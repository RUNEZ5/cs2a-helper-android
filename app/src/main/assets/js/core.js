// CS2A Помощник — page/core.js
// Работает в контексте страницы (MAIN world) ДО скриптов сайта: перехватывает WebSocket / fetch / XHR,
// ищет JWT и ведёт журнал запросов для диагностики. Токен уходит только в хранилище самого расширения.
(() => {
  'use strict';
  if (window.__cs2a) return;
  const CH = '__cs2a_ext';
  const JWT_RE = /eyJ[\w-]{6,}\.eyJ[\w-]{6,}\.[\w-]{6,}/g;
  const A = (window.__cs2a = {
    version: '1.0.0', CH, JWT_RE,
    sockets: new Set(), recent: [], net: [], seen: new Map(), lastIn: [],
    tokens: new Set(), token: null, handlers: {}, outHooks: [], inHooks: [],
  });

  // ---------- связь со скриптом расширения (content/bridge.js) ----------
  A.post = (type, data, id) => {
    try { window.postMessage({ ch: CH, from: 'page', type, data, id }, location.origin); } catch (e) { /* ignore */ }
  };
  A.on = (type, fn) => { A.handlers[type] = fn; };
  window.addEventListener('message', (ev) => {
    const d = ev.data;
    if (ev.source !== window || !d || d.ch !== CH || d.from !== 'ext') return;
    const fn = A.handlers[d.type];
    if (!fn) return;
    Promise.resolve().then(() => fn(d.data)).then(
      (res) => { if (d.id) A.post('reply', { ok: true, res }, d.id); },
      (err) => { if (d.id) A.post('reply', { ok: false, err: String((err && err.message) || err) }, d.id); }
    );
  });

  // ---------- JWT ----------
  const decodeJwt = (t) => {
    try {
      let p = t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
      p += '='.repeat((4 - (p.length % 4)) % 4);
      const bin = atob(p);
      return JSON.parse(new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0))));
    } catch (e) { return null; }
  };
  A.decodeJwt = decodeJwt;
  // Токен API (onemails.net) — {id, iat, exp}. Токен WebSocket (Centrifugo) без exp и обновляется
  // сайтом примерно раз в час — его в расширение больше не отправляем (он затирал токен API).
  A.apiToken = null;
  const isApi = (pl) => !!pl && pl.id != null && typeof pl.exp === 'number';
  A.noteToken = (value, src) => {
    if (A.tokens.has(value)) return;
    const pl = decodeJwt(value);
    if (!pl || typeof pl !== 'object') return;
    A.tokens.add(value);
    const exp = typeof pl.exp === 'number' ? pl.exp * 1000 : 0;
    A.token = { value, exp, src };
    if (!isApi(pl)) return;
    if (!A.apiToken || exp >= A.apiToken.exp) A.apiToken = { value, exp, src };
    A.post('jwt', { token: value, exp, src });
  };
  // по запросу расширения: перечитать хранилище сайта и отдать самый свежий токен API
  A.on('get-token', () => {
    try { A.rescan && A.rescan(); } catch (e) { /* ignore */ }
    const t = A.apiToken;
    return { token: t && t.exp > Date.now() ? t.value : null };
  });
  A.scanTokens = (s, src) => {
    if (typeof s !== 'string' || s.length < 30) return;
    const m = s.match(JWT_RE);
    if (m) for (const v of m) A.noteToken(v, src);
  };
  A.redact = (s, n = 160) => String(s).replace(JWT_RE, '<JWT>').slice(0, n);
  A.abs = (u) => { try { return new URL(u, location.href).href; } catch (e) { return String(u); } };

  // ---------- кошелёк и профиль (перехват ответов самого сайта) ----------
  A.wallet = null;   // { totalDeposits, totalWithdraws, userId, ... }
  A.profile = null;  // { id, name, registerAt, ... }
  A.routeResponse = (url, obj) => {
    if (!obj || typeof obj !== 'object') return;
    const u = String(url || '');
    if (!/onemails\.net/.test(u)) return;
    if (/\/current-state/.test(u)) {
      let w = obj.data && typeof obj.data === 'object' ? obj.data : obj;
      if (w && w.wallet && typeof w.wallet === 'object') w = w.wallet;
      if (w && (typeof w.totalDeposits !== 'undefined' || typeof w.totalWithdraws !== 'undefined')) {
        A.wallet = w;
        A.post('wallet', w);
      }
    } else if (/\/profile\//.test(u)) {
      const p = obj.data && typeof obj.data === 'object' ? obj.data : obj;
      if (p && typeof p.registerAt !== 'undefined') {
        A.profile = p;
        A.post('profile', p);
      }
    }
  };

  // ---------- журналы ----------
  A.logNet = (k, m, u, extra) => {
    A.net.push(Object.assign({ t: Date.now(), k, m, u: A.redact(u, 200) }, extra || {}));
    if (A.net.length > 200) A.net.shift();
  };
  A.rememberOut = (e) => {
    e.t = Date.now();
    A.recent.push(e);
    if (A.recent.length > 40) A.recent.shift();
    for (const fn of A.outHooks) { try { fn(e); } catch (err) { /* ignore */ } }
  };

  // ---------- WebSocket ----------
  const NativeWS = window.WebSocket;
  const nativeSend = NativeWS.prototype.send;
  A.nativeSend = (ws, data) => nativeSend.call(ws, data);

  A.onIncoming = (ws, data) => {
    if (typeof data !== 'string') return;
    const key = data.slice(0, 24);
    let c = A.seen.get(key);
    if (!c) {
      if (A.seen.size >= 80) { // вытесняем самую старую запись
        let oldest = null, ot = Infinity;
        for (const [k, v] of A.seen) if (v.t < ot) { ot = v.t; oldest = k; }
        A.seen.delete(oldest);
      }
      c = { n: 0, t: 0, s: A.redact(data, 220) };
      A.seen.set(key, c);
    }
    c.n++; c.t = Date.now();
    if (data.length < 400) { A.lastIn.push({ t: c.t, s: A.redact(data, 200) }); if (A.lastIn.length > 25) A.lastIn.shift(); }
    for (const fn of A.inHooks) { try { fn('ws', data, ws); } catch (err) { /* ignore */ } }
  };

  A.onOutgoing = (ws, data) => {
    if (typeof data !== 'string') { A.logNet('ws-out-bin', 'WS', ws.__cs2aUrl, { b: '<binary>' }); return; }
    A.scanTokens(data, 'ws-out');
    A.logNet('ws-out', 'WS', ws.__cs2aUrl, { b: A.redact(data, 160) });
    A.rememberOut({ kind: 'ws', via: 'ws', ws, data, url: ws.__cs2aUrl });
  };

  function track(ws, url) {
    ws.__cs2aUrl = A.abs(url);
    A.sockets.add(ws);
    A.scanTokens(ws.__cs2aUrl, 'ws-url');
    A.logNet('ws-open', 'WS', ws.__cs2aUrl);
    ws.addEventListener('message', (ev) => A.onIncoming(ws, ev.data));
    ws.addEventListener('close', () => { A.sockets.delete(ws); A.logNet('ws-close', 'WS', ws.__cs2aUrl); });
  }
  window.WebSocket = new Proxy(NativeWS, {
    construct(target, args, newTarget) {
      const ws = Reflect.construct(target, args, newTarget);
      try { track(ws, args[0]); } catch (e) { /* ignore */ }
      return ws;
    },
  });
  NativeWS.prototype.send = function (data) {
    try { A.onOutgoing(this, data); } catch (e) { /* ignore */ }
    return nativeSend.apply(this, arguments);
  };

  // ---------- fetch ----------
  const nativeFetch = window.fetch;
  A.nativeFetch = nativeFetch.bind(window);
  window.fetch = function (input, init) {
    let e = null;
    try {
      const req = typeof Request !== 'undefined' && input instanceof Request ? input : null;
      const url = A.abs(req ? req.url : input instanceof URL ? input.href : input);
      const method = String((init && init.method) || (req && req.method) || 'GET').toUpperCase();
      const headers = {};
      const hs = (init && init.headers) || (req && req.headers);
      if (hs) {
        const pairs = hs instanceof Headers ? [...hs.entries()] : Array.isArray(hs) ? hs : Object.entries(hs);
        for (const [k, v] of pairs) headers[String(k).toLowerCase()] = String(v);
      }
      Object.values(headers).forEach((v) => A.scanTokens(v, 'header'));
      A.scanTokens(url, 'url');
      e = { kind: 'http', via: 'fetch', method, url, headers, body: null };
      if (init && typeof init.body === 'string') e.body = init.body;
      A.scanTokens(e.body, 'body');
      A.logNet('fetch', method, url, { b: e.body && A.redact(e.body, 120) });
      if (req && e.body === null && method !== 'GET' && method !== 'HEAD') {
        const ee = e;
        req.clone().text().then((b) => { ee.body = b; A.scanTokens(b, 'body'); A.rememberOut(ee); }, () => {});
      } else A.rememberOut(e);
    } catch (err) { /* ignore */ }
    const p = nativeFetch.apply(this, arguments);
    if (e) p.then((r) => {
      A.logNet('fetch-res', e.method, e.url, { s: r.status });
      try { r.clone().json().then((o) => A.routeResponse(e.url, o)).catch(() => {}); } catch (err) { /* ignore */ }
    }, () => {});
    return p;
  };

  // ---------- XMLHttpRequest ----------
  const XO = XMLHttpRequest.prototype;
  const xOpen = XO.open, xSet = XO.setRequestHeader, xSend = XO.send;
  XO.open = function (m, u) {
    try { this.__c = { kind: 'http', via: 'xhr', method: String(m).toUpperCase(), url: A.abs(u), headers: {}, body: null }; } catch (e) { /* ignore */ }
    return xOpen.apply(this, arguments);
  };
  XO.setRequestHeader = function (k, v) {
    try { if (this.__c) { this.__c.headers[String(k).toLowerCase()] = String(v); A.scanTokens(String(v), 'header'); } } catch (e) { /* ignore */ }
    return xSet.apply(this, arguments);
  };
  XO.send = function (body) {
    try {
      const c = this.__c;
      if (c) {
        c.body = typeof body === 'string' ? body : null;
        A.scanTokens(c.url, 'url'); A.scanTokens(c.body, 'body');
        A.logNet('xhr', c.method, c.url, { b: c.body && A.redact(c.body, 120) });
        A.rememberOut(c);
        this.addEventListener('loadend', () => {
          A.logNet('xhr-res', c.method, c.url, { s: this.status });
          try { A.routeResponse(c.url, JSON.parse(this.responseText)); } catch (err) { /* ignore */ }
        });
      }
    } catch (e) { /* ignore */ }
    return xSend.apply(this, arguments);
  };

  // ---------- токен, уже лежащий в хранилище сайта ----------
  const scanStorage = () => {
    try {
      for (const st of [localStorage, sessionStorage]) {
        for (let i = 0; i < st.length; i++) { const k = st.key(i); A.scanTokens(st.getItem(k), 'storage:' + k); }
      }
      A.scanTokens(document.cookie, 'cookie');
    } catch (e) { /* хранилище может быть недоступно */ }
  };
  A.rescan = () => { A.tokens.clear(); scanStorage(); };
  scanStorage();
  window.addEventListener('load', () => setTimeout(scanStorage, 1500));
})();
