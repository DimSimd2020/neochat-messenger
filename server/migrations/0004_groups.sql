-- Channels, member roles, group settings and the chain of signed group states.
ALTER TABLE conversations DROP CONSTRAINT conversations_kind_check;
ALTER TABLE conversations ADD CONSTRAINT conversations_kind_check CHECK (kind IN ('direct', 'group', 'channel'));
ALTER TABLE conversations ADD COLUMN settings jsonb NOT NULL DEFAULT '{}';
ALTER TABLE memberships ADD COLUMN role text NOT NULL DEFAULT 'member' CHECK (role IN ('owner', 'admin', 'member'));
UPDATE memberships m SET role = 'owner' FROM conversations c WHERE c.id = m.conversation_id AND c.owner_id = m.user_id;

-- Every signed state, so a client can verify admin-signed changes from its last trusted state.
CREATE TABLE group_states (
    conversation_id uuid NOT NULL REFERENCES conversations(id),
    revision        bigint NOT NULL,
    blob            bytea NOT NULL,
    sig             bytea NOT NULL,
    PRIMARY KEY (conversation_id, revision)
);
INSERT INTO group_states(conversation_id, revision, blob, sig)
SELECT id, revision - jsonb_array_length(leaves), state_blob, state_sig FROM conversations WHERE state_blob IS NOT NULL;
