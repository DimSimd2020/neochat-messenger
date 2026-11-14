-- View-once media: an object may be burned once it has been seen, and only a device that has
-- already downloaded it may ask for that. 'burned' joins the states an object can rest in.
ALTER TABLE attachments ADD COLUMN downloaded_at timestamptz;
ALTER TABLE attachments DROP CONSTRAINT attachments_state_check;
ALTER TABLE attachments ADD CONSTRAINT attachments_state_check
    CHECK (state IN ('reserved', 'ready', 'attached', 'burned'));
