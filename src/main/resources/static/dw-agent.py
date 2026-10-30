#!/usr/bin/env python3
"""DeenWallet monitoring agent.

Runs on an extra server (for example your database machine) and reports to the DeenWallet Metrics tab every minute:
processor, memory, storage, load, what needs updating (with the command to run), SSH guessing and weak spots.
It only READS the machine and sends a small report. It never changes anything except its own two small systemd files.

Install (as root, on the extra server):
    curl -fsSL https://YOUR-API/dw-agent.py -o dw-agent.py
    sudo python3 dw-agent.py --install --url https://YOUR-API --token YOUR_TOKEN --name "Database VM"
Remove:
    sudo python3 dw-agent.py --uninstall
Test without sending anything:
    sudo python3 dw-agent.py --print
"""
import argparse
import json
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

VERSION = "1"
CONF = "/etc/dw-agent.json"
BIN = "/usr/local/bin/dw-agent.py"
CACHE = "/var/tmp/dw-agent-apt.json"
SERVICE = "/etc/systemd/system/dw-agent.service"
TIMER = "/etc/systemd/system/dw-agent.timer"


def read(path, limit=None):
    try:
        with open(path, "r", errors="replace") as f:
            return f.read() if limit is None else f.read(limit)
    except Exception:
        return ""


def run(cmd, timeout=20):
    try:
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=timeout, text=True)
        return p.stdout or ""
    except Exception:
        return None


def cpu_sample():
    line = read("/proc/stat", 400).split("\n")[0].split()
    if len(line) < 5 or line[0] != "cpu":
        return None
    vals = [int(x) for x in line[1:9] if x.isdigit()]
    return sum(vals), int(line[4]) + (int(line[5]) if len(line) > 5 else 0)


def cpu_percent():
    a = cpu_sample()
    time.sleep(1)
    b = cpu_sample()
    if not a or not b or b[0] <= a[0]:
        return 0.0
    return round(max(0.0, min(100.0, 100.0 * ((b[0] - a[0]) - (b[1] - a[1])) / (b[0] - a[0]))), 1)


def meminfo():
    out = {}
    for line in read("/proc/meminfo").split("\n"):
        if ":" in line:
            k, v = line.split(":", 1)
            try:
                out[k] = int(v.strip().split()[0]) * 1024
            except Exception:
                pass
    return out


def net_totals():
    rx = tx = 0
    for line in read("/proc/net/dev").split("\n")[2:]:
        if ":" not in line:
            continue
        name, data = line.split(":", 1)
        if name.strip() == "lo":
            continue
        f = data.split()
        if len(f) >= 9:
            rx += int(f[0])
            tx += int(f[8])
    return rx, tx


def item(level, title, detail="", fix=""):
    return {"level": level, "title": title, "detail": detail, "fix": fix}


def public_ports():
    ports = set()
    for fn, v6 in (("/proc/net/tcp", False), ("/proc/net/tcp6", True)):
        for line in read(fn).split("\n")[1:]:
            f = line.split()
            if len(f) < 4 or f[3] != "0A":
                continue
            addr, _, port = f[1].partition(":")
            if (addr == "0" * 32) if v6 else (addr == "00000000"):
                try:
                    ports.add(int(port, 16))
                except ValueError:
                    pass
    return sorted(ports)


EOL = {"18.04": "2023-05-31", "20.04": "2025-05-31", "22.04": "2027-06-01", "24.04": "2029-06-01"}


def apt_state():
    """Waiting updates are checked at most every 30 minutes (apt is slowish)."""
    try:
        c = json.loads(read(CACHE) or "{}")
        if time.time() - c.get("at", 0) < 1800:
            return c
    except Exception:
        pass
    c = {"at": time.time(), "total": None, "security": 0, "autoremove": 0}
    out = run(["apt", "list", "--upgradable"], 25)
    if out is not None:
        lines = [l for l in out.split("\n") if "/" in l and not l.startswith("Listing")]
        c["total"] = len(lines)
        c["security"] = len([l for l in lines if "-security" in l])
    out = run(["apt-get", "-s", "autoremove"], 20)
    if out is not None:
        c["autoremove"] = len([l for l in out.split("\n") if l.startswith("Remv ")])
    try:
        with open(CACHE, "w") as f:
            json.dump(c, f)
    except Exception:
        pass
    return c


