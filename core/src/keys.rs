//! Device key material and account-signed statements.
//!
//! Signed structures use a fixed binary layout with a domain string, version,
//! big-endian integers and sorted member lists, so every platform produces the
//! same bytes. Signatures are XEdDSA from libsignal (`calculate_signature`).

use base64::Engine;
use base64::engine::general_purpose::STANDARD as B64;
use ed25519_dalek::{Signer, SigningKey};
use libsignal_protocol::{
    GenericSignedPreKey, IdentityKey, IdentityKeyPair, KeyPair, KyberPreKeyId, KyberPreKeyRecord, PreKeyId, PreKeyRecord,
    SignedPreKeyId, SignedPreKeyRecord, Timestamp, kem,
};
use rand::{Rng, RngCore};
use rusqlite::{Transaction, params};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use uuid::Uuid;

use crate::error::{Error, Result, crypto};
use crate::store::{kv_get, kv_get_str, kv_set, now_ms, save_last_resort_kyber};

pub const PREKEY_BATCH: u32 = 100;

const ROSTER_DOMAIN: &[u8] = b"NeoChat-roster-v1\0";
const GROUP_DOMAIN: &[u8] = b"NeoChat-group-v1\0";
/// v2 adds the signer, the kind (group or channel) and the admin list.
const GROUP_DOMAIN_V2: &[u8] = b"NeoChat-group-v2\0";
const LEAVE_DOMAIN: &[u8] = b"NeoChat-leave-v1\0";

pub fn b64(b: &[u8]) -> String {
    B64.encode(b)
}

pub fn unb64(s: &str) -> Result<Vec<u8>> {
    B64.decode(s).map_err(|_| Error::Invalid("Некорректные base64-данные".into()))
}

pub fn random_bytes<const N: usize>() -> [u8; N] {
    let mut b = [0u8; N];
    rand::rng().fill_bytes(&mut b);
    b
}

pub fn sha256(b: &[u8]) -> [u8; 32] {
    Sha256::digest(b).into()
}

fn uuid_bytes(s: &str) -> Result<[u8; 16]> {
    Uuid::parse_str(s).map(|u| *u.as_bytes()).map_err(|_| Error::Invalid("Некорректный идентификатор".into()))
}

fn uuid_str(b: &[u8]) -> String {
    Uuid::from_slice(b).map(|u| u.to_string()).unwrap_or_default()
}

// ---- device keys ----

/// Fresh key material of one device, stored locally and published as a bundle.
pub struct NewDevice {
    pub device_id: String,
    pub identity: IdentityKeyPair,
    pub registration_id: u32,
    pub auth: SigningKey,
    pub signed_prekey: SignedPreKeyRecord,
    pub kyber_last_resort: KyberPreKeyRecord,
    pub one_time: Vec<PreKeyRecord>,
    pub kyber_one_time: Vec<KyberPreKeyRecord>,
}

pub fn new_device() -> Result<NewDevice> {
    let mut rng = rand::rng();
    let identity = IdentityKeyPair::generate(&mut rng);
    let signed_prekey = new_signed_prekey(&identity, 1)?;
    let kyber_last_resort = KyberPreKeyRecord::generate(kem::KeyType::Kyber1024, KyberPreKeyId::from(1), identity.private_key())?;
    let (one_time, kyber_one_time) = new_one_time_prekeys(&identity, 2, PREKEY_BATCH)?;
    Ok(NewDevice {
        device_id: Uuid::new_v4().to_string(),
        identity,
        registration_id: rng.random_range(1..16380),
        auth: SigningKey::from_bytes(&random_bytes::<32>()),
        signed_prekey,
        kyber_last_resort,
        one_time,
        kyber_one_time,
    })
}

pub fn new_signed_prekey(identity: &IdentityKeyPair, id: u32) -> Result<SignedPreKeyRecord> {
    let mut rng = rand::rng();
    let kp = KeyPair::generate(&mut rng);
    let sig = identity.private_key().calculate_signature(&kp.public_key.serialize(), &mut rng).map_err(crypto)?;
    Ok(SignedPreKeyRecord::new(SignedPreKeyId::from(id), Timestamp::from_epoch_millis(now_ms() as u64), &kp, &sig))
}

