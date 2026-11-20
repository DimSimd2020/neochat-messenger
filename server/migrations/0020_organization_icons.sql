ALTER TABLE organizations ADD COLUMN icon_id uuid UNIQUE;
ALTER TABLE organizations ADD COLUMN icon_png bytea CHECK (octet_length(icon_png) <= 49152);
ALTER TABLE organizations ADD CHECK ((icon_id IS NULL) = (icon_png IS NULL));
