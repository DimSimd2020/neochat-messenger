-- An installation hint is private metadata, never an authentication credential.
ALTER TABLE devices ADD COLUMN installation_id text;
CREATE UNIQUE INDEX devices_active_installation ON devices(user_id, platform, installation_id)
WHERE revoked_at IS NULL AND installation_id IS NOT NULL;