pub fn new_one_time_prekeys(identity: &IdentityKeyPair, start: u32, n: u32) -> Result<(Vec<PreKeyRecord>, Vec<KyberPreKeyRecord>)> {
    let mut rng = rand::rng();
    let mut ec = Vec::with_capacity(n as usize);
    let mut ky = Vec::with_capacity(n as usize);
    for id in start..start + n {
        ec.push(PreKeyRecord::new(PreKeyId::from(id), &KeyPair::generate(&mut rng)));
        ky.push(KyberPreKeyRecord::generate(kem::KeyType::Kyber1024, KyberPreKeyId::from(id), identity.private_key())?);
    }
    Ok((ec, ky))
}

pub fn prekeys_json(ec: &[PreKeyRecord], ky: &[KyberPreKeyRecord]) -> Result<(Value, Value)> {
    let ec: Vec<Value> = ec
        .iter()
        .map(|p| Ok(json!({"id": u32::from(p.id()?), "public_key": b64(&p.public_key()?.serialize())})))
        .collect::<Result<_>>()?;
    let ky: Vec<Value> = ky
        .iter()
        .map(|k| Ok(json!({"id": u32::from(k.id()?), "public_key": b64(&k.public_key()?.serialize()), "signature": b64(&k.signature()?)})))
        .collect::<Result<_>>()?;
    Ok((Value::Array(ec), Value::Array(ky)))
}

pub fn signed_prekey_json(sp: &SignedPreKeyRecord) -> Result<Value> {
    Ok(json!({"id": u32::from(sp.id()?), "public_key": b64(&sp.public_key()?.serialize()), "signature": b64(&sp.signature()?)}))
}

pub fn kyber_prekey_json(k: &KyberPreKeyRecord) -> Result<Value> {
    Ok(json!({"id": u32::from(k.id()?), "public_key": b64(&k.public_key()?.serialize()), "signature": b64(&k.signature()?)}))
}

impl NewDevice {
    pub fn upload_json(&self, name: &str, platform: &str) -> Result<Value> {
        let (ec, ky) = prekeys_json(&self.one_time, &self.kyber_one_time)?;
        Ok(json!({
            "device_id": self.device_id,
            "name": name,
            "platform": platform,
            "registration_id": self.registration_id,
            "identity_key": b64(&self.identity.identity_key().serialize()),
            "auth_key": b64(self.auth.verifying_key().as_bytes()),
            "signed_prekey": signed_prekey_json(&self.signed_prekey)?,
            "kyber_prekey": kyber_prekey_json(&self.kyber_last_resort)?,
            "one_time_prekeys": ec,
            "kyber_one_time_prekeys": ky,
        }))
    }

    pub fn roster_entry(&self) -> RosterDevice {
        RosterDevice {
            device_id: self.device_id.clone(),
            identity_key: self.identity.identity_key().serialize().to_vec(),
            auth_key: self.auth.verifying_key().to_bytes().to_vec(),
        }
    }

    /// Persists private keys in the same transaction as the account record.
    pub fn persist(&self, tx: &Transaction) -> Result<()> {
        kv_set(tx, "device_id", self.device_id.as_bytes())?;
        kv_set(tx, "device_identity", &self.identity.serialize())?;
        kv_set(tx, "registration_id", self.registration_id.to_string().as_bytes())?;
        kv_set(tx, "auth_key", &self.auth.to_bytes())?;
        tx.execute(
            "INSERT OR REPLACE INTO signed_prekeys(id, record, created_at) VALUES (?1, ?2, ?3)",
            params![u32::from(self.signed_prekey.id()?), self.signed_prekey.serialize()?, now_ms()],
        )?;
        save_last_resort_kyber(tx, &self.kyber_last_resort)?;
        save_one_time(tx, &self.one_time, &self.kyber_one_time)?;
        kv_set(tx, "next_prekey_id", (2 + PREKEY_BATCH).to_string().as_bytes())?;
        kv_set(tx, "next_signed_prekey_id", b"2")?;
        Ok(())
    }
}

