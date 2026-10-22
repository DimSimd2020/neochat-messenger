-- Password reset via the same Firebase email delivery as verification.
-- Only the login password_hash changes; identity, devices and key_backup stay.

CREATE TABLE password_resets (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    email text NOT NULL,
    link_hash bytea NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    sent_at timestamptz,
    consumed_at timestamptz,
    attempts integer NOT NULL DEFAULT 0
);
CREATE INDEX password_resets_user ON password_resets (user_id, created_at);
CREATE INDEX password_resets_expiry ON password_resets (expires_at);
