-- Profile photo file ID; the JPEG is stored separately from the profile JSON.
ALTER TABLE users ADD COLUMN avatar text NOT NULL DEFAULT '';
