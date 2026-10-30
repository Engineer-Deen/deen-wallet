package com.glr.deenwallet.monitoring;

import java.util.regex.Pattern;

/**
 * Recognises the classic signs of someone trying to trick the database through typed text (SQL injection).
 * Strong signs only, so ordinary messages are not flagged. It only reports; the database calls themselves
 * pass typed values separately from the SQL text, which is what actually stops the attack.
 */
final class SqlInjectionDetector {

    private SqlInjectionDetector() { }

    private record Rule(String label, Pattern pattern) { }

    private static final Rule[] RULES = {
            new Rule("UNION SELECT", Pattern.compile("(?i)\\bunion\\b[\\s/*+]+(all[\\s/*+]+)?select\\b")),
            new Rule("quote then OR/AND test (' or 1=1)", Pattern.compile("(?i)['\"`)]\\s*(or|and)\\s+['\"(]?\\w+['\"]?\\s*(=|<|>|like)")),
            new Rule("empty-string trick (' or ''=')", Pattern.compile("(?i)['\"]\\s*(or|and)\\s*['\"]{1,2}\\s*=\\s*['\"]")),
            new Rule("OR 1=1 test", Pattern.compile("(?i)\\b(or|and)\\s+(\\d+)\\s*=\\s*\\2\\b")),
            new Rule("stacked command (; DROP ...)", Pattern.compile("(?i);\\s*(drop\\s+(table|database|user|index)|truncate\\s+\\w|alter\\s+(table|user)|delete\\s+from|insert\\s+into|update\\s+\\w+\\s+set|create\\s+(table|user|role))")),
            new Rule("time-delay trick (sleep)", Pattern.compile("(?i)\\b(pg_sleep|sleep|benchmark)\\s*\\(|waitfor\\s+delay")),
            new Rule("reading system tables", Pattern.compile("(?i)\\b(information_schema|pg_catalog|pg_shadow|pg_user|xp_cmdshell|sqlite_master)\\b|into\\s+(out|dump)file|load_file\\s*\\(")),
            new Rule("SELECT ... FROM users", Pattern.compile("(?i)\\bselect\\b.{1,80}?\\bfrom\\s+(users|admin_audit|refresh_tokens|transactions|pg_\\w+)\\b")),
            new Rule("quote then SQL comment", Pattern.compile("['\"]\\s*(--(\\s|$)|/\\*)")),
    };

    private static final Pattern TOOLS = Pattern.compile("(?i)(sqlmap|havij|nikto|nmap|masscan|zgrab|acunetix|nessus|dirbuster|gobuster|wpscan|nuclei)");

    /** Name of the SQL-injection sign found in the text, or null. */
    static String detect(String text) {
        if (text == null || text.isEmpty()) return null;
        String t = text.length() > 10_000 ? text.substring(0, 10_000) : text;
        for (Rule r : RULES) {
            if (r.pattern().matcher(t).find()) return r.label();
        }
        return null;
    }

    /** Name of a well-known attack or scanning tool in the User-Agent, or null. */
    static String tool(String userAgent) {
        if (userAgent == null) return null;
        var m = TOOLS.matcher(userAgent);
        return m.find() ? m.group(1).toLowerCase() : null;
    }
}
