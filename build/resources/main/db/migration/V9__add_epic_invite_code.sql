ALTER TABLE epics ADD COLUMN invite_code VARCHAR(32) NULL;
UPDATE epics SET invite_code = UPPER(SUBSTRING(REPLACE(id::text, '-', ''), 1, 8)) WHERE invite_code IS NULL;
ALTER TABLE epics ALTER COLUMN invite_code SET NOT NULL;
CREATE UNIQUE INDEX idx_epics_invite_code ON epics(invite_code);