pub fn save_one_time(tx: &Transaction, ec: &[PreKeyRecord], ky: &[KyberPreKeyRecord]) -> Result<()> {
    for p in ec {
        tx.execute("INSERT OR REPLACE INTO prekeys(id, record) VALUES (?1, ?2)", params![u32::from(p.id()?), p.serialize()?])?;
    }
    for k in ky {
        tx.execute(
            "INSERT OR REPLACE INTO kyber_prekeys(id, record, last_resort) VALUES (?1, ?2, 0)",
            params![u32::from(k.id()?), k.serialize()?],
        )?;
    }
    Ok(())
}

pub fn local_identity(tx: &rusqlite::Connection) -> Result<IdentityKeyPair> {
    let b = kv_get(tx, "device_identity")?.ok_or(Error::NotLoggedIn)?;
    Ok(IdentityKeyPair::try_from(b.as_slice())?)
}

pub fn login_signature(conn: &rusqlite::Connection, username: &str, device_id: &str, ts: &str) -> Result<Vec<u8>> {
    let key = kv_get(conn, "auth_key")?.ok_or(Error::NotLoggedIn)?;
    let key: [u8; 32] = key.try_into().map_err(|_| Error::Crypto("Повреждён ключ устройства".into()))?;
    let msg = format!("NeoChat-login-v1\n{username}\n{device_id}\n{ts}");
    Ok(SigningKey::from_bytes(&key).sign(msg.as_bytes()).to_bytes().to_vec())
}

// ---- recovery secret ----

const B32: &[u8; 32] = b"ABCDEFGHJKMNPQRSTVWXYZ0123456789";

/// 256-bit secret shown to the user as groups of four characters.
pub fn recovery_encode(secret: &[u8; 32]) -> String {
    let mut bits = 0u32;
    let mut nbits = 0;
    let mut out = String::new();
    for &b in secret {
        bits = (bits << 8) | b as u32;
        nbits += 8;
        while nbits >= 5 {
            nbits -= 5;
            out.push(B32[((bits >> nbits) & 31) as usize] as char);
        }
    }
    if nbits > 0 {
        out.push(B32[((bits << (5 - nbits)) & 31) as usize] as char);
    }
    out.as_bytes().chunks(4).map(|c| std::str::from_utf8(c).unwrap()).collect::<Vec<_>>().join("-")
}

pub fn recovery_decode(code: &str) -> Result<[u8; 32]> {
    let bad = || Error::Invalid("Неверный код восстановления".into());
    let mut bits = 0u64;
    let mut nbits = 0;
    let mut out = Vec::with_capacity(32);
    for c in code.chars().filter(|c| c.is_ascii_alphanumeric()) {
        let c = c.to_ascii_uppercase();
        let c = match c {
            'O' => '0',
            'I' | 'L' => '1',
            'U' => 'V',
            c => c,
        };
        let v = B32.iter().position(|&x| x as char == c).ok_or_else(bad)? as u64;
        bits = (bits << 5) | v;
        nbits += 5;
        if nbits >= 8 {
            nbits -= 8;
            out.push((bits >> nbits) as u8);
            bits &= (1 << nbits) - 1;
        }
    }
    out.try_into().map_err(|_| bad())
}

// ---- roster ----

#[derive(Clone, Debug, PartialEq)]
pub struct RosterDevice {
    pub device_id: String,
    pub identity_key: Vec<u8>,
    pub auth_key: Vec<u8>,
}

#[derive(Clone, Debug)]
pub struct Roster {
    pub username: String,
    pub epoch: u64,
    pub revision: u64,
    pub prev_hash: [u8; 32],
    pub account_key: Vec<u8>,
    pub devices: Vec<RosterDevice>,
}

