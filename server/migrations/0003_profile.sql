-- Public profile: display name, username as typed (lookups stay lowercase) and a numeric ID.
ALTER TABLE users
    ADD COLUMN nickname     text NOT NULL DEFAULT '',
    ADD COLUMN display_name text NOT NULL DEFAULT '',
    ADD COLUMN number       bigint GENERATED ALWAYS AS IDENTITY (START WITH 100001) UNIQUE;
