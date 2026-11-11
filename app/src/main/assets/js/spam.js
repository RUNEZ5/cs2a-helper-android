// CS2A Помощник — page/spam.js (MAIN world)
// 1) Запоминает формат отправки сообщения в чат: когда вы сами пишете в чат, находит исходящий кадр WebSocket
//    (или HTTP-запрос), в котором есть ваш текст, и превращает его в шаблон.
// 2) По команде расширения шлёт такой же кадр/запрос с вашим текстом и текущим JWT.
// 3) Следит за ответами сайта и сообщает, если чат остановлен.
(() => {
  'use strict';
  const A = window.__cs2a;
  if (!A || A.spamReady) return;
  A.spamReady = true;

  const SEP = '\u0001MSG\u0001';
  const ENC = {
    raw: (s) => s,
    json: (s) => JSON.stringify(s).slice(1, -1),
    uri: (s) => encodeURIComponent(s),
    form: (s) => encodeURIComponent(s).replace(/%20/g, '+'),
  };
  const ERR_RE = /(error|forbidden|denied|muted?|banned?|cooldown|too (many|fast)|rate.?limit|ошибк|запрещ|нельзя|слишком|мут\b|бан\b|подожд)/i;

  // Реальный сигнал остановки чата (подтверждено ответом сайта на POST /chat/ru):
  // {success:true, data:{failed:true, stopped:true}}. Проверяем структурно — это надёжнее
  // regex по тексту (stopRe), который пользователь может не настроить/не угадать.
  function looksStopped(text) {
    if (!text || text.length > 3000) return false;
    try {
      const o = JSON.parse(text);
      const d = o && o.data;
      return !!(d && (d.stopped === true || d.failed === true));
    } catch (e) { return false; }
  }

  let pending = null;      // { text, t } — то, что вы только что отправили в чат
  let tpl = null;          // выученный шаблон отправки
  let seq = 9000000;       // свои счётчики для id / ack-id, чтобы не пересекаться с сайтом
  let running = false, stopRe = null, lastSendAt = 0, errSeen = 0, lastStopAt = 0;

  // ---------- обучение формату ----------
  function learnFrom(e) {
    if (!pending || pending.text.length < 3) return false;
    if (e.t < pending.t - 1000 || Date.now() - pending.t > 5000) return false;
    const parts = e.kind === 'ws' ? { data: e.data } : { url: e.url, body: e.body || '' };
    let enc = null, needle = '';
    for (const name of Object.keys(ENC)) {
      const n = ENC[name](pending.text);
      if (n && Object.values(parts).some((v) => typeof v === 'string' && v.includes(n))) { enc = name; needle = n; break; }
    }
    if (!enc) return false;
    const out = {};
    for (const k of Object.keys(parts)) out[k] = typeof parts[k] === 'string' ? parts[k].split(needle).join(SEP) : parts[k];
    const jwts = [...new Set(JSON.stringify([out, e.headers || {}]).match(A.JWT_RE) || [])];
    tpl = Object.assign({ kind: e.kind, via: e.via, enc, method: e.method, headers: e.headers || {}, jwts, ws: e.ws, at: Date.now() }, out);
    pending = null;
    const preview = e.kind === 'ws' ? out.data : `${e.method} ${out.url}  ${out.body || ''}`;
    A.post('learned', { kind: e.kind, via: e.via, hasJwt: jwts.length > 0, preview: A.redact(preview.split(SEP).join('{текст}'), 160) });
    if (jwts[0]) {
      const pl = A.decodeJwt(jwts[0]) || {};
      A.post('jwt', { token: jwts[0], exp: typeof pl.exp === 'number' ? pl.exp * 1000 : 0, src: 'chat' });
    }
    return true;
  }
  A.outHooks.push((e) => { learnFrom(e); });

  A.on('chat-submit', (d) => {
    pending = { text: String((d && d.text) || ''), t: Date.now() };
    for (const e of [...A.recent].reverse()) if (learnFrom(e)) break;
  });
  A.on('reset-template', () => { tpl = null; pending = null; return true; });

  A.on('status', () => ({
    hasTemplate: !!tpl,
    kind: tpl && (tpl.kind === 'ws' ? 'WebSocket' : 'HTTP'),
    hasJwt: !!(tpl && tpl.jwts.length),
    sockets: [...A.sockets].filter((s) => s.readyState === 1).length,
  }));

  A.on('spam-config', (d) => {
    running = !!(d && d.running);
    errSeen = 0;
    stopRe = null;
    if (running && d.stopRe) { try { stopRe = new RegExp(d.stopRe, 'i'); } catch (e) { stopRe = null; } }
    return { running, stopRe: !!stopRe };
  });

  // ---------- отправка ----------
  const pickSocket = () => {
    const open = [...A.sockets].filter((s) => s.readyState === 1);
    if (tpl.ws && open.includes(tpl.ws)) return tpl.ws;
    const base = (u) => String(u || '').split('?')[0];
    return open.reverse().find((s) => base(s.__cs2aUrl) === base(tpl.ws && tpl.ws.__cs2aUrl)) || open[0] || null;
  };
  const bumpIds = (frame) => {
    // socket.io: 42<ackId>["event", ...] — делаем новый ack-id
    const m = frame.replace(/^(4[25](?:\/[^,]*,)?)(\d+)(?=\[)/, (_, a) => a + (++seq));
    if (m !== frame) return m;
    if (frame[0] === '{') {
      try { const o = JSON.parse(frame); if (o && typeof o.id === 'number') { o.id = ++seq; return JSON.stringify(o); } } catch (e) { /* не JSON */ }
    }
    return frame;
  };

  A.on('send', async (d) => {
    if (!tpl) throw new Error('формат отправки ещё не записан');
    const text = String((d && d.text) || '');
    const token = d && d.token;
    const esc = ENC[tpl.enc](text);
    const fill = (s) => {
      let r = String(s).split(SEP).join(esc);
      const tp = tpl.jwts.length === 1 && A.decodeJwt(tpl.jwts[0]);
      if (token && tp && tp.id != null && typeof tp.exp === 'number' && tpl.jwts[0] !== token) r = r.split(tpl.jwts[0]).join(token);
      return r;
    };
    const errs = errSeen; errSeen = 0;
    lastSendAt = Date.now();
    if (tpl.kind === 'ws') {
      const ws = pickSocket();
      if (!ws) throw new Error('нет открытого WebSocket — обновите страницу сайта');
      A.nativeSend(ws, bumpIds(fill(tpl.data)));
      return { ok: true, via: 'ws', errSeen: errs };
    }
    const headers = {};
    for (const k of Object.keys(tpl.headers)) headers[k] = fill(tpl.headers[k]);
    const hasBody = tpl.method !== 'GET' && tpl.method !== 'HEAD' && tpl.body;
    const res = await A.nativeFetch(fill(tpl.url), { method: tpl.method, headers, body: hasBody ? fill(tpl.body) : undefined, credentials: 'include' });
    const txt = await res.text().catch(() => '');
    A.logNet('spam-res', tpl.method, tpl.url, { s: res.status, b: A.redact(txt, 120) });
    if (running && (looksStopped(txt) || (stopRe && stopRe.test(txt))) && Date.now() - lastStopAt > 2000) {
      lastStopAt = Date.now();
      A.post('stop-signal', { src: 'http', snippet: A.redact(txt, 180) });
    }
    return { ok: res.ok, via: 'http', status: res.status, body: A.redact(txt, 160), errSeen: errs };
  });

  // ---------- ответы сайта: сигнал остановки чата и ошибки ----------
  A.inHooks.push((src, data) => {
    if (!running || data.length > 3000) return;
    if ((looksStopped(data) || (stopRe && stopRe.test(data))) && Date.now() - lastStopAt > 2000) {
      lastStopAt = Date.now();
      A.post('stop-signal', { src, snippet: A.redact(data, 180) });
      return;
    }
    if (Date.now() - lastSendAt < 1200 && data.length < 300 && ERR_RE.test(data)) {
      errSeen++;
      A.post('warn', { snippet: A.redact(data, 140) });
    }
  });

  // ---------- диагностика (токен и cookie в отчёт не попадают) ----------
  A.on('diag', () => ({
    version: A.version, url: location.href, at: new Date().toISOString(),
    template: tpl && { kind: tpl.kind, via: tpl.via, enc: tpl.enc, method: tpl.method, hasJwt: tpl.jwts.length > 0,
      shape: A.redact((tpl.data || `${tpl.url} ${tpl.body || ''}`).split(SEP).join('{текст}'), 300) },
    token: A.token && { src: A.token.src, exp: A.token.exp ? new Date(A.token.exp).toISOString() : null },
    sockets: [...A.sockets].map((s) => ({ url: A.redact(s.__cs2aUrl, 200), state: s.readyState })),
    out: A.net.slice(-60),
    inboundTypes: [...A.seen.values()].sort((a, b) => b.t - a.t).slice(0, 40).map((v) => ({ n: v.n, ago: Date.now() - v.t, s: v.s })),
    lastInbound: A.lastIn.slice(-15),
  }));
})();