impl Roster {
    pub fn encode(&self) -> Result<Vec<u8>> {
        let mut devices = self.devices.clone();
        devices.sort_by(|a, b| a.device_id.cmp(&b.device_id));
        let mut out = ROSTER_DOMAIN.to_vec();
        let name = self.username.as_bytes();
        out.push(name.len() as u8);
        out.extend_from_slice(name);
        out.extend_from_slice(&self.epoch.to_be_bytes());
        out.extend_from_slice(&self.revision.to_be_bytes());
        out.extend_from_slice(&self.prev_hash);
        out.extend_from_slice(&self.account_key);
        out.extend_from_slice(&(devices.len() as u16).to_be_bytes());
        for d in &devices {
            if d.identity_key.len() != 33 || d.auth_key.len() != 32 {
                return Err(Error::Crypto("Некорректный ключ устройства".into()));
            }
            out.extend_from_slice(&uuid_bytes(&d.device_id)?);
            out.extend_from_slice(&d.identity_key);
            out.extend_from_slice(&d.auth_key);
        }
        Ok(out)
    }

    pub fn decode(b: &[u8]) -> Result<Roster> {
        let bad = || Error::Crypto("Некорректный список устройств".into());
        let mut r = Reader { b, i: 0 };
        if r.take(ROSTER_DOMAIN.len()).ok_or_else(bad)? != ROSTER_DOMAIN {
            return Err(bad());
        }
        let nlen = r.take(1).ok_or_else(bad)?[0] as usize;
        let username = String::from_utf8(r.take(nlen).ok_or_else(bad)?.to_vec()).map_err(|_| bad())?;
        let epoch = r.u64().ok_or_else(bad)?;
        let revision = r.u64().ok_or_else(bad)?;
        let prev_hash: [u8; 32] = r.take(32).ok_or_else(bad)?.try_into().unwrap();
        let account_key = r.take(33).ok_or_else(bad)?.to_vec();
        let n = r.u16().ok_or_else(bad)? as usize;
        let mut devices = Vec::with_capacity(n);
        for _ in 0..n {
            devices.push(RosterDevice {
                device_id: uuid_str(r.take(16).ok_or_else(bad)?),
                identity_key: r.take(33).ok_or_else(bad)?.to_vec(),
                auth_key: r.take(32).ok_or_else(bad)?.to_vec(),
            });
        }
        if r.i != b.len() {
            return Err(bad());
        }
        Ok(Roster { username, epoch, revision, prev_hash, account_key, devices })
    }

    /// Signs with the account identity; returns (blob, signature).
    pub fn sign(&self, account: &IdentityKeyPair) -> Result<(Vec<u8>, Vec<u8>)> {
        let blob = self.encode()?;
        let sig = account.private_key().calculate_signature(&blob, &mut rand::rng()).map_err(crypto)?;
        Ok((blob, sig.to_vec()))
    }
}

/// Verifies a roster against an expected account key. Returns the parsed roster.
pub fn verify_roster(account_key: &[u8], blob: &[u8], sig: &[u8]) -> Result<Roster> {
    let key = IdentityKey::decode(account_key)?;
    if !key.public_key().verify_signature(blob, sig) {
        return Err(Error::Crypto("Подпись списка устройств не прошла проверку".into()));
    }
    let r = Roster::decode(blob)?;
    if r.account_key != account_key {
        return Err(Error::Crypto("Список устройств подписан другим ключом".into()));
    }
    Ok(r)
}

// ---- group state ----

#[derive(Clone, Debug)]
pub struct GroupState {
    pub group_id: String,
    pub revision: u64,
    pub prev_hash: [u8; 32],
    pub owner_id: String,
    pub members: Vec<String>,
    /// Who signed this state: the owner or an admin of the previous state.
    pub signer_id: String,
    pub channel: bool,
    pub admins: Vec<String>,
}

fn sorted(v: &[String]) -> Vec<String> {
    let mut v = v.to_vec();
    v.sort();
    v.dedup();
    v
}

