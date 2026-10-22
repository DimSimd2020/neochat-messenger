ALTER TABLE users ADD COLUMN email text;
ALTER TABLE users ADD COLUMN email_verified_at timestamptz;
ALTER TABLE users ADD CONSTRAINT users_verified_email CHECK ((email IS NULL) = (email_verified_at IS NULL));
CREATE UNIQUE INDEX users_email ON users (email) WHERE email IS NOT NULL;

CREATE TABLE email_links (
    id uuid PRIMARY KEY,
    email text NOT NULL,
    user_id uuid REFERENCES users(id) ON DELETE CASCADE,
    device_id uuid REFERENCES devices(id) ON DELETE CASCADE,
    ticket_hash bytea NOT NULL UNIQUE,
    link_hash bytea NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    sent_at timestamptz,
    confirmed_at timestamptz,
    consumed_at timestamptz
);
CREATE INDEX email_links_expiry ON email_links (expires_at);
CREATE INDEX email_links_address ON email_links (email, created_at);
