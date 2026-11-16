# CS2A License Worker

## Cloudflare setup
1. In Worker settings, add the D1 binding named `DB` pointing to `cs2a-license-db`.
2. Add a secret named `ADMIN_SECRET` containing a randomly generated value of at least 32 characters. Keep it private; never put it in the Android app or a public repository.
3. Apply `schema.sql` to the D1 database using the D1 Console SQL editor.
4. For the Cloudflare dashboard's JavaScript editor, copy the complete `worker.js` file into `JS worker.js`, save, and deploy. The Wrangler entry point is also configured as `worker.js`.

## Admin panel
After deploying, open:

`https://cs2a-license-server.smirnov-pashaqw.workers.dev/admin-panel`

Enter the `ADMIN_SECRET` value in the login field. The page keeps it only in memory for the current tab and does not save it. From the panel you can:
- Generate temporary keys (1–36500 days) or perpetual keys (leave duration blank).
- View activation state, license status, device label, expiry, and event history.
- Deactivate a key (prevents new activation but does not revoke an existing license).
- Block a key (revokes access for the activated device).
- Reset a binding (allows a new activation if the key is not blocked).

The plaintext key is displayed only once after generation. Copy it and deliver it to the customer.

## API
- `POST /api/activate`: JSON `{ "key": "...", "deviceId": "...", "deviceLabel": "Android" }`.
- `POST /api/validate`: JSON `{ "token": "...", "deviceId": "..." }`.
- Admin requests require `Authorization: Bearer <ADMIN_SECRET>`.
- `POST /admin/keys`: JSON `{ "durationDays": null }` for perpetual, or integer 1..36500 for temporary.
- `GET /admin/keys`, `GET /admin/events`.
- `POST /admin/keys/:id/deactivate`, `/block`, and `/reset`.

## Security notes
Only SHA-256 hashes of license keys and bearer tokens are stored. Device IDs are hashed. The admin secret is a Cloudflare Worker secret and is never embedded in the Android app. The browser admin page does not persist the secret.