impl GroupState {
    pub fn encode(&self) -> Result<Vec<u8>> {
        let members = sorted(&self.members);
        let admins = sorted(&self.admins);
        let mut out = GROUP_DOMAIN_V2.to_vec();
        out.extend_from_slice(&uuid_bytes(&self.group_id)?);
        out.extend_from_slice(&self.revision.to_be_bytes());
        out.extend_from_slice(&self.prev_hash);
        out.extend_from_slice(&uuid_bytes(&self.owner_id)?);
        out.extend_from_slice(&uuid_bytes(&self.signer_id)?);
        out.push(self.channel as u8);
        out.extend_from_slice(&(members.len() as u16).to_be_bytes());
        for m in &members {
            out.extend_from_slice(&uuid_bytes(m)?);
        }
        out.extend_from_slice(&(admins.len() as u16).to_be_bytes());
        for a in &admins {
            out.extend_from_slice(&uuid_bytes(a)?);
        }
        Ok(out)
    }

    pub fn decode(b: &[u8]) -> Result<GroupState> {
        let bad = || Error::Crypto("Некорректное состояние группы".into());
        let mut r = Reader { b, i: 0 };
        let v2 = match r.take(GROUP_DOMAIN.len()).ok_or_else(bad)? {
            d if d == GROUP_DOMAIN => false,
            d if d == GROUP_DOMAIN_V2 => true,
            _ => return Err(bad()),
        };
        let group_id = uuid_str(r.take(16).ok_or_else(bad)?);
        let revision = r.u64().ok_or_else(bad)?;
        let prev_hash: [u8; 32] = r.take(32).ok_or_else(bad)?.try_into().unwrap();
        let owner_id = uuid_str(r.take(16).ok_or_else(bad)?);
        let (signer_id, channel) = if v2 {
            let signer = uuid_str(r.take(16).ok_or_else(bad)?);
            let kind = r.take(1).ok_or_else(bad)?[0];
            if kind > 1 {
                return Err(bad());
            }
            (signer, kind == 1)
        } else {
            (owner_id.clone(), false)
        };
        let mut list = |r: &mut Reader| -> Result<Vec<String>> {
            let n = r.u16().ok_or_else(bad)? as usize;
            (0..n).map(|_| Ok(uuid_str(r.take(16).ok_or_else(bad)?))).collect()
        };
        let members = list(&mut r)?;
        let admins = if v2 { list(&mut r)? } else { Vec::new() };
        if r.i != b.len() {
            return Err(bad());
        }
        Ok(GroupState { group_id, revision, prev_hash, owner_id, members, signer_id, channel, admins })
    }
}

pub fn sign_blob(account: &IdentityKeyPair, blob: &[u8]) -> Result<Vec<u8>> {
    Ok(account.private_key().calculate_signature(blob, &mut rand::rng()).map_err(crypto)?.to_vec())
}

pub fn verify_blob(account_key: &[u8], blob: &[u8], sig: &[u8]) -> Result<()> {
    let key = IdentityKey::decode(account_key)?;
    if key.public_key().verify_signature(blob, sig) { Ok(()) } else { Err(Error::Crypto("Подпись не прошла проверку".into())) }
}

/// A member's own leave statement, signed by one of its roster devices.
pub fn leave_blob(group_id: &str, user_id: &str, device_id: &str, prev_revision: u64) -> Result<Vec<u8>> {
    let mut out = LEAVE_DOMAIN.to_vec();
    out.extend_from_slice(&uuid_bytes(group_id)?);
    out.extend_from_slice(&uuid_bytes(user_id)?);
    out.extend_from_slice(&uuid_bytes(device_id)?);
    out.extend_from_slice(&prev_revision.to_be_bytes());
    Ok(out)
}

/// Returns (group_id, user_id, device_id, prev_revision).
pub fn parse_leave(b: &[u8]) -> Result<(String, String, String, u64)> {
    let bad = || Error::Crypto("Некорректное заявление о выходе".into());
    let mut r = Reader { b, i: 0 };
    if r.take(LEAVE_DOMAIN.len()).ok_or_else(bad)? != LEAVE_DOMAIN {
        return Err(bad());
    }
    let g = uuid_str(r.take(16).ok_or_else(bad)?);
    let u = uuid_str(r.take(16).ok_or_else(bad)?);
    let d = uuid_str(r.take(16).ok_or_else(bad)?);
    let rev = r.u64().ok_or_else(bad)?;
    if r.i != b.len() {
        return Err(bad());
    }
    Ok((g, u, d, rev))
}

