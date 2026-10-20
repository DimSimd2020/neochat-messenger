-- About text, last seen time (set when the last foreground connection opens or closes)
-- and whether others may see the exact time.
ALTER TABLE users
    ADD COLUMN bio            text NOT NULL DEFAULT '',
    ADD COLUMN last_seen      timestamptz,
    ADD COLUMN show_last_seen boolean NOT NULL DEFAULT true;