def tasks(disk_pct):
    t = []
    c = apt_state()
    if c["total"] is None:
        t.append(item("info", "Waiting updates could not be checked", "This machine does not use apt.", "Check by hand: sudo apt update && apt list --upgradable"))
    elif c["security"] > 0:
        t.append(item("bad", "%d security update(s) are waiting" % c["security"], "%d package update(s) waiting in total. Security updates close holes attackers already know about." % c["total"],
                      "sudo apt update && sudo apt upgrade -y"))
    elif c["total"] > 0:
        t.append(item("warn", "%d system update(s) are waiting" % c["total"], "None is marked as a security update.", "sudo apt update && sudo apt upgrade -y"))
    else:
        t.append(item("ok", "System packages are up to date", "No updates are waiting (based on the last package list refresh)."))
    if os.path.exists("/var/run/reboot-required") or os.path.exists("/run/reboot-required"):
        t.append(item("warn", "This machine needs a restart", "Some updates only take effect after a restart.", "sudo reboot   (do it when few people are using the app)"))
    auto = read("/etc/apt/apt.conf.d/20auto-upgrades")
    if 'Unattended-Upgrade "1"' in auto:
        t.append(item("ok", "Automatic security updates are on"))
    elif os.path.isdir("/etc/apt/apt.conf.d"):
        t.append(item("warn", "Automatic security updates are not on", "Nobody installs security fixes unless you do it by hand.",
                      "sudo apt install unattended-upgrades && sudo dpkg-reconfigure -plow unattended-upgrades"))
    # ssh settings (first value found wins, files in sshd_config.d come first)
    files = []
    d = "/etc/ssh/sshd_config.d"
    if os.path.isdir(d):
        files += [os.path.join(d, x) for x in sorted(os.listdir(d)) if x.endswith(".conf")]
    files.append("/etc/ssh/sshd_config")
    pw = root = None
    seen = False
    for fn in files:
        txt = read(fn)
        if txt:
            seen = True
        for line in txt.split("\n"):
            s = line.strip()
            if not s or s.startswith("#"):
                continue
            p = s.split()
            if len(p) < 2:
                continue
            if pw is None and p[0].lower() == "passwordauthentication":
                pw = p[1].lower()
            if root is None and p[0].lower() == "permitrootlogin":
                root = p[1].lower()
    if seen:
        if pw == "yes":
            t.append(item("bad", "SSH accepts passwords", "Anyone on the internet can keep guessing the password. Keys cannot be guessed.",
                          "Set PasswordAuthentication no in /etc/ssh/sshd_config, then: sudo systemctl restart ssh   (keep your key login working first)"))
        elif pw is None:
            t.append(item("warn", "SSH password setting is not set", "The default allows password logins unless a setting turns them off.",
                          "Add PasswordAuthentication no to /etc/ssh/sshd_config, then: sudo systemctl restart ssh   (keep your key login working first)"))
        else:
            t.append(item("ok", "SSH uses keys only"))
        if root == "yes":
            t.append(item("bad", "SSH allows logging in as root", "Attackers always try root first.", "Set PermitRootLogin no in /etc/ssh/sshd_config, then: sudo systemctl restart ssh"))
    if os.path.exists("/etc/fail2ban") or os.path.exists("/run/fail2ban/fail2ban.sock"):
        t.append(item("ok", "fail2ban is installed"))
    else:
        t.append(item("warn", "No automatic blocking of attackers (fail2ban)", "People who keep guessing SSH passwords are never blocked.", "sudo apt install fail2ban -y"))
    ufw = read("/etc/ufw/ufw.conf")
    if "ENABLED=yes" in ufw.replace(" ", ""):
        t.append(item("ok", "Firewall (ufw) is on"))
    elif os.path.exists("/etc/iptables/rules.v4"):
        t.append(item("ok", "Firewall (iptables) rules are saved", "This is the firewall Oracle Ubuntu images use. Do not also turn on ufw."))
    elif ufw:
        t.append(item("info", "No saved firewall rules found", "On Oracle Cloud the cloud firewall (Security List) may still protect this machine."))
    ports = public_ports()
    risky = [p for p in ports if p in (5432, 3306, 6379, 27017, 9200, 11211, 2375, 5984)]
    other = [p for p in ports if p not in (22, 80, 443) and p not in risky]
    if risky:
        t.append(item("bad", "Database / internal port open to everyone: %s" % risky, "These ports should only be reachable from your own servers.",
                      "Make the service listen only on your private address (PostgreSQL: listen_addresses in postgresql.conf) and allow only your app server in the firewall / Oracle Security List."))
    if other:
        t.append(item("info", "Other ports listening on all addresses: %s" % other, "Make sure you recognise each of these.",
                      "If a service does not need the internet, make it listen on 127.0.0.1 or your private address."))
    if not risky and not other:
        t.append(item("ok", "Only the normal ports are open", "Listening on all addresses: %s" % ports))
    # end of support
    ver = ""
    ident = ""
    for line in read("/etc/os-release").split("\n"):
        if line.startswith("ID="):
            ident = line[3:].strip('"')
        if line.startswith("VERSION_ID="):
            ver = line[11:].strip('"')
    if ident == "ubuntu" and ver in EOL:
        days = int((time.mktime(time.strptime(EOL[ver], "%Y-%m-%d")) - time.time()) // 86400)
        if days < 0:
            t.append(item("bad", "Ubuntu %s is no longer supported" % ver, "It stopped getting free security fixes on %s." % EOL[ver],
                          "Back up first, then: sudo do-release-upgrade   (or move to a new machine with a current Ubuntu)"))
        elif days < 180:
            t.append(item("warn", "Ubuntu %s support ends in %d days" % (ver, days), "Free security fixes stop on %s." % EOL[ver], "Plan the upgrade: sudo do-release-upgrade   (back up first)"))
    # disk and cleanup
    if disk_pct >= 75:
        lvl = "bad" if disk_pct >= 90 else "warn"
        t.append(item(lvl, "Storage is %d%% full" % round(disk_pct), "The machine and database can stop working when the disk fills.",
                      "sudo apt autoremove -y && sudo apt clean && sudo journalctl --vacuum-size=200M\ndu -xh / --max-depth=2 2>/dev/null | sort -h | tail -15   # shows what is big"))
    elif c.get("autoremove", 0) > 0 and disk_pct >= 70:
        t.append(item("info", "%d old package(s) can be removed" % c["autoremove"], "Frees a little space.", "sudo apt autoremove -y && sudo apt clean"))
    return t


FAILED = re.compile(r"Failed password for (?:invalid user )?(\S+) from (\S+) port")
ACCEPTED = re.compile(r"Accepted (\S+) for (\S+) from (\S+) port")


def ssh_attempts():
    fn = "/var/log/auth.log" if os.path.exists("/var/log/auth.log") else "/var/log/secure"
    if not os.path.exists(fn):
        return {"available": False, "hint": "No SSH log file (/var/log/auth.log) on this machine."}
    try:
        with open(fn, "rb") as f:
            f.seek(0, 2)
            size = f.tell()
            f.seek(max(0, size - 4000000))
            text = f.read().decode("utf-8", "replace")
    except Exception as e:
        return {"available": False, "hint": "The SSH log could not be read: %s" % type(e).__name__}
    per, users, acc, attempts = {}, {}, [], 0
    for line in text.split("\n"):
        if "sshd" not in line:
            continue
        m = FAILED.search(line)
        if m:
            attempts += 1
            per[m.group(2)] = per.get(m.group(2), 0) + 1
            users.setdefault(m.group(2), [])
            if m.group(1) not in users[m.group(2)]:
                users[m.group(2)].append(m.group(1))
            continue
        a = ACCEPTED.search(line)
        if a:
            ts = line.split()[0] if re.match(r"^\d{4}-", line) else line[:15]
            acc.append({"time": ts, "how": a.group(1), "user": a.group(2), "ip": a.group(3)})
            acc = acc[-50:]
    top = []
    for ip, n in sorted(per.items(), key=lambda x: -x[1])[:8]:
        top.append({"ip": ip, "attempts": n, "usernames": users.get(ip, [])[:4], "level": "high" if n >= 50 else "medium" if n >= 10 else "low"})
    return {"available": True, "attempts": attempts, "uniqueIps": len(per), "topIps": top, "recentLogins": list(reversed(acc[-5:]))}


def collect(name):
    mem = meminfo()
    total = mem.get("MemTotal", 0)
    avail = mem.get("MemAvailable", 0)
    st = os.statvfs("/")
    dtotal = st.f_blocks * st.f_frsize
    dfree = st.f_bavail * st.f_frsize
    dpct = round(100.0 * (dtotal - dfree) / dtotal, 1) if dtotal else 0.0
    la = read("/proc/loadavg").split()
    up = read("/proc/uptime").split()
    rx, tx = net_totals()
    osname = ""
    for line in read("/etc/os-release").split("\n"):
        if line.startswith("PRETTY_NAME="):
            osname = line[12:].strip('"')
    return {
        "name": name,
        "agentVersion": VERSION,
        "server": {
            "cores": os.cpu_count() or 1,
            "cpuPercent": cpu_percent(),
            "load1": float(la[0]) if len(la) > 2 else -1, "load5": float(la[1]) if len(la) > 2 else -1, "load15": float(la[2]) if len(la) > 2 else -1,
            "ramTotal": total, "ramUsed": max(0, total - avail), "ramPercent": round(100.0 * (total - avail) / total, 1) if total else 0.0,
            "swapTotal": mem.get("SwapTotal", 0), "swapUsed": max(0, mem.get("SwapTotal", 0) - mem.get("SwapFree", 0)),
            "diskTotal": dtotal, "diskUsed": dtotal - dfree, "diskPercent": dpct,
            "uptimeSeconds": int(float(up[0])) if up else 0,
            "rxTotal": rx, "txTotal": tx,
            "os": osname or platform.platform(), "kernel": platform.release(), "host": socket.gethostname(),
        },
        "tasks": tasks(dpct),
        "ssh": ssh_attempts(),
    }


def send(url, token, payload):
    req = urllib.request.Request(url.rstrip("/") + "/api/monitor/agent/report", data=json.dumps(payload).encode("utf-8"), method="POST",
                                 headers={"Content-Type": "application/json", "X-Agent-Token": token, "User-Agent": "dw-agent/" + VERSION})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, ""
    except urllib.error.HTTPError as e:
        return e.code, ""
    except Exception as e:
        return 0, "%s: %s" % (type(e).__name__, e)


def install(url, token, name):
    if os.geteuid() != 0:
        sys.exit("Run this with sudo.")
    if not re.match(r"^[A-Za-z0-9 ._-]{1,40}$", name):
        sys.exit("The name may only use letters, numbers, spaces, dots, dashes and underscores (max 40).")
    print("Testing the connection to %s ..." % url)
    code, err = send(url, token, collect(name))
    if code not in (200, 204):
        reason = {401: "The token is wrong.", 404: "The server has no MONITOR_AGENT_TOKEN set yet (or the address is wrong).", 0: err}.get(code, "HTTP %s" % code)
        sys.exit("Could not report: %s Nothing was installed." % reason)
    shutil.copy2(os.path.abspath(__file__), BIN)
    os.chmod(BIN, 0o755)
    with open(CONF, "w") as f:
        json.dump({"url": url, "token": token, "name": name}, f)
    os.chmod(CONF, 0o600)
    with open(SERVICE, "w") as f:
        f.write("[Unit]\nDescription=DeenWallet monitoring agent\nAfter=network-online.target\n\n[Service]\nType=oneshot\nExecStart=/usr/bin/python3 %s\n" % BIN)
    with open(TIMER, "w") as f:
        f.write("[Unit]\nDescription=Run the DeenWallet monitoring agent every minute\n\n[Timer]\nOnBootSec=30\nOnUnitActiveSec=60\nAccuracySec=5\n\n[Install]\nWantedBy=timers.target\n")
    run(["systemctl", "daemon-reload"], 30)
    run(["systemctl", "enable", "--now", "dw-agent.timer"], 30)
    print("Installed. '%s' will appear in Server Metrics within a minute." % name)


def uninstall():
    if os.geteuid() != 0:
        sys.exit("Run this with sudo.")
    run(["systemctl", "disable", "--now", "dw-agent.timer"], 30)
    for p in (SERVICE, TIMER, BIN, CONF, CACHE):
        try:
            os.remove(p)
        except FileNotFoundError:
            pass
    run(["systemctl", "daemon-reload"], 30)
    print("Removed.")


def main():
    ap = argparse.ArgumentParser(description="DeenWallet monitoring agent")
    ap.add_argument("--install", action="store_true")
    ap.add_argument("--uninstall", action="store_true")
    ap.add_argument("--print", action="store_true", help="show the report instead of sending it")
    ap.add_argument("--url")
    ap.add_argument("--token")
    ap.add_argument("--name")
    a = ap.parse_args()
    if a.uninstall:
        return uninstall()
    if a.install:
        if not (a.url and a.token and a.name):
            sys.exit("Use: sudo python3 dw-agent.py --install --url https://YOUR-API --token YOUR_TOKEN --name \"Database VM\"")
        return install(a.url, a.token, a.name)
    conf = {}
    try:
        conf = json.loads(read(CONF) or "{}")
    except Exception:
        pass
    name = a.name or conf.get("name") or socket.gethostname()
    payload = collect(name)
    if a.print:
        print(json.dumps(payload, indent=2))
        return
    url, token = a.url or conf.get("url"), a.token or conf.get("token")
    if not url or not token:
        sys.exit("Not installed yet. See the instructions at the top of this file.")
    code, err = send(url, token, payload)
    if code not in (200, 204):
        sys.exit("Report failed: %s" % (err or "HTTP %s" % code))


if __name__ == "__main__":
    main()
