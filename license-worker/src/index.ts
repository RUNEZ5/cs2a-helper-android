export interface Env {
  DB: D1Database;
  ADMIN_SECRET: string;
}

type LicenseRow = {
  id: string; key_hash: string; activation_state: string; status: string;
  duration_days: number | null; created_at: number; expires_at: number | null;
};
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), {
  status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }
});
const now = () => Math.floor(Date.now() / 1000);
async function sha256(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map(x => x.toString(16).padStart(2, "0")).join("");
}
function randomHex(bytes = 32): string {
  const data = crypto.getRandomValues(new Uint8Array(bytes));
  return [...data].map(x => x.toString(16).padStart(2, "0")).join("");
}
function randomKey(): string {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const bytes = crypto.getRandomValues(new Uint8Array(20));
  const parts = [0, 1, 2, 3].map(i => [...bytes.slice(i * 5, i * 5 + 5)].map(n => alphabet[n % alphabet.length]).join(""));
  return "CS2A-" + parts.join("-");
}
function authorized(request: Request, env: Env): boolean {
  const value = request.headers.get("authorization") || "";
  return value.startsWith("Bearer ") && value.slice(7) === env.ADMIN_SECRET && env.ADMIN_SECRET.length >= 32;
}
async function body(request: Request): Promise<any> {
  try { return await request.json(); } catch { return null; }
}
async function audit(env: Env, keyId: string | null, action: string, detail = "") {
  await env.DB.prepare("INSERT INTO license_events (id,key_id,action,detail,created_at) VALUES (?,?,?,?,?)")
    .bind(randomHex(12), keyId, action, detail.slice(0, 300), now()).run();
}
async function admin(request: Request, env: Env, url: URL): Promise<Response> {
  if (!authorized(request, env)) return json({ error: "unauthorized" }, 401);
  if (request.method === "POST" && url.pathname === "/admin/keys") {
    const input = await body(request);
    const days = input?.durationDays;
    if (!(days === null || (Number.isInteger(days) && days >= 1 && days <= 36500))) return json({ error: "durationDays must be null or 1..36500" }, 400);
    const key = randomKey(), id = randomHex(16), created = now();
    await env.DB.prepare("INSERT INTO license_keys (id,key_hash,activation_state,status,duration_days,created_at) VALUES (?,?, 'available','active',?,?)")
      .bind(id, await sha256(key), days, created).run();
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
    const result = await env.DB.prepare(`SELECT k.id,k.activation_state,k.status,k.duration_days,k.created_at,
      a.device_label,a.activated_at,a.expires_at
      FROM license_keys k LEFT JOIN license_activations a ON a.key_id=k.id
      ORDER BY k.created_at DESC LIMIT 500`).all();
    return json({ keys: result.results });
  }
  if (request.method === "GET" && url.pathname === "/admin/events") {
    const result = await env.DB.prepare("SELECT key_id,action,detail,created_at FROM license_events ORDER BY created_at DESC LIMIT 500").all();
    return json({ events: result.results });
  }
  return json({ error: "not_found" }, 404);
}
async function client(request: Request, env: Env, url: URL): Promise<Response> {
  const input = await body(request);
  if (!input || typeof input !== "object") return json({ error: "invalid_json" }, 400);
  const deviceId = typeof input.deviceId === "string" ? input.deviceId.trim() : "";
  if (deviceId.length < 16 || deviceId.length > 128) return json({ error: "invalid_device" }, 400);
  const deviceHash = await sha256(deviceId);
  if (request.method === "POST" && url.pathname === "/api/activate") {
    const key = typeof input.key === "string" ? input.key.trim().toUpperCase() : "";
    if (key.length < 12 || key.length > 64) return json({ error: "invalid_key" }, 400);
    const row = await env.DB.prepare("SELECT * FROM license_keys WHERE key_hash=?").bind(await sha256(key)).first<LicenseRow>();
    if (!row || row.status !== "active" || row.activation_state !== "available") return json({ error: "key_unavailable" }, 403);
    const expiresAt = row.duration_days === null ? null : now() + row.duration_days * 86400;
    const token = randomHex(32);
    const update = await env.DB.prepare("UPDATE license_keys SET activation_state='consumed' WHERE id=? AND status='active' AND activation_state='available'").bind(row.id).run();
    if (update.meta.changes !== 1) return json({ error: "key_unavailable" }, 409);
    await env.DB.prepare("INSERT INTO license_activations (id,key_id,device_hash,device_label,token_hash,activated_at,expires_at) VALUES (?,?,?,?,?,?,?)")
      .bind(randomHex(16), row.id, deviceHash, String(input.deviceLabel || "Android").slice(0, 80), await sha256(token), now(), expiresAt).run();
    await audit(env, row.id, "activated", deviceHash.slice(0, 12));
    return json({ ok: true, token, expiresAt });
  }
  if (request.method === "POST" && url.pathname === "/api/validate") {
    const token = typeof input.token === "string" ? input.token : "";
    if (token.length < 32 || token.length > 256) return json({ valid: false, error: "invalid_token" }, 401);
    const row = await env.DB.prepare(`SELECT k.status,a.device_hash,a.expires_at FROM license_activations a
      JOIN license_keys k ON k.id=a.key_id WHERE a.token_hash=?`).bind(await sha256(token)).first<any>();
    const valid = !!row && row.status === "active" && row.device_hash === deviceHash && (row.expires_at === null || row.expires_at > now());
    return json({ valid, expiresAt: row?.expires_at ?? null }, valid ? 200 : 403);
  }
  return json({ error: "not_found" }, 404);
}
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: { "access-control-allow-origin": "*", "access-control-allow-methods": "GET,POST,OPTIONS", "access-control-allow-headers": "authorization,content-type" } });
    if (url.pathname.startsWith("/admin/")) return admin(request, env, url);
    if (url.pathname.startsWith("/api/")) return client(request, env, url);
    return json({ service: "CS2A License Server", status: "ok" });
  }
};