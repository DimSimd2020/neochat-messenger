CREATE TABLE organizations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 64),
    owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    badge text NOT NULL DEFAULT '🏢',
    channel_id uuid UNIQUE REFERENCES conversations(id) ON DELETE SET NULL,
    group_id uuid UNIQUE REFERENCES conversations(id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX organizations_name ON organizations (lower(name));

CREATE TABLE organization_members (
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role text NOT NULL CHECK (role IN ('owner', 'admin', 'member', 'invited')),
    PRIMARY KEY (organization_id, user_id)
);
CREATE INDEX organization_members_user ON organization_members (user_id);
