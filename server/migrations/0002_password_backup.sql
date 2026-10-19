-- Password-sealed account key copy, optional two-factor sign-in and QR sign-in links.
ALTER TABLE users ADD COLUMN key_backup bytea, ADD COLUMN two_factor boolean NOT NULL DEFAULT false;

DELETE FROM device_links;
ALTER TABLE device_links
    ALTER COLUMN user_id DROP NOT NULL,
    ADD COLUMN kind text NOT NULL DEFAULT 'qr',
    ADD COLUMN sealed bytea;

CREATE TABLE login_codes (
    user_id    uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    code_hash  bytea NOT NULL,
    expires_at timestamptz NOT NULL,
    attempts   int NOT NULL DEFAULT 0
);
