-- Small public profile photo, base64 JPEG. Messages and files remain E2EE.
ALTER TABLE users ADD COLUMN avatar text NOT NULL DEFAULT '';
