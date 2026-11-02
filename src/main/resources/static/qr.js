/* DeenWallet QR code maker. Small, no dependencies. Byte mode, error correction level M, versions 1-6 (up to 106 characters).
   DWQR.matrix(text) -> array of rows of 0/1 (or null if the text is too long). DWQR.svg(text, opts) -> SVG markup string. */
(function (root) {
  'use strict';
  var EXP = new Array(512), LOG = new Array(256), i, x = 1;
  for (i = 0; i < 255; i++) { EXP[i] = x; LOG[x] = i; x <<= 1; if (x & 256) x ^= 0x11d; }
  for (i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
  function gmul(a, b) { return a && b ? EXP[LOG[a] + LOG[b]] : 0; }
  function rsGen(n) {
    var p = [1], k, j;
    for (k = 0; k < n; k++) {
      var q = new Array(p.length + 1).fill(0);
      for (j = 0; j < p.length; j++) { q[j] ^= p[j]; q[j + 1] ^= gmul(p[j], EXP[k]); }
      p = q;
    }
    return p;
  }
  function rsEc(data, n) {
    var gen = rsGen(n), res = new Array(n).fill(0), k, j;
    for (k = 0; k < data.length; k++) {
      var f = data[k] ^ res[0];
      res.shift(); res.push(0);
      for (j = 0; j < n; j++) res[j] ^= gmul(gen[j + 1], f);
    }
    return res;
  }
  // level M: [data codewords, EC codewords per block, blocks]
  var VERS = [null, [16, 10, 1], [28, 16, 1], [44, 26, 1], [64, 18, 2], [86, 24, 2], [108, 16, 4]];
  var ALIGN = [null, [], [6, 18], [6, 22], [6, 26], [6, 30], [6, 34]];

  function encode(text) {
    var bytes = [], k;
    var u = unescape(encodeURIComponent(text));
    for (k = 0; k < u.length; k++) bytes.push(u.charCodeAt(k));
    var ver = 0;
    for (k = 1; k <= 6; k++) { if (bytes.length + 2 <= VERS[k][0]) { ver = k; break; } }
    if (!ver) return null;
    var cap = VERS[ver][0], ecn = VERS[ver][1], nb = VERS[ver][2];
    var bits = [];
    function put(v, n) { for (var b = n - 1; b >= 0; b--) bits.push((v >> b) & 1); }
    put(4, 4); put(bytes.length, 8);
    for (k = 0; k < bytes.length; k++) put(bytes[k], 8);
    var maxBits = cap * 8;
    for (k = 0; k < 4 && bits.length < maxBits; k++) bits.push(0);
    while (bits.length % 8) bits.push(0);
    var data = [];
    for (k = 0; k < bits.length; k += 8) { var v = 0; for (var b = 0; b < 8; b++) v = (v << 1) | bits[k + b]; data.push(v); }
    for (k = 0; data.length < cap; k++) data.push(k % 2 ? 0x11 : 0xec);
    var per = cap / nb, blocks = [], ecs = [];
    for (k = 0; k < nb; k++) { var d = data.slice(k * per, (k + 1) * per); blocks.push(d); ecs.push(rsEc(d, ecn)); }
    var out = [], j;
    for (j = 0; j < per; j++) for (k = 0; k < nb; k++) out.push(blocks[k][j]);
    for (j = 0; j < ecn; j++) for (k = 0; k < nb; k++) out.push(ecs[k][j]);
    var all = [];
    for (k = 0; k < out.length; k++) for (var b2 = 7; b2 >= 0; b2--) all.push((out[k] >> b2) & 1);
    return { ver: ver, bits: all };
  }

  function build(ver, bits, mask) {
    var n = 17 + ver * 4, m = [], fn = [], r, c;
    for (r = 0; r < n; r++) { m.push(new Array(n).fill(0)); fn.push(new Array(n).fill(false)); }
    function set(r, c, v) { if (r >= 0 && c >= 0 && r < n && c < n) { m[r][c] = v ? 1 : 0; fn[r][c] = true; } }
    function finder(r0, c0) {
      for (var dr = -1; dr <= 7; dr++) for (var dc = -1; dc <= 7; dc++) {
        var on = dr >= 0 && dr <= 6 && dc >= 0 && dc <= 6 && (dr === 0 || dr === 6 || dc === 0 || dc === 6 || (dr >= 2 && dr <= 4 && dc >= 2 && dc <= 4));
        set(r0 + dr, c0 + dc, on);
      }
    }
    finder(0, 0); finder(0, n - 7); finder(n - 7, 0);
    for (c = 8; c < n - 8; c++) { set(6, c, c % 2 === 0); set(c, 6, c % 2 === 0); }
    var ap = ALIGN[ver];
    for (var a = 0; a < ap.length; a++) for (var b = 0; b < ap.length; b++) {
      var ar = ap[a], ac = ap[b];
      if ((a === 0 && b === 0) || (a === 0 && b === ap.length - 1) || (a === ap.length - 1 && b === 0)) continue;
      for (var dr = -2; dr <= 2; dr++) for (var dc = -2; dc <= 2; dc++) set(ar + dr, ac + dc, Math.max(Math.abs(dr), Math.abs(dc)) !== 1);
    }
    set(n - 8, 8, 1);
    for (k = 0; k < 9; k++) { if (!fn[8][k]) set(8, k, 0); if (!fn[k][8]) set(k, 8, 0); }
    for (var k = 0; k < 8; k++) { if (!fn[8][n - 1 - k]) set(8, n - 1 - k, 0); if (!fn[n - 1 - k][8]) set(n - 1 - k, 8, 0); }
    set(n - 8, 8, 1);
    var idx = 0, up = true;
    for (c = n - 1; c > 0; c -= 2) {
      if (c === 6) c--;
      for (var t = 0; t < n; t++) {
        r = up ? n - 1 - t : t;
        for (var cc = c; cc >= c - 1; cc--) {
          if (fn[r][cc]) continue;
          var v = idx < bits.length ? bits[idx] : 0; idx++;
          var inv = false;
          switch (mask) {
            case 0: inv = (r + cc) % 2 === 0; break;
            case 1: inv = r % 2 === 0; break;
            case 2: inv = cc % 3 === 0; break;
            case 3: inv = (r + cc) % 3 === 0; break;
            case 4: inv = (Math.floor(r / 2) + Math.floor(cc / 3)) % 2 === 0; break;
            case 5: inv = (r * cc) % 2 + (r * cc) % 3 === 0; break;
            case 6: inv = ((r * cc) % 2 + (r * cc) % 3) % 2 === 0; break;
            default: inv = ((r + cc) % 2 + (r * cc) % 3) % 2 === 0;
          }
          m[r][cc] = v ^ (inv ? 1 : 0);
        }
      }
      up = !up;
    }
    // format information: level M = 00, then mask
    var data = (0 << 3) | mask, rem = data;
    for (k = 0; k < 10; k++) rem = (rem << 1) ^ ((rem >> 9) * 0x537);
    var fmt = ((data << 10) | rem) ^ 0x5412;
    function fb(i) { return (fmt >> i) & 1; }
    for (k = 0; k <= 5; k++) m[k][8] = fb(k);
    m[7][8] = fb(6); m[8][8] = fb(7); m[8][7] = fb(8);
    for (k = 9; k < 15; k++) m[8][14 - k] = fb(k);
    for (k = 0; k < 8; k++) m[8][n - 1 - k] = fb(k);
    for (k = 8; k < 15; k++) m[n - 15 + k][8] = fb(k);
    m[n - 8][8] = 1;
    return m;
  }

  function penalty(m) {
    var n = m.length, p = 0, r, c, run, k;
    for (r = 0; r < n; r++) {
      run = 1;
      for (c = 1; c < n; c++) { if (m[r][c] === m[r][c - 1]) { run++; if (run === 5) p += 3; else if (run > 5) p++; } else run = 1; }
    }
    for (c = 0; c < n; c++) {
      run = 1;
      for (r = 1; r < n; r++) { if (m[r][c] === m[r - 1][c]) { run++; if (run === 5) p += 3; else if (run > 5) p++; } else run = 1; }
    }
    for (r = 0; r < n - 1; r++) for (c = 0; c < n - 1; c++) {
      var s = m[r][c] + m[r][c + 1] + m[r + 1][c] + m[r + 1][c + 1];
      if (s === 0 || s === 4) p += 3;
    }
    var pat = [1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0], pat2 = pat.slice().reverse();
    function match(get, q) { var a = true, b = true; for (var z = 0; z < 11; z++) { var v = get(z); if (v !== pat[z]) a = false; if (v !== pat2[z]) b = false; } return a || b; }
    for (r = 0; r < n; r++) for (c = 0; c + 11 <= n; c++) {
      if (match(function (z) { return m[r][c + z]; })) p += 40;
      if (match(function (z) { return m[c + z][r]; })) p += 40;
    }
    var dark = 0;
    for (r = 0; r < n; r++) for (c = 0; c < n; c++) dark += m[r][c];
    k = Math.floor(Math.abs(dark * 100 / (n * n) - 50) / 5);
    p += k * 10;
    return p;
  }

  function matrix(text, forceMask) {
    var e = encode(String(text));
    if (!e) return null;
    var best = null, bestP = Infinity, mk;
    for (mk = 0; mk < 8; mk++) {
      if (forceMask !== undefined && mk !== forceMask) continue;
      var m = build(e.ver, e.bits, mk), p = penalty(m);
      if (p < bestP) { bestP = p; best = m; }
    }
    return best;
  }

  function svg(text, opts) {
    opts = opts || {};
    var m = matrix(text, opts.mask);
    if (!m) return '';
    var quiet = opts.margin === undefined ? 4 : opts.margin, n = m.length, size = n + quiet * 2, r, c, d = '';
    for (r = 0; r < n; r++) for (c = 0; c < n; c++) if (m[r][c]) d += 'M' + (c + quiet) + ' ' + (r + quiet) + 'h1v1h-1z';
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + size + ' ' + size + '" shape-rendering="crispEdges"' +
      (opts.size ? ' width="' + opts.size + '" height="' + opts.size + '"' : '') + ' role="img" aria-label="QR code">' +
      '<rect width="' + size + '" height="' + size + '" fill="' + (opts.bg || '#ffffff') + '"/>' +
      '<path d="' + d + '" fill="' + (opts.fg || '#0f2a33') + '"/></svg>';
  }

  root.DWQR = { matrix: matrix, svg: svg };
})(typeof window !== 'undefined' ? window : this);
