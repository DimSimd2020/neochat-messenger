-- Accounts that may see and download alpha builds (infra/publish-update.py, channel alpha).
-- Keyed by account id: a username can be renamed and then claimed by someone else.
-- More testers are added with INSERT; there is no API for it.
CREATE TABLE alpha_testers (
    user_id  uuid PRIMARY KEY REFERENCES users(id),
    username text NOT NULL
);

INSERT INTO alpha_testers(user_id, username)
SELECT id, username FROM users WHERE username IN ('dimsimd', 'ner1s');
