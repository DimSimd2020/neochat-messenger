//! Encrypted local database (SQLCipher) and libsignal store implementations.
//!
//! All crypto state lives in the same database as messages and the outbox, so a
//! ratchet step, the resulting ciphertext request and the processed event id are
//! committed in one transaction.

use libsignal_protocol::{
    Direction, GenericSignedPreKey, IdentityChange, IdentityKey, IdentityKeyPair, IdentityKeyStore, KyberPreKeyId,
    KyberPreKeyRecord, KyberPreKeyStore, PreKeyId, PreKeyRecord, PreKeyStore, ProtocolAddress, PublicKey,
    SessionRecord, SessionStore, SignalProtocolError, SignedPreKeyId, SignedPreKeyRecord, SignedPreKeyStore,
};
use rusqlite::{Connection, OptionalExtension, Transaction, params};

use crate::error::{Error, Result};

const SCHEMA: &str = r#"
CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS sessions (address TEXT PRIMARY KEY, record BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS identities (address TEXT PRIMARY KEY, identity BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS prekeys (id INTEGER PRIMARY KEY, record BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS signed_prekeys (id INTEGER PRIMARY KEY, record BLOB NOT NULL, created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS kyber_prekeys (id INTEGER PRIMARY KEY, record BLOB NOT NULL, last_resort INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS kyber_used (kyber_id INTEGER NOT NULL, signed_id INTEGER NOT NULL, base_key BLOB NOT NULL,
    PRIMARY KEY (kyber_id, signed_id, base_key));
CREATE TABLE IF NOT EXISTS contacts (
    user_id TEXT PRIMARY KEY,
    username TEXT NOT NULL,
    first_name TEXT NOT NULL DEFAULT '',
    last_name TEXT NOT NULL DEFAULT '',
    account_key BLOB NOT NULL,
    identity_epoch TEXT NOT NULL DEFAULT '1',
    roster_revision INTEGER NOT NULL DEFAULT 0,
    verified INTEGER NOT NULL DEFAULT 0,
    key_changed INTEGER NOT NULL DEFAULT 0,
    pending_account_key BLOB,
    is_contact INTEGER NOT NULL DEFAULT 0,
    added_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS known_devices (
    user_id TEXT NOT NULL,
    device_id TEXT NOT NULL,
    identity_key BLOB NOT NULL,
    PRIMARY KEY (user_id, device_id)
);
CREATE TABLE IF NOT EXISTS conversations (
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL,
    title TEXT NOT NULL DEFAULT '',
    peer_user_id TEXT,
    owner_id TEXT,
    revision TEXT NOT NULL DEFAULT '1',
    members TEXT NOT NULL DEFAULT '[]',
    recipients TEXT NOT NULL DEFAULT '[]',
    recipient_rev TEXT NOT NULL DEFAULT '',
    archived INTEGER NOT NULL DEFAULT 0,
    left_group INTEGER NOT NULL DEFAULT 0,
    last_activity INTEGER NOT NULL DEFAULT 0,
    last_preview TEXT NOT NULL DEFAULT '',
    unread INTEGER NOT NULL DEFAULT 0,
    draft TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS messages (
    local_id INTEGER PRIMARY KEY AUTOINCREMENT,
    id TEXT NOT NULL UNIQUE,
    conversation_id TEXT NOT NULL,
    sender_user_id TEXT NOT NULL,
    sender_device_id TEXT NOT NULL DEFAULT '',
    outgoing INTEGER NOT NULL,
    kind TEXT NOT NULL,
    body TEXT NOT NULL DEFAULT '',
    reply_to TEXT,
    file TEXT,
    status TEXT NOT NULL,
    error TEXT,
    server_id TEXT,
    sent_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS messages_conv ON messages(conversation_id, local_id);
CREATE TABLE IF NOT EXISTS receipts (
    message_id TEXT NOT NULL, user_id TEXT NOT NULL, kind TEXT NOT NULL, at INTEGER NOT NULL,
    PRIMARY KEY (message_id, user_id, kind)
);
CREATE TABLE IF NOT EXISTS outbox (
    id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    payload BLOB NOT NULL,
    request BLOB,
    attachment_path TEXT,
    attachment_id TEXT,
    visible INTEGER NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_at INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS processed (event_id TEXT PRIMARY KEY, at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS deleted_messages (
    message_id TEXT NOT NULL, conversation_id TEXT NOT NULL, sender_user_id TEXT NOT NULL,
    PRIMARY KEY (message_id, conversation_id, sender_user_id)
);
"#;

pub struct Db {
    pub conn: Connection,
}

impl Db {
    /// Opens (or creates) the database with a raw 256-bit key from the platform keystore.
    /// Fails if the cipher backend is missing or the key is wrong.
    pub fn open(path: &str, key: &[u8]) -> Result<Db> {
        if key.len() != 32 {
            return Err(Error::Invalid("Ключ базы должен быть 32 байта".into()));
        }
        let conn = Connection::open(path)?;
        conn.execute_batch(&format!("PRAGMA key = \"x'{}'\";", hex::encode(key)))?;
        let cipher: Option<String> = conn.query_row("PRAGMA cipher_version", [], |r| r.get(0)).optional()?;
        if cipher.map(|c| c.is_empty()).unwrap_or(true) {
            return Err(Error::Db("SQLCipher backend is not available".into()));
        }
        // Reading the schema fails here if the key does not match the file.
        conn.query_row("SELECT count(*) FROM sqlite_master", [], |r| r.get::<_, i64>(0))
            .map_err(|_| Error::Db("Не удалось открыть локальную базу: неверный ключ".into()))?;
        // FULL sync: a lost ratchet step after power loss could repeat a message key.
        conn.execute_batch("PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA foreign_keys=ON; PRAGMA secure_delete=ON;")?;
        conn.execute_batch(SCHEMA)?;
        // Columns added after the first release; the error for an existing column is expected.
        for sql in [
            "ALTER TABLE contacts ADD COLUMN profile_name TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE contacts ADD COLUMN number TEXT NOT NULL DEFAULT ''",
            // Groups and channels: verified state, roles, settings, info and member tags.
            "ALTER TABLE conversations ADD COLUMN admins TEXT NOT NULL DEFAULT '[]'",
            "ALTER TABLE conversations ADD COLUMN settings TEXT NOT NULL DEFAULT '{}'",
            "ALTER TABLE conversations ADD COLUMN description TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE conversations ADD COLUMN tags TEXT NOT NULL DEFAULT '{}'",
            "ALTER TABLE conversations ADD COLUMN state_rev INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE conversations ADD COLUMN state_blob BLOB",
            "ALTER TABLE messages ADD COLUMN poll TEXT",
            // Archive and pins are per device; pinned holds the pin time (0 = not pinned).
            "ALTER TABLE conversations ADD COLUMN in_archive INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE contacts ADD COLUMN bio TEXT NOT NULL DEFAULT ''",
            // Forwarded messages keep a small header: who originally sent it and when.
            "ALTER TABLE messages ADD COLUMN fwd TEXT",
        ] {
            let _ = conn.execute(sql, []);
        }
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS poll_votes (
                poll_id TEXT NOT NULL, user_id TEXT NOT NULL, choices TEXT NOT NULL, at INTEGER NOT NULL,
                PRIMARY KEY (poll_id, user_id)
            );
            CREATE TABLE IF NOT EXISTS reactions (
                message_id TEXT NOT NULL, user_id TEXT NOT NULL, emoji TEXT NOT NULL, at INTEGER NOT NULL,
                PRIMARY KEY (message_id, user_id)
            );",
        )?;
        Ok(Db { conn })
    }
}

pub fn kv_get(c: &Connection, k: &str) -> Result<Option<Vec<u8>>> {
    Ok(c.query_row("SELECT v FROM kv WHERE k=?1", [k], |r| r.get(0)).optional()?)
}

pub fn kv_get_str(c: &Connection, k: &str) -> Result<Option<String>> {
    Ok(kv_get(c, k)?.map(|v| String::from_utf8_lossy(&v).into_owned()))
}

pub fn kv_set(c: &Connection, k: &str, v: &[u8]) -> Result<()> {
    c.execute("INSERT INTO kv(k, v) VALUES (?1, ?2) ON CONFLICT(k) DO UPDATE SET v=excluded.v", params![k, v])?;
    Ok(())
}

pub fn kv_del(c: &Connection, k: &str) -> Result<()> {
    c.execute("DELETE FROM kv WHERE k=?1", [k])?;
    Ok(())
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

fn db_err(e: rusqlite::Error) -> SignalProtocolError {
    SignalProtocolError::InvalidState("store", e.to_string())
}

/// libsignal stores backed by an open transaction.
pub struct SignalStore<'a> {
    pub tx: &'a Transaction<'a>,
    pub identity: IdentityKeyPair,
    pub registration_id: u32,
}

impl<'a> SignalStore<'a> {
    pub fn new(tx: &'a Transaction<'a>) -> Result<Self> {
        let id = kv_get(tx, "device_identity")?.ok_or(Error::NotLoggedIn)?;
        let identity = IdentityKeyPair::try_from(id.as_slice())?;
        let reg = kv_get_str(tx, "registration_id")?.ok_or(Error::NotLoggedIn)?;
        Ok(SignalStore { tx, identity, registration_id: reg.parse().unwrap_or(0) })
    }
}

#[async_trait::async_trait(?Send)]
impl IdentityKeyStore for SignalStore<'_> {
    async fn get_identity_key_pair(&self) -> libsignal_protocol::error::Result<IdentityKeyPair> {
        Ok(self.identity)
    }

    async fn get_local_registration_id(&self) -> libsignal_protocol::error::Result<u32> {
        Ok(self.registration_id)
    }

    async fn save_identity(&mut self, address: &ProtocolAddress, identity: &IdentityKey) -> libsignal_protocol::error::Result<IdentityChange> {
        let old: Option<Vec<u8>> = self
            .tx
            .query_row("SELECT identity FROM identities WHERE address=?1", [address.name()], |r| r.get(0))
            .optional()
            .map_err(db_err)?;
        let new = identity.serialize();
        self.tx
            .execute(
                "INSERT INTO identities(address, identity) VALUES (?1, ?2) ON CONFLICT(address) DO UPDATE SET identity=excluded.identity",
                params![address.name(), &new[..]],
            )
            .map_err(db_err)?;
        Ok(IdentityChange::from_changed(old.is_some_and(|o| o[..] != new[..])))
    }

    /// A device identity is trusted only if it appears in the verified,
    /// account-signed roster of its owner (table known_devices).
    async fn is_trusted_identity(&self, address: &ProtocolAddress, identity: &IdentityKey, _d: Direction) -> libsignal_protocol::error::Result<bool> {
        let known: Option<Vec<u8>> = self
            .tx
            .query_row("SELECT identity_key FROM known_devices WHERE device_id=?1", [address.name()], |r| r.get(0))
            .optional()
            .map_err(db_err)?;
        Ok(known.is_some_and(|k| k[..] == identity.serialize()[..]))
    }

    async fn get_identity(&self, address: &ProtocolAddress) -> libsignal_protocol::error::Result<Option<IdentityKey>> {
        let v: Option<Vec<u8>> = self
            .tx
            .query_row("SELECT identity FROM identities WHERE address=?1", [address.name()], |r| r.get(0))
            .optional()
            .map_err(db_err)?;
        v.map(|b| IdentityKey::decode(&b)).transpose()
    }
}

#[async_trait::async_trait(?Send)]
impl SessionStore for SignalStore<'_> {
    async fn load_session(&self, address: &ProtocolAddress) -> libsignal_protocol::error::Result<Option<SessionRecord>> {
        let v: Option<Vec<u8>> = self
            .tx
            .query_row("SELECT record FROM sessions WHERE address=?1", [address.name()], |r| r.get(0))
            .optional()
            .map_err(db_err)?;
        v.map(|b| SessionRecord::deserialize(&b)).transpose()
    }

    async fn store_session(&mut self, address: &ProtocolAddress, record: &SessionRecord) -> libsignal_protocol::error::Result<()> {
        self.tx
            .execute(
                "INSERT INTO sessions(address, record) VALUES (?1, ?2) ON CONFLICT(address) DO UPDATE SET record=excluded.record",
                params![address.name(), record.serialize()?],
            )
            .map_err(db_err)?;
        Ok(())
    }
}

#[async_trait::async_trait(?Send)]
impl PreKeyStore for SignalStore<'_> {
    async fn get_pre_key(&self, id: PreKeyId) -> libsignal_protocol::error::Result<PreKeyRecord> {
        let v: Option<Vec<u8>> = self
            .tx
            .query_row("SELECT record FROM prekeys WHERE id=?1", [u32::from(id)], |r| r.get(0))
            .optional()
            .map_err(db_err)?;
        PreKeyRecord::deserialize(&v.ok_or(SignalProtocolError::InvalidPreKeyId)?)
    }

    async fn save_pre_key(&mut self, id: PreKeyId, record: &PreKeyRecord) -> libsignal_protocol::error::Result<()> {
        self.tx
            .execute("INSERT OR REPLACE INTO prekeys(id, record) VALUES (?1, ?2)", params![u32::from(id), record.serialize()?])
            .map_err(db_err)?;
        Ok(())
    }

    async fn remove_pre_key(&mut self, id: PreKeyId) -> libsignal_protocol::error::Result<()> {
        self.tx.execute("DELETE FROM prekeys WHERE id=?1", [u32::from(id)]).map_err(db_err)?;
        Ok(())
    }
}

#[async_trait::async_trait(?Send)]
impl SignedPreKeyStore for SignalStore<'_> {
    async fn get_signed_pre_key(&self, id: SignedPreKeyId) -> libsignal_protocol::error::Result<SignedPreKeyRecord> {
        let v: Option<Vec<u8>> = self
            .tx
            .query_row("SELECT record FROM signed_prekeys WHERE id=?1", [u32::from(id)], |r| r.get(0))
            .optional()
            .map_err(db_err)?;
        SignedPreKeyRecord::deserialize(&v.ok_or(SignalProtocolError::InvalidSignedPreKeyId)?)
    }

    async fn save_signed_pre_key(&mut self, id: SignedPreKeyId, record: &SignedPreKeyRecord) -> libsignal_protocol::error::Result<()> {
        self.tx
            .execute(
                "INSERT OR REPLACE INTO signed_prekeys(id, record, created_at) VALUES (?1, ?2, ?3)",
                params![u32::from(id), record.serialize()?, now_ms()],
            )
            .map_err(db_err)?;
        Ok(())
    }
}

#[async_trait::async_trait(?Send)]
impl KyberPreKeyStore for SignalStore<'_> {
    async fn get_kyber_pre_key(&self, id: KyberPreKeyId) -> libsignal_protocol::error::Result<KyberPreKeyRecord> {
        let v: Option<Vec<u8>> = self
            .tx
            .query_row("SELECT record FROM kyber_prekeys WHERE id=?1", [u32::from(id)], |r| r.get(0))
            .optional()
            .map_err(db_err)?;
        KyberPreKeyRecord::deserialize(&v.ok_or(SignalProtocolError::InvalidKyberPreKeyId)?)
    }

    async fn save_kyber_pre_key(&mut self, id: KyberPreKeyId, record: &KyberPreKeyRecord) -> libsignal_protocol::error::Result<()> {
        self.tx
            .execute(
                "INSERT OR REPLACE INTO kyber_prekeys(id, record, last_resort) VALUES (?1, ?2, 0)",
                params![u32::from(id), record.serialize()?],
            )
            .map_err(db_err)?;
        Ok(())
    }

    /// One-time Kyber keys are deleted. The last-resort key stays, but the same
    /// (kyber id, signed id, base key) triple is rejected to stop replays.
    async fn mark_kyber_pre_key_used(&mut self, id: KyberPreKeyId, ec: SignedPreKeyId, base_key: &PublicKey) -> libsignal_protocol::error::Result<()> {
        let last_resort: Option<i64> = self
            .tx
            .query_row("SELECT last_resort FROM kyber_prekeys WHERE id=?1", [u32::from(id)], |r| r.get(0))
            .optional()
            .map_err(db_err)?;
        match last_resort {
            Some(0) => {
                self.tx.execute("DELETE FROM kyber_prekeys WHERE id=?1", [u32::from(id)]).map_err(db_err)?;
            }
            Some(_) => {
                let inserted = self
                    .tx
                    .execute(
                        "INSERT OR IGNORE INTO kyber_used(kyber_id, signed_id, base_key) VALUES (?1, ?2, ?3)",
                        params![u32::from(id), u32::from(ec), &base_key.serialize()[..]],
                    )
                    .map_err(db_err)?;
                if inserted == 0 {
                    return Err(SignalProtocolError::InvalidMessage(
                        libsignal_protocol::CiphertextMessageType::PreKey,
                        "reused last-resort kyber prekey".to_string(),
                    ));
                }
            }
            None => return Err(SignalProtocolError::InvalidKyberPreKeyId),
        }
        Ok(())
    }
}

pub fn save_last_resort_kyber(tx: &Transaction, record: &KyberPreKeyRecord) -> Result<()> {
    tx.execute(
        "INSERT OR REPLACE INTO kyber_prekeys(id, record, last_resort) VALUES (?1, ?2, 1)",
        params![u32::from(record.id()?), record.serialize()?],
    )?;
    Ok(())
}