struct Reader<'a> {
    b: &'a [u8],
    i: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Option<&'a [u8]> {
        let s = self.b.get(self.i..self.i + n)?;
        self.i += n;
        Some(s)
    }
    fn u64(&mut self) -> Option<u64> {
        Some(u64::from_be_bytes(self.take(8)?.try_into().ok()?))
    }
    fn u16(&mut self) -> Option<u16> {
        Some(u16::from_be_bytes(self.take(2)?.try_into().ok()?))
    }
}

/// Safety number over both account identities (libsignal displayable fingerprint).
pub fn safety_number(my_id: &str, my_key: &[u8], their_id: &str, their_key: &[u8]) -> Result<String> {
    let fp = libsignal_protocol::Fingerprint::new(
        2,
        5200,
        my_id.as_bytes(),
        &IdentityKey::decode(my_key)?,
        their_id.as_bytes(),
        &IdentityKey::decode(their_key)?,
    )
    .map_err(|_| Error::Crypto("Не удалось вычислить код безопасности".into()))?;
    let digits = fp.display_string().map_err(|_| Error::Crypto("Не удалось вычислить код безопасности".into()))?;
    // 12 groups of 5 digits, the form people compare aloud.
    Ok(digits.as_bytes().chunks(5).map(|c| String::from_utf8_lossy(c).into_owned()).collect::<Vec<_>>().join(" "))
}

pub fn account_keypair(conn: &rusqlite::Connection) -> Result<Option<IdentityKeyPair>> {
    match kv_get(conn, "account_identity")? {
        Some(b) => Ok(Some(IdentityKeyPair::try_from(b.as_slice())?)),
        None => Ok(None),
    }
}

pub fn my_user_id(conn: &rusqlite::Connection) -> Result<String> {
    kv_get_str(conn, "user_id")?.ok_or(Error::NotLoggedIn)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recovery_roundtrip() {
        let s = random_bytes::<32>();
        let code = recovery_encode(&s);
        assert_eq!(recovery_decode(&code).unwrap(), s);
        assert_eq!(recovery_decode(&code.to_lowercase().replace('-', " ")).unwrap(), s);
    }

    #[test]
    fn roster_sign_verify_and_tamper() {
        let account = IdentityKeyPair::generate(&mut rand::rng());
        let d = new_device().unwrap();
        let r = Roster {
            username: "alice".into(),
            epoch: 1,
            revision: 1,
            prev_hash: [0; 32],
            account_key: account.identity_key().serialize().to_vec(),
            devices: vec![d.roster_entry()],
        };
        let (blob, sig) = r.sign(&account).unwrap();
        let back = verify_roster(&account.identity_key().serialize(), &blob, &sig).unwrap();
        assert_eq!(back.devices, r.devices);
        let mut bad = blob.clone();
        let n = bad.len();
        bad[n - 1] ^= 1;
        assert!(verify_roster(&account.identity_key().serialize(), &bad, &sig).is_err());
        let other = IdentityKeyPair::generate(&mut rand::rng());
        assert!(verify_roster(&other.identity_key().serialize(), &blob, &sig).is_err());
    }

    #[test]
    fn group_state_is_order_independent() {
        let g = Uuid::new_v4().to_string();
        let o = Uuid::new_v4().to_string();
        let a = Uuid::new_v4().to_string();
        let b = Uuid::new_v4().to_string();
        let s1 = GroupState {
            group_id: g.clone(),
            revision: 3,
            prev_hash: [1; 32],
            owner_id: o.clone(),
            members: vec![a.clone(), b.clone()],
            signer_id: a.clone(),
            channel: true,
            admins: vec![a.clone()],
        };
        let s2 = GroupState { members: vec![b, a.clone()], ..s1.clone() };
        assert_eq!(s1.encode().unwrap(), s2.encode().unwrap());
        let back = GroupState::decode(&s1.encode().unwrap()).unwrap();
        assert_eq!(back.revision, 3);
        assert_eq!(back.owner_id, o);
        assert_eq!(back.signer_id, a);
        assert!(back.channel);
        assert_eq!(back.admins, vec![a]);
    }
}
