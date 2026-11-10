use rand::rngs::OsRng;
use rsa::{BigUint, Pkcs1v15Encrypt, RsaPublicKey};

pub fn rsa_pkcs1v15_encrypt_password_with_hex_key(
    password: &str,
    publickey_mod_hex: &str,
    publickey_exp_hex: &str,
) -> Option<Vec<u8>> {
    let modulus = BigUint::from_bytes_be(&hex_decode(publickey_mod_hex)?);
    let exponent = BigUint::from_bytes_be(&hex_decode(publickey_exp_hex)?);
    let key = RsaPublicKey::new(modulus, exponent).ok()?;
    key.encrypt(&mut OsRng, Pkcs1v15Encrypt, password.as_bytes())
        .ok()
}

fn hex_decode(hex: &str) -> Option<Vec<u8>> {
    if !hex.len().is_multiple_of(2) {
        return None;
    }
    let mut out = Vec::with_capacity(hex.len() / 2);
    let bytes = hex.as_bytes();
    for pair in bytes.chunks_exact(2) {
        let hi = nibble(pair[0])?;
        let lo = nibble(pair[1])?;
        out.push((hi << 4) | lo);
    }
    Some(out)
}

fn nibble(c: u8) -> Option<u8> {
    match c {
        b'0'..=b'9' => Some(c - b'0'),
        b'a'..=b'f' => Some(10 + c - b'a'),
        b'A'..=b'F' => Some(10 + c - b'A'),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rsa::traits::PublicKeyParts;

    #[test]
    fn rejects_bad_hex_keys() {
        assert!(rsa_pkcs1v15_encrypt_password_with_hex_key("pw", "abc", "010001").is_none());
        assert!(rsa_pkcs1v15_encrypt_password_with_hex_key("pw", "zz", "010001").is_none());
    }

    #[test]
    fn encrypts_with_generated_key_material() {
        let private = rsa::RsaPrivateKey::new(&mut OsRng, 1024).unwrap();
        let public = private.to_public_key();
        let mod_hex = hex_encode(&public.n().to_bytes_be());
        let exp_hex = hex_encode(&public.e().to_bytes_be());
        let encrypted =
            rsa_pkcs1v15_encrypt_password_with_hex_key("password", &mod_hex, &exp_hex).unwrap();
        assert_eq!(encrypted.len(), 128);
    }

    fn hex_encode(bytes: &[u8]) -> String {
        const HEX: &[u8; 16] = b"0123456789abcdef";
        let mut out = String::with_capacity(bytes.len() * 2);
        for b in bytes {
            out.push(HEX[(b >> 4) as usize] as char);
            out.push(HEX[(b & 0x0f) as usize] as char);
        }
        out
    }
}
