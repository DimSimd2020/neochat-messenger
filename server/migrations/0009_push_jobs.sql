-- A coalesced, durable wake-up per device. No message content goes to Firebase.
CREATE TABLE push_jobs (
    device_id uuid PRIMARY KEY REFERENCES push_tokens(device_id) ON DELETE CASCADE,
    event_seq bigint NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX push_jobs_ready ON push_jobs(next_attempt_at);
