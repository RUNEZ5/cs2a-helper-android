# CS2A License Worker

## Cloudflare setup
1. In the Worker settings, add the D1 binding named `DB` pointing to `cs2a-license-db`.
2. Add a secret named `ADMIN_SECRET` containing a randomly generated value of at least 32 characters. Keep it private; never put it in the Android app.
3. Apply `schema.sql` to the D1 database using the D1 Console SQL editor.
4. Deploy this folder as the Worker code (entry point: `src/index.ts`).

## API
- `POST /api/activate`: JSON `{ "key": "...", "deviceId": "...", "deviceLabel": "Android" }`.
- `POST /api/validate`: JSON `{ "token": "...", "deviceId": "..." }`.
- Admin requests require `Authorization: Bearer <ADMIN_SECRET>`.
- `POST /admin/keys`: JSON `{ "durationDays": null }` for perpetual, or integer 1..36500 for temporary. The plaintext key is returned once.
- `GET /admin/keys`, `GET /admin/events`.
- `POST /admin/keys/:id/deactivate`: prevents new activation; existing issuance stays valid.
- `POST /admin/keys/:id/block`: revokes the issued license.
- `POST /admin/keys/:id/reset`: removes the current device binding and makes an active key available again.

## Security notes
Only SHA-256 hashes of license keys and bearer tokens are stored. Device IDs are hashed. Admin secret is a Cloudflare Worker secret, never a client value.
