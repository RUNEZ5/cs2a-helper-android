const ADMIN_HTML = String.raw`<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>CS2A — управление лицензиями</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#111827;color:#e5e7eb;font:15px system-ui,Segoe UI,Arial}main{max-width:1100px;margin:36px auto;padding:0 18px}h1{font-size:25px;color:#7effed}h2{font-size:18px;margin:0 0 14px}.card{background:#1f2937;border:1px solid #374151;border-radius:14px;padding:18px;margin:16px 0}.row{display:flex;gap:10px;flex-wrap:wrap;align-items:center}input,button{font:inherit;border-radius:8px;padding:11px 13px;border:1px solid #4b5563}input{background:#111827;color:#fff;min-width:220px;flex:1}button{background:#7effed;color:#111827;border:0;font-weight:700;cursor:pointer}button.danger{background:#fb7185}button.secondary{background:#374151;color:#fff;border:1px solid #4b5563}button:disabled{opacity:.5;cursor:wait}.muted{color:#9ca3af}.hidden{display:none}.key{font-size:18px;letter-spacing:1px;word-break:break-word;color:#7effed;margin:10px 0}.scroll{overflow:auto}table{width:100%;border-collapse:collapse;min-width:760px}th,td{text-align:left;padding:10px;border-bottom:1px solid #374151;vertical-align:top;font-size:13px}th{color:#9ca3af}td button{padding:6px 9px;margin:2px;font-size:12px}.status{margin-top:10px;color:#fbbf24;min-height:20px}.pill{display:inline-block;padding:3px 7px;border-radius:20px;background:#374151;margin:2px}
</style></head><body><main>
<h1>CS2A — управление лицензиями</h1>
<p class="muted">Секрет администратора используется только в текущей вкладке и не сохраняется.</p>
<section class="card" id="login"><h2>Вход администратора</h2><div class="row"><input id="secret" type="password" autocomplete="off" placeholder="ADMIN_SECRET"><button id="loginBtn">Войти</button></div><div class="status" id="loginStatus"></div></section>
<div id="panel" class="hidden">
<section class="card"><h2>Создать лицензионный ключ</h2><div class="row"><input id="days" type="number" min="1" max="36500" placeholder="Срок в днях (пусто — бессрочная)"><button id="createBtn">Сгенерировать ключ</button></div><div id="newKey"></div><div class="status" id="createStatus"></div></section>
<section class="card"><div class="row"><h2 style="flex:1">Ключи и активации</h2><button class="secondary" id="refreshBtn">Обновить</button></div><div class="scroll"><table><thead><tr><th>ID</th><th>Ключ</th><th>Статус</th><th>Срок</th><th>Устройство</th><th>Аккаунт CSGORun</th><th>Активация / окончание</th><th>Действия</th></tr></thead><tbody id="keys"></tbody></table></div></section>
<section class="card"><h2>Журнал событий</h2><div class="scroll"><table><thead><tr><th>Дата</th><th>ID ключа</th><th>Событие</th><th>Подробности</th></tr></thead><tbody id="events"></tbody></table></div></section>
</div></main>
<script>
let adminSecret="";
const $=id=>document.getElementById(id);
const esc=v=>String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const date=v=>v?new Date(Number(v)*1000).toLocaleString("ru-RU"):"—";
async function api(path,options={}){const headers=Object.assign({"content-type":"application/json","authorization":"Bearer "+adminSecret},options.headers||{});const res=await fetch(path,Object.assign({},options,{headers}));const data=await res.json().catch(()=>({}));if(!res.ok)throw new Error(data.error||("HTTP "+res.status));return data}
async function loadAll(){
 const pair=await Promise.all([api("/admin/keys"),api("/admin/events")]);const k=pair[0],e=pair[1];
 $("keys").innerHTML=(k.keys||[]).map(x=>"<tr><td>"+esc(x.id.slice(0,10))+"…</td><td>"+(x.key?"<span class='key'>"+esc(x.key)+"</span><br><button class='secondary' data-copy-key='"+esc(x.key)+"'>Копировать</button>":"<span class='muted'>Недоступен (создан до обновления)</span>")+"</td><td><span class='pill'>"+esc(x.status)+"</span><span class='pill'>"+esc(x.activation_state)+"</span></td><td>"+(x.duration_days==null?"Бессрочная":esc(x.duration_days+" дн."))+"</td><td>"+esc(x.device_label||"Не активирован")+"</td><td>"+(x.user_id?"<b>"+esc(x.username||"Имя не определено")+"</b><br><span class=\'muted\'>ID: "+esc(x.user_id)+"</span><br><button class=\'"+(x.banned_at?"secondary":"danger")+"\' data-user-id=\'"+esc(x.user_id)+"\' data-user-action=\'"+(x.banned_at?"unban":"ban")+"\'>"+(x.banned_at?"Разблокировать":"Заблокировать пользователя")+"</button>":"<span class=\'muted\'>Не привязан</span>")+"</td><td>"+date(x.activated_at)+"<br>до: "+date(x.expires_at)+"</td><td><button class='secondary' data-id='"+esc(x.id)+"' data-action='deactivate'>Отключить</button><button class='danger' data-id='"+esc(x.id)+"' data-action='block'>Блокировать</button><button class='secondary' data-id='"+esc(x.id)+"' data-action='reset'>Сбросить привязку</button></td></tr>").join("")||"<tr><td colspan='6'>Ключей пока нет</td></tr>";
 $("events").innerHTML=(e.events||[]).map(x=>"<tr><td>"+date(x.created_at)+"</td><td>"+esc((x.key_id||"").slice(0,10))+"…</td><td>"+esc(x.action)+"</td><td>"+esc(x.detail)+"</td></tr>").join("")||"<tr><td colspan='4'>Событий пока нет</td></tr>";
}
$("loginBtn").onclick=async()=>{adminSecret=$("secret").value.trim();if(!adminSecret){$("loginStatus").textContent="Введите секрет.";return}try{await loadAll();$("login").classList.add("hidden");$("panel").classList.remove("hidden")}catch(e){adminSecret="";$("loginStatus").textContent="Ошибка входа: "+e.message}};
$("refreshBtn").onclick=()=>loadAll().catch(e=>alert(e.message));
$("createBtn").onclick=async()=>{try{const raw=$("days").value.trim();const days=raw===""?null:Number(raw);if(days!==null&&(!Number.isInteger(days)||days<1||days>36500))throw new Error("Срок должен быть от 1 до 36500 дней.");const r=await api("/admin/keys",{method:"POST",body:JSON.stringify({durationDays:days})});$("newKey").innerHTML="<div class='key' id='plainKey'></div><button class='secondary' id='copyKey'>Копировать ключ</button><p class='muted'>Ключ сохранён в зашифрованном виде и доступен в таблице после повторного входа.</p>";$("plainKey").textContent=r.key;$("copyKey").onclick=()=>navigator.clipboard.writeText(r.key).then(()=>$("createStatus").textContent="Ключ скопирован").catch(()=>$("createStatus").textContent="Выделите ключ и скопируйте вручную.");$("createStatus").textContent="Ключ создан.";await loadAll()}catch(e){$("createStatus").textContent="Ошибка: "+e.message}};
$("keys").onclick=async ev=>{const copy=ev.target.closest("button[data-copy-key]");if(copy){navigator.clipboard.writeText(copy.dataset.copyKey).then(()=>alert("Ключ скопирован")).catch(()=>alert("Не удалось скопировать ключ"));return}const ub=ev.target.closest("button[data-user-action]");if(ub){const action=ub.dataset.userAction;if(!confirm(action==="ban"?"Заблокировать этот аккаунт CSGORun во всех лицензиях?":"Снять блокировку аккаунта CSGORun?"))return;try{await api("/admin/users/"+encodeURIComponent(ub.dataset.userId)+"/"+action,{method:"POST",body:"{}"});await loadAll()}catch(e){alert(e.message)}return}const b=ev.target.closest("button[data-action]");if(!b)return;const names={deactivate:"Отключить возможность активации?",block:"Заблокировать лицензию и отозвать доступ?",reset:"Сбросить привязку устройства и разрешить повторную активацию?"};if(!confirm(names[b.dataset.action]))return;try{await api("/admin/keys/"+b.dataset.id+"/"+b.dataset.action,{method:"POST",body:"{}"});await loadAll()}catch(e){alert(e.message)}};
</script></body></html>`;

