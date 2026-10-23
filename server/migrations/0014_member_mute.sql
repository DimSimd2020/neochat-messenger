-- A member the owner or an admin muted cannot post until this moment; NULL means not muted.
-- 'infinity' mutes without an end. Receipts and votes (control sends) still go through.
ALTER TABLE memberships ADD COLUMN muted_until timestamptz;
