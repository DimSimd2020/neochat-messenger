//! Streaming attachment encryption: XChaCha20-Poly1305 in the STREAM construction
//! (big-endian 32-bit counter, last-block flag) from the RustCrypto `aead` crate.
//! Truncation, reordering and modification of chunks fail authentication.

use std::fs::File;
use std::io::{BufReader, BufWriter, Read, Write};
use std::path::Path;

use chacha20poly1305::aead::stream::{DecryptorBE32, EncryptorBE32};
use chacha20poly1305::{KeyInit, XChaCha20Poly1305};
use sha2::{Digest, Sha256};

use crate::error::{Error, Result};
use crate::keys::random_bytes;

pub const CHUNK: usize = 64 * 1024;
const TAG: usize = 16;
pub const MAX_PLAINTEXT: u64 = 25 << 20;

pub struct Encrypted {
    pub key: [u8; 32],
    pub nonce: [u8; 19],
    pub ciphertext_size: u64,
    pub ciphertext_sha256: [u8; 32],
    pub plaintext_size: u64,
}

fn read_full(r: &mut impl Read, buf: &mut [u8]) -> std::io::Result<usize> {
    let mut n = 0;
    while n < buf.len() {
        match r.read(&mut buf[n..])? {
            0 => break,
            k => n += k,
        }
    }
    Ok(n)
}

pub fn encrypt_file(src: &Path, dst: &Path) -> Result<Encrypted> {
    let plaintext_size = std::fs::metadata(src)?.len();
    if plaintext_size > MAX_PLAINTEXT {
        return Err(Error::Invalid("Файл больше 25 МиБ".into()));
    }
    let key = random_bytes::<32>();
    let nonce = random_bytes::<19>();
    let aead = XChaCha20Poly1305::new((&key).into());
    let mut enc = Some(EncryptorBE32::from_aead(aead, (&nonce).into()));
    let mut input = BufReader::new(File::open(src)?);
    let mut out = BufWriter::new(File::create(dst)?);
    let mut hash = Sha256::new();
    let mut total = 0u64;
    let mut cur = vec![0u8; CHUNK];
    let mut next = vec![0u8; CHUNK];
    let mut n = read_full(&mut input, &mut cur)?;
    loop {
        let m = read_full(&mut input, &mut next)?;
        let last = m == 0;
        let fail = |_| Error::Crypto("Ошибка шифрования файла".into());
        let ct = if last {
            enc.take().expect("encryptor").encrypt_last(&cur[..n]).map_err(fail)?
        } else {
            enc.as_mut().expect("encryptor").encrypt_next(&cur[..n]).map_err(fail)?
        };
        hash.update(&ct);
        out.write_all(&ct)?;
        total += ct.len() as u64;
        if last {
            break;
        }
        std::mem::swap(&mut cur, &mut next);
        n = m;
    }
    out.flush()?;
    out.get_ref().sync_all()?;
    Ok(Encrypted { key, nonce, ciphertext_size: total, ciphertext_sha256: hash.finalize().into(), plaintext_size })
}

/// Decrypts to `dst`; on any failure the partial output is removed.
pub fn decrypt_file(src: &Path, dst: &Path, key: &[u8], nonce: &[u8]) -> Result<()> {
    let res = decrypt_inner(src, dst, key, nonce);
    if res.is_err() {
        let _ = std::fs::remove_file(dst);
    }
    res
}

fn decrypt_inner(src: &Path, dst: &Path, key: &[u8], nonce: &[u8]) -> Result<()> {
    let bad = || Error::Crypto("Файл повреждён или изменён".into());
    let key: [u8; 32] = key.try_into().map_err(|_| bad())?;
    let nonce: [u8; 19] = nonce.try_into().map_err(|_| bad())?;
    let aead = XChaCha20Poly1305::new((&key).into());
    let mut dec = Some(DecryptorBE32::from_aead(aead, (&nonce).into()));
    let mut input = BufReader::new(File::open(src)?);
    let mut out = BufWriter::new(File::create(dst)?);
    let mut cur = vec![0u8; CHUNK + TAG];
    let mut next = vec![0u8; CHUNK + TAG];
    let mut n = read_full(&mut input, &mut cur)?;
    if n < TAG {
        return Err(bad());
    }
    loop {
        let m = read_full(&mut input, &mut next)?;
        if m == 0 {
            let pt = dec.take().expect("decryptor").decrypt_last(&cur[..n]).map_err(|_| bad())?;
            out.write_all(&pt)?;
            break;
        }
        if n != CHUNK + TAG {
            return Err(bad());
        }
        let pt = dec.as_mut().expect("decryptor").decrypt_next(&cur[..n]).map_err(|_| bad())?;
        out.write_all(&pt)?;
        std::mem::swap(&mut cur, &mut next);
        n = m;
    }
    out.flush()?;
    out.get_ref().sync_all()?;
    Ok(())
}

pub fn sha256_file(p: &Path) -> Result<[u8; 32]> {
    let mut f = BufReader::new(File::open(p)?);
    let mut h = Sha256::new();
    let mut buf = vec![0u8; CHUNK];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
    }
    Ok(h.finalize().into())
}

/// Keeps only a safe file name: no separators, no traversal, bounded length.
pub fn safe_name(name: &str) -> String {
    let cleaned: String = name
        .chars()
        .map(|c| if c.is_control() || "/\\:*?\"<>|".contains(c) { '_' } else { c })
        .collect();
    let trimmed = cleaned.trim().trim_start_matches('.').trim();
    let mut s: String = trimmed.chars().take(120).collect();
    if s.is_empty() {
        s = "file".into();
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    fn roundtrip(size: usize) {
        let dir = std::env::temp_dir().join(format!("nc-files-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let src = dir.join("a");
        let data: Vec<u8> = (0..size).map(|i| (i * 31 % 251) as u8).collect();
        std::fs::write(&src, &data).unwrap();
        let enc = encrypt_file(&src, &dir.join("a.enc")).unwrap();
        assert_eq!(sha256_file(&dir.join("a.enc")).unwrap(), enc.ciphertext_sha256);
        decrypt_file(&dir.join("a.enc"), &dir.join("a.out"), &enc.key, &enc.nonce).unwrap();
        assert_eq!(std::fs::read(dir.join("a.out")).unwrap(), data);

        // Truncating one whole chunk or a byte must fail, never yield a shorter file.
        let ct = std::fs::read(dir.join("a.enc")).unwrap();
        if ct.len() > CHUNK + TAG {
            std::fs::write(dir.join("t.enc"), &ct[..CHUNK + TAG]).unwrap();
            assert!(decrypt_file(&dir.join("t.enc"), &dir.join("t.out"), &enc.key, &enc.nonce).is_err());
            assert!(!dir.join("t.out").exists());
        }
        let mut flipped = ct.clone();
        flipped[0] ^= 1;
        std::fs::write(dir.join("f.enc"), &flipped).unwrap();
        assert!(decrypt_file(&dir.join("f.enc"), &dir.join("f.out"), &enc.key, &enc.nonce).is_err());
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn file_roundtrip_and_tamper() {
        for size in [0, 1, CHUNK - 1, CHUNK, CHUNK + 1, 3 * CHUNK + 17] {
            roundtrip(size);
        }
    }

    #[test]
    fn names_are_sanitized() {
        assert_eq!(safe_name("../../etc/passwd"), "_.._etc_passwd");
        assert_eq!(safe_name("..\\x.exe"), "_x.exe");
        assert_eq!(safe_name(""), "file");
    }
}
