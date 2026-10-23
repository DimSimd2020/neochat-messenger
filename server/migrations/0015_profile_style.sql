-- How a profile looks to others: the colour of the name and the header gradient.
-- name_color is '#rrggbb' or ''; header is up to three '#rrggbb' joined by commas, or ''.
ALTER TABLE users ADD COLUMN name_color text NOT NULL DEFAULT '';
ALTER TABLE users ADD COLUMN header text NOT NULL DEFAULT '';
