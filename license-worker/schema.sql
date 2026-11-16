CREATE TABLE IF NOT EXISTS license_keys (
  id TEXT PRIMARY KEY,
  key_hash TEXT NOT NULL UNIQUE,
  key_encrypted TEXT,
  activation_state TEXT NOT NULL CHECK (activation_state IN ('available','consumed','deactivated')),
  status TEXT NOT NULL CHECK (status IN ('active','blocked')),
  duration_days INTEGER,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS license_activations (
  id TEXT PRIMARY KEY,
  key_id TEXT NOT NULL UNIQUE REFERENCES license_keys(id),
  device_hash TEXT NOT NULL,
  device_label TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,
  activated_at INTEGER NOT NULL,
  expires_at INTEGER
);
CREATE TABLE IF NOT EXISTS license_events (
  id TEXT PRIMARY KEY,
  key_id TEXT,
  action TEXT NOT NULL,
  detail TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_license_events_created ON license_events(created_at);

CREATE TABLE IF NOT EXISTS license_account_links (activation_id TEXT PRIMARY KEY REFERENCES license_activations(id) ON DELETE CASCADE, user_id TEXT NOT NULL, username TEXT NOT NULL DEFAULT '', linked_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS idx_license_account_user ON license_account_links(user_id);
CREATE TABLE IF NOT EXISTS license_user_bans (user_id TEXT PRIMARY KEY, username TEXT NOT NULL DEFAULT '', banned_at INTEGER NOT NULL);
