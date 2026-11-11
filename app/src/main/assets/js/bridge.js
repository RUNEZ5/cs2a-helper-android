// CS2A Помощник (Android) — bridge.js
// Работает в ТОМ ЖЕ JS-контексте страницы, что и core.js/spam.js (в WebView нет разделения
// MAIN/ISOLATED миров, как в Chrome-расширении) — поэтому напрямую общается с window.__cs2a
// через тот же канал window.postMessage(), что использовали content/bridge.js + content/chat.js
// в расширении, и передаёт события в Kotlin через AndroidBridge (addJavascriptInterface).
(() => {
  if (window.__cs2aAndroidBridge) return;
  window.__cs2aAndroidBridge = true;
  const CH = '__cs2a_ext';

  // ---------- страница -> Kotlin ----------
  window.addEventListener('message', (ev) => {
    const d = ev.data;
    if (ev.source !== window || !d || d.ch !== CH || d.from !== 'page') return;
    try { AndroidBridge.onPageEvent(JSON.stringify(d)); } catch (e) { /* интерфейс ещё не готов */ }
  });

  // ---------- Kotlin -> страница (вызывается через evaluateJavascript) ----------
  window.__cs2aToPage = (type, dataJson, id) => {
    try {
      const data = dataJson ? JSON.parse(dataJson) : null;
      window.postMessage({ ch: CH, from: 'ext', type, data, id: id || 0 }, location.origin);
    } catch (e) { /* ignore */ }
  };

  // ---------- определяем, когда ВЫ САМИ отправили сообщение в чат вручную ----------
  // (нужно один раз, чтобы spam.js «выучил» формат отправки — см. page/spam.js learnFrom)
  const isField = (el) => el instanceof Element && (el.tagName === 'TEXTAREA' || el.isContentEditable ||
    (el.tagName === 'INPUT' && /^(text|search)?$/i.test(el.getAttribute('type') || '')));
  const valueOf = (el) => String(el.value !== undefined ? el.value : el.innerText || el.textContent || '').trim();

  function submitted(el) {
    const text = valueOf(el);
    if (!text) return;
    window.postMessage({ ch: CH, from: 'ext', type: 'chat-submit', data: { text }, id: 0 }, location.origin);
  }
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing && isField(e.target)) submitted(e.target);
  }, true);
  document.addEventListener('click', (e) => {
    const btn = e.target instanceof Element && e.target.closest('button, [role="button"], [type="submit"]');
    if (!btn) return;
    const scope = btn.closest('form') || (btn.parentElement && btn.parentElement.parentElement) || btn.parentElement;
    const inp = scope && [...scope.querySelectorAll('textarea, input, [contenteditable]')].find((x) => isField(x) && valueOf(x));
    if (inp) submitted(inp);
  }, true);

  try { AndroidBridge.onReady(); } catch (e) { /* ignore */ }
})();
