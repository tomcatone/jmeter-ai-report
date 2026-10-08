/* 場景/接口趨勢圖 + 響應時間分佈圖。依賴主腳本的全局 css()。數據由 Java 注入:TRG / TRS / DIST */
var REDRAW = [];
var PAL = ['#1976d2', '#f57c00', '#43a047', '#e53935', '#8e24aa', '#00acc1', '#6d4c41', '#c0ca33', '#d81b60', '#5e35b1', '#00897b', '#fb8c00'];

function T(tw, cn) { return '<span class="i" data-cn="' + cn + '">' + tw + '</span>'; }
function E(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;'); }
function fmtv(v, m) { return v == null ? '-' : (m == 'tps' || m == 'err' ? v.toFixed(2) : v.toFixed(0)); }

function buildMC(id, d, defN) {
  var root = document.getElementById(id);
  if (!root || !d || !d.names || !d.names.length) return;
  var mets = [['tps', 'TPS', 'TPS'], ['avg', '平均響應(ms)', '平均响应(ms)'],
              ['max', '最大響應(ms)', '最大响应(ms)'], ['err', '錯誤率(%)', '错误率(%)']];
  var met = 'tps';
  var vis = d.names.map(function (_, i) { return i < defN; });
  var h = '<div class="mt">';
  mets.forEach(function (m) { h += '<button data-m="' + m[0] + '">' + T(m[1], m[2]) + '</button>'; });
  h += '</div><div class="cw"><canvas width="1100" height="300"></canvas><div class="tip"></div></div><div class="lg"></div>';
  root.innerHTML = h;
  var cv = root.querySelector('canvas'), tip = root.querySelector('.tip'), lg = root.querySelector('.lg');
  var lh = '';
  d.names.forEach(function (n, i) {
    lh += '<label><input type="checkbox" data-i="' + i + '"' + (vis[i] ? ' checked' : '') + '><i style="background:' +
      PAL[i % PAL.length] + '"></i>' + E(n) + '</label>';
  });
  lg.innerHTML = lh;
  var pl = 50, pr = 10, pt = 10, pb = 26;

  function draw() {
    var g = cv.getContext('2d'), w = cv.width, h = cv.height, n = d.t.length, m = 0;
    g.clearRect(0, 0, w, h);
    d[met].forEach(function (s, i) { if (vis[i]) s.forEach(function (v) { if (v != null && v > m) m = v; }); });
    if (m <= 0) m = 1;
    g.strokeStyle = css('--grid'); g.fillStyle = css('--mut'); g.font = '11px sans-serif'; g.lineWidth = 1;
    for (var k = 0; k <= 4; k++) {
      var y = pt + (h - pt - pb) * k / 4;
      g.beginPath(); g.moveTo(pl, y); g.lineTo(w - pr, y); g.stroke();
      g.fillText((m * (4 - k) / 4).toFixed(m < 10 ? 2 : 0), 2, y + 4);
    }
    var xs = function (i) { return pl + (w - pl - pr) * i / Math.max(n - 1, 1); };
    var tk = Math.min(6, n);
    for (var j = 0; j < tk; j++) {
      var ix = tk > 1 ? Math.round((n - 1) * j / (tk - 1)) : 0;
      g.fillText(d.t[ix] + 's', xs(ix) - 10, h - 8);
    }
    d[met].forEach(function (s, i) {
      if (!vis[i]) return;
      g.strokeStyle = PAL[i % PAL.length]; g.lineWidth = 1.6; g.beginPath();
      var pen = false;
      s.forEach(function (v, j) {
        if (v == null) { pen = false; return; }
        var yy = pt + (h - pt - pb) * (1 - v / m);
        if (!pen) { g.moveTo(xs(j), yy); pen = true; } else g.lineTo(xs(j), yy);
      });
      g.stroke();
    });
  }

  function mark() {
    root.querySelectorAll('.mt button').forEach(function (b) { b.className = b.getAttribute('data-m') == met ? 'on' : ''; });
  }
  root.querySelectorAll('.mt button').forEach(function (b) {
    b.onclick = function () { met = b.getAttribute('data-m'); mark(); draw(); };
  });
  lg.querySelectorAll('input').forEach(function (c) {
    c.onchange = function () { vis[+c.getAttribute('data-i')] = c.checked; draw(); };
  });
  cv.onmousemove = function (e) {
    var r = cv.getBoundingClientRect(), n = d.t.length;
    var x = (e.clientX - r.left) * cv.width / r.width;
    var i = Math.round((x - pl) / (cv.width - pl - pr) * (n - 1));
    i = Math.max(0, Math.min(n - 1, i));
    var t = '<b>' + d.t[i] + 's</b>';
    d.names.forEach(function (nm, k) {
      if (vis[k]) t += '<br><span style="color:' + PAL[k % PAL.length] + '">■</span> ' + E(nm) + ': ' + fmtv(d[met][k][i], met);
    });
    tip.innerHTML = t; tip.style.display = 'block';
    var left = (e.clientX - r.left) + 14;
    if (left > r.width - 200) left = (e.clientX - r.left) - 200;
    tip.style.left = left + 'px';
  };
  cv.onmouseleave = function () { tip.style.display = 'none'; };
  mark(); draw();
  REDRAW.push(draw);
}

function buildHist(id, D) {
  var root = document.getElementById(id);
  if (!root || !D || !D.labels) return;
  var src = [{ tw: '全部', cn: '全部', c: D.all }];
  if (D.groups.length > 1) D.groups.forEach(function (x) { src.push({ tw: '場景: ' + x.n, cn: '场景: ' + x.n, c: x.c }); });
  D.samplers.forEach(function (x) { src.push({ tw: '接口: ' + x.n, cn: '接口: ' + x.n, c: x.c }); });
  var h = '<select>';
  src.forEach(function (o, i) { h += '<option value="' + i + '" data-cn="' + E(o.cn) + '">' + E(o.tw) + '</option>'; });
  h += '</select> <span style="font-size:12px;color:var(--mut)">' + T('橫軸:響應時間區間(ms);柱:樣本數;線:累計佔比', '横轴:响应时间区间(ms);柱:样本数;线:累计占比') + '</span>';
  h += '<div class="cw"><canvas width="1100" height="320"></canvas></div>';
  root.innerHTML = h;
  var sel = root.querySelector('select'), cv = root.querySelector('canvas');
  var pl = 55, pr = 45, pt = 16, pb = 34;

  function draw() {
    var g = cv.getContext('2d'), w = cv.width, hh = cv.height;
    var c = src[+sel.value].c, tot = 0, mx = 1;
    c.forEach(function (v) { tot += v; if (v > mx) mx = v; });
    g.clearRect(0, 0, w, hh);
    g.font = '11px sans-serif'; g.lineWidth = 1;
    for (var k = 0; k <= 4; k++) {
      var y = pt + (hh - pt - pb) * k / 4;
      g.strokeStyle = css('--grid'); g.beginPath(); g.moveTo(pl, y); g.lineTo(w - pr, y); g.stroke();
      g.fillStyle = css('--mut');
      g.fillText(Math.round(mx * (4 - k) / 4), 4, y + 4);
      g.fillText((100 * (4 - k) / 4) + '%', w - pr + 4, y + 4);
    }
    if (tot == 0) return;
    var bw = (w - pl - pr) / c.length, cum = 0, pts = [];
    c.forEach(function (v, i) {
      var x = pl + i * bw, bh = (hh - pt - pb) * v / mx;
      g.fillStyle = '#1976d2'; g.fillRect(x + 4, hh - pb - bh, bw - 8, bh);
      g.fillStyle = css('--fg');
      if (v > 0) g.fillText((v * 100 / tot).toFixed(1) + '%', x + 5, hh - pb - bh - 4);
      g.fillStyle = css('--mut');
      g.fillText(D.labels[i], x + 4, hh - 14);
      cum += v;
      pts.push([x + bw / 2, pt + (hh - pt - pb) * (1 - cum / tot)]);
    });
    g.strokeStyle = '#f57c00'; g.lineWidth = 2; g.beginPath();
    pts.forEach(function (p, i) { if (i) g.lineTo(p[0], p[1]); else g.moveTo(p[0], p[1]); });
    g.stroke();
    g.fillStyle = '#f57c00';
    pts.forEach(function (p) { g.beginPath(); g.arc(p[0], p[1], 3, 0, 6.2832); g.fill(); });
  }
  sel.onchange = draw;
  draw();
  REDRAW.push(draw);
}

function initExtra() {
  if (window.TRG) buildMC('mcG', TRG, 8);
  if (window.TRS) buildMC('mcS', TRS, 8);
  if (window.DIST) buildHist('hist', DIST);
}
