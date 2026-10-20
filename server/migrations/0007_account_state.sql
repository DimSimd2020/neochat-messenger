-- Account state shared by the user's own devices (contacts, pins, preferences),
-- sealed on the client; the server keeps only the opaque blob and a revision.
CREATE TABLE account_state (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    revision bigint NOT NULL,
    blob bytea NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
