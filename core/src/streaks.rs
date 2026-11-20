use rusqlite::{Connection, params};

use crate::error::Result;

const DAY_MS: i64 = 86_400_000;

/// A day counts once both sides have sent a delivered-to-server message. The
/// current day can still be in progress, so yesterday's streak remains visible.
pub(crate) fn count(db: &Connection, conversation: &str, now: i64) -> Result<u32> {
    let mut both = db.prepare(
        "SELECT
          EXISTS(SELECT 1 FROM messages WHERE conversation_id=?1 AND outgoing=0 AND sent_at>=?2 AND sent_at<?3
            AND kind IN ('text','file','poll') AND status IN ('received','sent','delivered','read'))
          AND
          EXISTS(SELECT 1 FROM messages WHERE conversation_id=?1 AND outgoing=1 AND sent_at>=?2 AND sent_at<?3
            AND kind IN ('text','file','poll') AND status IN ('received','sent','delivered','read'))",
    )?;
    let today = now.div_euclid(DAY_MS) * DAY_MS;
    let mut mutual = |day| -> Result<bool> {
        Ok(both.query_row(params![conversation, day, day + DAY_MS], |r| r.get(0))?)
    };
    let mut day = if mutual(today)? { today } else { today - DAY_MS };
    let mut days = 0;
    while mutual(day)? {
        days += 1;
        day -= DAY_MS;
    }
    Ok(if days >= 3 { days } else { 0 })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn requires_three_mutual_days_and_survives_an_unfinished_today() {
        let db = Connection::open_in_memory().unwrap();
        db.execute_batch(
            "CREATE TABLE messages(conversation_id TEXT, outgoing INTEGER, sent_at INTEGER, kind TEXT, status TEXT);
             CREATE INDEX messages_conv_time ON messages(conversation_id,outgoing,sent_at);",
        ).unwrap();
        let insert = |day, outgoing, status| {
            db.execute("INSERT INTO messages VALUES('dm',?1,?2,'text',?3)",
                params![outgoing, day * DAY_MS + 1, status]).unwrap();
        };
        for day in [1, 2] {
            insert(day, 0, "received");
            insert(day, 1, "sent");
        }
        insert(3, 0, "received");
        insert(3, 1, "queued");
        assert_eq!(count(&db, "dm", 3 * DAY_MS + 2).unwrap(), 0);
        insert(3, 1, "sent");
        assert_eq!(count(&db, "dm", 4 * DAY_MS).unwrap(), 3);
        assert_eq!(count(&db, "dm", 5 * DAY_MS).unwrap(), 0);
    }
}