const json = (data, status = 200) => new Response(JSON.stringify(data), {
  status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }
});
const now = () => Math.floor(Date.now() / 1000);
async function ensureAccountTables(env) {
  await env.DB.batch([
    env.DB.prepare("CREATE TABLE IF NOT EXISTS license_account_links (activation_id TEXT PRIMARY KEY REFERENCES license_activations(id) ON DELETE CASCADE, user_id TEXT NOT NULL, username TEXT NOT NULL DEFAULT '', linked_at INTEGER NOT NULL)"),
    env.DB.prepare("CREATE INDEX IF NOT EXISTS idx_license_account_user ON license_account_links(user_id)"),
    env.DB.prepare("CREATE TABLE IF NOT EXISTS license_user_bans (user_id TEXT PRIMARY KEY, username TEXT NOT NULL DEFAULT '', banned_at INTEGER NOT NULL)")
  ]);
}
async function sha256(value) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map(x => x.toString(16).padStart(2, "0")).join("");
}
function randomHex(bytes = 32) {
  const data = crypto.getRandomValues(new Uint8Array(bytes));
  return [...data].map(x => x.toString(16).padStart(2, "0")).join("");
}
async function encryptLicenseKey(value, secret) {
  const raw = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(secret));
  const key = await crypto.subtle.importKey("raw", raw, { name: "AES-GCM" }, false, ["encrypt"]);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const encrypted = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, new TextEncoder().encode(value)));
  const hex = bytes => Array.from(bytes, x => x.toString(16).padStart(2, "0")).join("");
  return hex(iv) + ":" + hex(encrypted);
}
async function decryptLicenseKey(value, secret) {
  if (!value) return null;
  const parts = value.split(":");
  if (parts.length !== 2) return null;
  const bytes = hex => {
    const out = new Uint8Array(hex.length / 2);
    for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
    return out;
  };
  const raw = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(secret));
  const key = await crypto.subtle.importKey("raw", raw, { name: "AES-GCM" }, false, ["decrypt"]);
  const decrypted = await crypto.subtle.decrypt({ name: "AES-GCM", iv: bytes(parts[0]) }, key, bytes(parts[1]));
  return new TextDecoder().decode(decrypted);
}
function randomKey() {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const bytes = crypto.getRandomValues(new Uint8Array(20));
  const parts = [0, 1, 2, 3].map(i => [...bytes.slice(i * 5, i * 5 + 5)].map(n => alphabet[n % alphabet.length]).join(""));
  return "CS2A-" + parts.join("-");
}
function authorized(request, env) {
  const value = request.headers.get("authorization") || "";
  return value.startsWith("Bearer ") && value.slice(7) === env.ADMIN_SECRET && env.ADMIN_SECRET.length >= 32;
}
async function body(request) {
  try { return await request.json(); } catch { return null; }
}
async function audit(env, keyId, action, detail = "") {
  await env.DB.prepare("INSERT INTO license_events (id,key_id,action,detail,created_at) VALUES (?,?,?,?,?)")
    .bind(randomHex(12), keyId, action, detail.slice(0, 300), now()).run();
}
async function admin(request, env, url) {
  if (!authorized(request, env)) return json({ error: "unauthorized" }, 401);
  await ensureAccountTables(env);
  const userAction = url.pathname.match(/^\/admin\/users\/([A-Za-z0-9._:-]{1,128})\/(ban|unban)$/);
  if (request.method === "POST" && userAction) {
    const [, userId, action] = userAction;
    const linked = await env.DB.prepare("SELECT username FROM license_account_links WHERE user_id=? ORDER BY linked_at DESC LIMIT 1").bind(userId).first();
    if (!linked && action === "unban") return json({ error: "user_not_found" }, 404);
    if (action === "ban") {
      await env.DB.prepare("INSERT INTO license_user_bans (user_id,username,banned_at) VALUES (?,?,?) ON CONFLICT(user_id) DO UPDATE SET username=excluded.username,banned_at=excluded.banned_at").bind(userId, linked?.username || "", now()).run();
    } else { await env.DB.prepare("DELETE FROM license_user_bans WHERE user_id=?").bind(userId).run(); }
    const keyRow = await env.DB.prepare("SELECT a.key_id FROM license_account_links l JOIN license_activations a ON a.id=l.activation_id WHERE l.user_id=? LIMIT 1").bind(userId).first();
    await audit(env, keyRow?.key_id || null, action === "ban" ? "csgorun_user_banned" : "csgorun_user_unbanned", "user_id=" + userId);
    return json({ ok: true, action, userId });
  }
  if (request.method === "POST" && url.pathname === "/admin/keys") {
    const input = await body(request);
    const days = input?.durationDays;
    if (!(days === null || (Number.isInteger(days) && days >= 1 && days <= 36500))) return json({ error: "durationDays must be null or 1..36500" }, 400);
    const key = randomKey(), id = randomHex(16), created = now();
    const encryptedKey = await encryptLicenseKey(key, env.ADMIN_SECRET);
    await env.DB.prepare("INSERT INTO license_keys (id,key_hash,key_encrypted,activation_state,status,duration_days,created_at) VALUES (?,?,?, 'available','active',?,?)")
      .bind(id, await sha256(key), encryptedKey, days, created).run();
    await audit(env, id, "created", days === null ? "perpetual" : days + " days");
    return json({ id, key, durationDays: days, createdAt: created }, 201);
  }
  const match = url.pathname.match(/^\/admin\/keys\/([a-f0-9]+)\/(deactivate|block|reset)$/);
  if (request.method === "POST" && match) {
    const [, id, action] = match;
    const exists = await env.DB.prepare("SELECT id FROM license_keys WHERE id=?").bind(id).first();
    if (!exists) return json({ error: "not_found" }, 404);
    if (action === "deactivate") {
      await env.DB.prepare("UPDATE license_keys SET activation_state='deactivated' WHERE id=? AND status='active'").bind(id).run();
    } else if (action === "block") {
      await env.DB.prepare("UPDATE license_keys SET status='blocked' WHERE id=?").bind(id).run();
    } else {
      await env.DB.prepare("DELETE FROM license_activations WHERE key_id=?").bind(id).run();
      await env.DB.prepare("UPDATE license_keys SET activation_state='available' WHERE id=? AND status='active'").bind(id).run();
    }
    await audit(env, id, action);
    return json({ ok: true, action });
  }
  if (request.method === "GET" && url.pathname === "/admin/keys") {
    const result = await env.DB.prepare(`SELECT k.id,k.key_encrypted,k.activation_state,k.status,k.duration_days,k.created_at,
      a.device_label,a.activated_at,a.expires_at,l.user_id,l.username,b.banned_at
      FROM license_keys k LEFT JOIN license_activations a ON a.key_id=k.id
      LEFT JOIN license_account_links l ON l.activation_id=a.id LEFT JOIN license_user_bans b ON b.user_id=l.user_id
      ORDER BY k.created_at DESC LIMIT 500`).all();
    const keys = await Promise.all(result.results.map(async row => ({ ...row, key: await decryptLicenseKey(row.key_encrypted, env.ADMIN_SECRET) })));
    return json({ keys });
  }
  if (request.method === "GET" && url.pathname === "/admin/events") {
    const result = await env.DB.prepare("SELECT key_id,action,detail,created_at FROM license_events ORDER BY created_at DESC LIMIT 500").all();
    return json({ events: result.results });
  }
  return json({ error: "not_found" }, 404);
}
async function client(request, env, url) {
  await ensureAccountTables(env);
  const input = await body(request);
  if (!input || typeof input !== "object") return json({ error: "invalid_json" }, 400);
  const deviceId = typeof input.deviceId === "string" ? input.deviceId.trim() : "";
  if (deviceId.length < 16 || deviceId.length > 128) return json({ error: "invalid_device" }, 400);
  const deviceHash = await sha256(deviceId);
  if (request.method === "POST" && url.pathname === "/api/account/link") {
    const licenseToken = typeof input.licenseToken === "string" ? input.licenseToken : "";
    const userId = typeof input.userId === "string" ? input.userId.trim() : "";
    const username = typeof input.username === "string" ? input.username.trim().slice(0, 80) : "";
    if (licenseToken.length < 32 || licenseToken.length > 256) return json({ error: "invalid_license_token" }, 401);
    if (!userId || userId.length > 128) return json({ error: "invalid_user_id" }, 400);
    const activation = await env.DB.prepare("SELECT a.id,a.key_id,a.device_hash,a.expires_at,k.status FROM license_activations a JOIN license_keys k ON k.id=a.key_id WHERE a.token_hash=?").bind(await sha256(licenseToken)).first();
    if (!activation || activation.status !== "active" || activation.device_hash !== deviceHash || (activation.expires_at !== null && activation.expires_at <= now())) return json({ error: "license_invalid" }, 403);
    const result = await env.DB.prepare("INSERT INTO license_account_links (activation_id,user_id,username,linked_at) VALUES (?,?,?,?) ON CONFLICT(activation_id) DO UPDATE SET username=excluded.username,linked_at=excluded.linked_at WHERE license_account_links.user_id=excluded.user_id").bind(activation.id,userId,username,now()).run();
    if (result.meta.changes !== 1) return json({ error: "license_already_linked_to_another_account" }, 409);
    await audit(env, activation.key_id, "csgorun_account_linked", "user_id=" + userId + (username ? ", username=" + username : ""));
    const ban = await env.DB.prepare("SELECT user_id FROM license_user_bans WHERE user_id=?").bind(userId).first();
    if (ban) return json({ error: "user_banned" }, 403);
    return json({ ok: true, linked: true, userId, username });
  }
  if (request.method === "POST" && url.pathname === "/api/activate") {
    const key = typeof input.key === "string" ? input.key.trim().toUpperCase() : "";
    if (key.length < 12 || key.length > 64) return json({ error: "invalid_key" }, 400);
    const row = await env.DB.prepare("SELECT * FROM license_keys WHERE key_hash=?").bind(await sha256(key)).first();
    if (!row || row.status !== "active" || row.activation_state !== "available") return json({ error: "key_unavailable" }, 403);
    const expiresAt = row.duration_days === null ? null : now() + row.duration_days * 86400;
    const token = randomHex(32);
    const activatedAt = now();
    const activationResults = await env.DB.batch([
      env.DB.prepare("UPDATE license_keys SET activation_state='consumed' WHERE id=? AND status='active' AND activation_state='available'").bind(row.id),
      env.DB.prepare(`INSERT INTO license_activations (id,key_id,device_hash,device_label,token_hash,activated_at,expires_at)
        SELECT ?,id,?,?,?,?,? FROM license_keys
        WHERE id=? AND status='active' AND activation_state='consumed'
          AND NOT EXISTS (SELECT 1 FROM license_activations WHERE key_id=?)`)
        .bind(randomHex(16), deviceHash, String(input.deviceLabel || "Android").slice(0, 80), await sha256(token), activatedAt, expiresAt, row.id, row.id)
    ]);
    if (activationResults[1].meta.changes !== 1) return json({ error: "key_unavailable" }, 409);
    await audit(env, row.id, "activated", deviceHash.slice(0, 12));
    return json({ ok: true, token, expiresAt });
  }
  if (request.method === "POST" && url.pathname === "/api/validate") {
    const token = typeof input.token === "string" ? input.token : "";
    if (token.length < 32 || token.length > 256) return json({ valid: false, error: "invalid_token" }, 401);
    const row = await env.DB.prepare(`SELECT k.status,a.device_hash,a.expires_at,b.user_id AS banned_user_id FROM license_activations a
      JOIN license_keys k ON k.id=a.key_id LEFT JOIN license_account_links l ON l.activation_id=a.id
      LEFT JOIN license_user_bans b ON b.user_id=l.user_id WHERE a.token_hash=?`).bind(await sha256(token)).first();
    const valid = !!row && row.status === "active" && row.device_hash === deviceHash && !row.banned_user_id && (row.expires_at === null || row.expires_at > now());
    return json({ valid, expiresAt: row?.expires_at ?? null }, valid ? 200 : 403);
  }
  return json({ error: "not_found" }, 404);
}
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: { "access-control-allow-origin": "*", "access-control-allow-methods": "GET,POST,OPTIONS", "access-control-allow-headers": "authorization,content-type" } });
    if (url.pathname === "/admin-panel") return new Response(ADMIN_HTML, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });
    if (url.pathname.startsWith("/admin/")) return admin(request, env, url);
    if (url.pathname.startsWith("/api/")) return client(request, env, url);
    return json({ service: "CS2A License Server", status: "ok" });
  }
};