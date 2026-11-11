// CS2A Помощник — cs2a_inject.js (внедряется в WebView фоновой службы спама)
// Слит из расширения: page/core.js (перехват WebSocket/fetch/XHR, поиск JWT) +
// page/spam.js (обучение формату отправки, отправка, детект остановки чата) +
// content/chat.js (ловит, когда вы сами пишете в чат). В WebView отдельных «миров» нет,
// поэтому вместо window.postMessage между расширением и страницей здесь прямой мост
// AndroidBridge (добавлен через addJavascriptInterface в SpamForegroundService).
(function () {
  'use strict';
  if (window.__cs2a) return;
  var JWT_RE = /eyJ[\w-]{6,}\.eyJ[\w-]{6,}\.[\w-]{6,}/g;
  var A = (window.__cs2a = { sockets: new Set(), recent: [], tokens: new Set(), apiToken: null, outHooks: [], inHooks: [] });

  function emit(type, data) {
    try { if (window.AndroidBridge) window.AndroidBridge.emit(type, JSON.stringify(data || {})); } catch (e) {}
  }
  A.redact = function (s, n) { return String(s == null ? '' : s).replace(JWT_RE, '<JWT>').slice(0, n || 160); };
  A.abs = function (u) { try { return new URL(u, location.href).href; } catch (e) { return String(u); } };

  function decodeJwt(t) {
    try {
      var p = t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
      p += '='.repeat((4 - (p.length % 4)) % 4);
      var bin = atob(p);
      return JSON.parse(new TextDecoder().decode(Uint8Array.from(bin, function (c) { return c.charCodeAt(0); })));
    } catch (e) { return null; }
  }
  A.decodeJwt = decodeJwt;
  var isApi = function (pl) { return !!pl && pl.id != null && typeof pl.exp === 'number'; };
  A.noteToken = function (value, src) {
    if (A.tokens.has(value)) return;
    var pl = decodeJwt(value);
    if (!pl || typeof pl !== 'object') return;
    A.tokens.add(value);
    var exp = typeof pl.exp === 'number' ? pl.exp * 1000 : 0;
    if (!isApi(pl)) return;
    if (!A.apiToken || exp >= A.apiToken.exp) A.apiToken = { value: value, exp: exp, src: src };
    emit('jwt', { token: value, exp: exp, src: src });
  };
  A.scanTokens = function (s, src) {
    if (typeof s !== 'string' || s.length < 30) return;
    var m = s.match(JWT_RE);
    if (m) for (var i = 0; i < m.length; i++) A.noteToken(m[i], src);
  };

  // ---------- WebSocket ----------
  var NativeWS = window.WebSocket;
  var nativeSend = NativeWS.prototype.send;
  A.nativeSend = function (ws, data) { return nativeSend.call(ws, data); };
  function routeCrashFrame(raw) {
    if (typeof raw !== 'string' || raw.indexOf('csgorun:crash') < 0) return;
    var root;
    try { root = JSON.parse(raw); } catch (e) { return; }
    var found = [];
    function walk(v, depth) {
      if (depth > 8 || v == null) return;
      if (typeof v === 'string') {
        var t = v.trim();
        if ((t.charAt(0) === '{' || t.charAt(0) === '[') && t.length < 20000) {
          try { walk(JSON.parse(t), depth + 1); } catch (e) {}
        }
        return;
      }
      if (typeof v !== 'object') return;
      if (!Array.isArray(v) && (v.type === 'start' || v.type === 'update' || v.type === 'crash')) found.push(v);
      if (Array.isArray(v)) v.forEach(function (x) { walk(x, depth + 1); });
      else Object.keys(v).forEach(function (k) { walk(v[k], depth + 1); });
    }
    walk(root, 0);
    found.forEach(function (e) {
      emit('crash-event', { event: { type:e.type, id:e.id == null ? null : e.id, status:e.status == null ? null : e.status, delta:e.delta == null ? null : e.delta, crash:e.crash == null ? null : e.crash } });
    });
  }
  A.onIncoming = function (ws, data) {
    if (typeof data !== 'string') return;
    try { routeCrashFrame(data); } catch (e) {}
    for (var i = 0; i < A.inHooks.length; i++) { try { A.inHooks[i]('ws', data, ws); } catch (e) {} }
  };
  A.onOutgoing = function (ws, data) {
    if (typeof data !== 'string') return;
    A.scanTokens(data, 'ws-out');
    A.rememberOut({ kind: 'ws', via: 'ws', ws: ws, data: data, url: ws.__cs2aUrl });
  };
  A.rememberOut = function (e) {
    e.t = Date.now();
    A.recent.push(e);
    if (A.recent.length > 40) A.recent.shift();
    for (var i = 0; i < A.outHooks.length; i++) { try { A.outHooks[i](e); } catch (err) {} }
  };
  function track(ws, url) {
    ws.__cs2aUrl = A.abs(url);
    A.sockets.add(ws);
    A.scanTokens(ws.__cs2aUrl, 'ws-url');
    ws.addEventListener('message', function (ev) { A.onIncoming(ws, ev.data); });
    ws.addEventListener('close', function () { A.sockets.delete(ws); });
  }
  window.WebSocket = new Proxy(NativeWS, {
    construct: function (target, args, newTarget) {
      var ws = Reflect.construct(target, args, newTarget);
      try { track(ws, args[0]); } catch (e) {}
      return ws;
    },
  });
  NativeWS.prototype.send = function (data) {
    try { A.onOutgoing(this, data); } catch (e) {}
    return nativeSend.apply(this, arguments);
  };

  // ---------- fetch ----------
  var nativeFetch = window.fetch;
  A.nativeFetch = nativeFetch.bind(window);
  window.fetch = function (input, init) {
    var e = null;
    try {
      var req = typeof Request !== 'undefined' && input instanceof Request ? input : null;
      var url = A.abs(req ? req.url : input instanceof URL ? input.href : input);
      var method = String((init && init.method) || (req && req.method) || 'GET').toUpperCase();
      var headers = {};
      var hs = (init && init.headers) || (req && req.headers);
      if (hs) {
        var pairs = hs instanceof Headers ? Array.from(hs.entries()) : Array.isArray(hs) ? hs : Object.entries(hs);
        pairs.forEach(function (kv) { headers[String(kv[0]).toLowerCase()] = String(kv[1]); });
      }
      Object.values(headers).forEach(function (v) { A.scanTokens(v, 'header'); });
      A.scanTokens(url, 'url');
      e = { kind: 'http', via: 'fetch', method: method, url: url, headers: headers, body: null };
      if (init && typeof init.body === 'string') e.body = init.body;
      A.scanTokens(e.body, 'body');
      A.rememberOut(e);
    } catch (err) {}
    return nativeFetch.apply(this, arguments);
  };

  // ---------- XHR ----------
  var XO = XMLHttpRequest.prototype;
  var xOpen = XO.open, xSet = XO.setRequestHeader, xSend = XO.send;
  XO.open = function (m, u) {
    try { this.__c = { kind: 'http', via: 'xhr', method: String(m).toUpperCase(), url: A.abs(u), headers: {}, body: null }; } catch (e) {}
    return xOpen.apply(this, arguments);
  };
  XO.setRequestHeader = function (k, v) {
    try { if (this.__c) { this.__c.headers[String(k).toLowerCase()] = String(v); A.scanTokens(String(v), 'header'); } } catch (e) {}
    return xSet.apply(this, arguments);
  };
  XO.send = function (body) {
    try {
      var c = this.__c;
      if (c) { c.body = typeof body === 'string' ? body : null; A.scanTokens(c.url, 'url'); A.scanTokens(c.body, 'body'); A.rememberOut(c); }
    } catch (e) {}
    return xSend.apply(this, arguments);
  };

  (function scanStorage() {
    try {
      [localStorage, sessionStorage].forEach(function (st) {
        for (var i = 0; i < st.length; i++) { var k = st.key(i); A.scanTokens(st.getItem(k), 'storage:' + k); }
      });
      A.scanTokens(document.cookie, 'cookie');
    } catch (e) {}
  })();

  // =====================================================================
  //                         СПАМ (page/spam.js, слито)
  // =====================================================================
  var SEP = '\u0001MSG\u0001';
  var ENC = {
    raw: function (s) { return s; },
    json: function (s) { return JSON.stringify(s).slice(1, -1); },
    uri: function (s) { return encodeURIComponent(s); },
    form: function (s) { return encodeURIComponent(s).replace(/%20/g, '+'); },
  };
  var ERR_RE = /(error|forbidden|denied|muted?|banned?|cooldown|too (many|fast)|rate.?limit|ошибк|запрещ|нельзя|слишком|мут\b|бан\b|подожд)/i;

  function looksStopped(text) {
    if (!text || text.length > 3000) return false;
    try {
      var o = JSON.parse(text);
      var d = o && o.data;
      return !!(d && (d.stopped === true || d.failed === true));
    } catch (e) { return false; }
  }
  A.looksStopped = looksStopped;

  var pending = null, tpl = null, seq = 9000000;
  var running = false, stopRe = null, lastSendAt = 0, errSeen = 0, lastStopAt = 0;

  function learnFrom(e) {
    if (!pending || pending.text.length < 3) return false;
    if (e.t < pending.t - 1000 || Date.now() - pending.t > 5000) return false;
    var parts = e.kind === 'ws' ? { data: e.data } : { url: e.url, body: e.body || '' };
    var enc = null, needle = '';
    for (var name in ENC) {
      var n = ENC[name](pending.text);
      var found = Object.keys(parts).some(function (k) { return typeof parts[k] === 'string' && parts[k].indexOf(n) !== -1; });
      if (n && found) { enc = name; needle = n; break; }
    }
    if (!enc) return false;
    var out = {};
    Object.keys(parts).forEach(function (k) { out[k] = typeof parts[k] === 'string' ? parts[k].split(needle).join(SEP) : parts[k]; });
    var jwts = Array.from(new Set((JSON.stringify([out, e.headers || {}]).match(JWT_RE) || [])));
    tpl = Object.assign({ kind: e.kind, via: e.via, enc: enc, method: e.method, headers: e.headers || {}, jwts: jwts, ws: e.ws, at: Date.now() }, out);
    pending = null;
    emit('learned', { kind: e.kind, hasJwt: jwts.length > 0 });
    if (jwts[0]) {
      var pl = decodeJwt(jwts[0]) || {};
      emit('jwt', { token: jwts[0], exp: typeof pl.exp === 'number' ? pl.exp * 1000 : 0, src: 'chat' });
    }
    return true;
  }
  A.outHooks.push(function (e) { learnFrom(e); });

  function onSubmitted(text) {
    pending = { text: String(text || ''), t: Date.now() };
    var arr = A.recent.slice().reverse();
    for (var i = 0; i < arr.length; i++) if (learnFrom(arr[i])) break;
  }

  // ---------- ловим, когда вы сами пишете в чат (как content/chat.js) ----------
  function isField(el) { return el instanceof Element && (el.tagName === 'TEXTAREA' || el.isContentEditable || (el.tagName === 'INPUT' && /^(text|search)?$/i.test(el.getAttribute('type') || ''))); }
  function valueOf(el) { return String(el.value !== undefined ? el.value : el.innerText || el.textContent || '').trim(); }
  function submittedEl(el) { var text = valueOf(el); if (text) onSubmitted(text); }
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing && isField(e.target)) submittedEl(e.target);
  }, true);
  document.addEventListener('click', function (e) {
    var btn = e.target instanceof Element && e.target.closest('button, [role="button"], [type="submit"]');
    if (!btn) return;
    var scope = btn.closest('form') || (btn.parentElement && btn.parentElement.parentElement) || btn.parentElement;
    var inp = scope && Array.from(scope.querySelectorAll('textarea, input, [contenteditable]')).find(function (x) { return isField(x) && valueOf(x); });
    if (inp) submittedEl(inp);
  }, true);

  // ---------- статус / конфиг / отправка (вызываются из Kotlin через evaluateJavascript) ----------
  window.__cs2aStatus = function () {
    return JSON.stringify({ hasTemplate: !!tpl, kind: tpl ? (tpl.kind === 'ws' ? 'ws' : 'http') : null });
  };
  window.__cs2aConfig = function (isRunning, stopReStr) {
    running = !!isRunning; errSeen = 0; stopRe = null;
    if (running && stopReStr) { try { stopRe = new RegExp(stopReStr, 'i'); } catch (e) { stopRe = null; } }
  };
  window.__cs2aReset = function () { tpl = null; pending = null; };
  // Шаблон учится один раз (обычно в видимом окне входа) и переносится в скрытый WebView службы
  // как обычный JSON — «живой» WebSocket всё равно переоткрывается заново при каждой загрузке
  // страницы, pickSocket() сам находит актуальный сокет в своей сессии (см. ниже).
  window.__cs2aExportTpl = function () {
    if (!tpl) return '';
    var copy = {}; for (var k in tpl) if (k !== 'ws') copy[k] = tpl[k];
    return JSON.stringify(copy);
  };
  window.__cs2aImportTpl = function (json) {
    try { var t = JSON.parse(json); if (t && t.enc) tpl = t; } catch (e) {}
  };

  var pickSocket = function () {
    var open = Array.from(A.sockets).filter(function (s) { return s.readyState === 1; });
    if (tpl.ws && open.indexOf(tpl.ws) !== -1) return tpl.ws;
    var base = function (u) { return String(u || '').split('?')[0]; };
    var same = open.slice().reverse().find(function (s) { return base(s.__cs2aUrl) === base(tpl.ws && tpl.ws.__cs2aUrl); });
    return same || open[0] || null;
  };
  var bumpIds = function (frame) {
    var m = frame.replace(/^(4[25](?:\/[^,]*,)?)(\d+)(?=\[)/, function (_, a) { return a + (++seq); });
    if (m !== frame) return m;
    if (frame[0] === '{') {
      try { var o = JSON.parse(frame); if (o && typeof o.id === 'number') { o.id = ++seq; return JSON.stringify(o); } } catch (e) {}
    }
    return frame;
  };

  window.__cs2aSend = function (text, token, reqId) {
    (async function () {
      var result;
      try {
        if (!tpl) throw new Error('формат отправки ещё не записан');
        var esc = ENC[tpl.enc](String(text || ''));
        var fill = function (s) {
          var r = String(s).split(SEP).join(esc);
          var tp = tpl.jwts.length === 1 && decodeJwt(tpl.jwts[0]);
          if (token && tp && tp.id != null && typeof tp.exp === 'number' && tpl.jwts[0] !== token) r = r.split(tpl.jwts[0]).join(token);
          return r;
        };
        lastSendAt = Date.now();
        if (tpl.kind === 'ws') {
          var ws = pickSocket();
          if (!ws) throw new Error('нет открытого WebSocket — перезагрузите страницу');
          A.nativeSend(ws, bumpIds(fill(tpl.data)));
          result = { ok: true, via: 'ws' };
        } else {
          var headers = {};
          Object.keys(tpl.headers).forEach(function (k) { headers[k] = fill(tpl.headers[k]); });
          var hasBody = tpl.method !== 'GET' && tpl.method !== 'HEAD' && tpl.body;
          var res = await A.nativeFetch(fill(tpl.url), { method: tpl.method, headers: headers, body: hasBody ? fill(tpl.body) : undefined, credentials: 'include' });
          var txt = await res.text().catch(function () { return ''; });
          if (running && (looksStopped(txt) || (stopRe && stopRe.test(txt))) && Date.now() - lastStopAt > 2000) {
            lastStopAt = Date.now();
            emit('stop-signal', { src: 'http' });
          }
          result = { ok: res.ok, via: 'http', status: res.status };
        }
      } catch (e) {
        result = { ok: false, error: String((e && e.message) || e) };
      }
      try { if (window.AndroidBridge) window.AndroidBridge.resolve(reqId, JSON.stringify(result)); } catch (e2) {}
    })();
  };

  // ---------- входящие кадры WS: сигнал остановки чата ----------
  A.inHooks.push(function (src, data) {
    if (!running || data.length > 3000) return;
    if ((looksStopped(data) || (stopRe && stopRe.test(data))) && Date.now() - lastStopAt > 2000) {
      lastStopAt = Date.now();
      emit('stop-signal', { src: src });
      return;
    }
    if (Date.now() - lastSendAt < 1200 && data.length < 300 && ERR_RE.test(data)) {
      errSeen++;
      emit('warn', { snippet: A.redact(data, 140) });
    }
  });
})();
