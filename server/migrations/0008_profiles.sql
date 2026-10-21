-- Keep the password derivation salt stable when the public username changes.
ALTER TABLE users ADD COLUMN password_salt text NOT NULL DEFAULT '';
UPDATE users SET password_salt=username;

CREATE TABLE profile_photos (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX profile_photos_user ON profile_photos(user_id, created_at DESC);
INSERT INTO profile_photos(id, user_id)
SELECT avatar::uuid, id FROM users WHERE avatar<>'';
