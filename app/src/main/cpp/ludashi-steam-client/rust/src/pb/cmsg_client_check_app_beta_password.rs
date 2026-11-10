use crate::proto_wire::{Reader, Writer};

/// `CMsgClientCheckAppBetaPassword` (EMsg 5426): ask the CM which password-protected beta branches
/// of `app_id` the given access code unlocks. SteamKit field numbers: app_id = 1, betapassword = 2.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientCheckAppBetaPassword {
    pub app_id: u32,
    pub betapassword: String,
}

impl CMsgClientCheckAppBetaPassword {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.app_id);
        w.string_field(2, &self.betapassword);
        out
    }
}

/// One unlocked branch: its name and the hex-encoded AES-256 key that decrypts the branch's
/// `encryptedmanifests/<branch>/gid` (the key is the "betapassword" in Valve's naming).
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct BetaPassword {
    pub betaname: String,
    pub betapassword: String,
}

/// `CMsgClientCheckAppBetaPasswordResponse` (EMsg 5427): eresult = 1 (default 2 = Fail),
/// betapasswords = 4 (repeated {betaname = 1, betapassword = 2}). Steam returns EVERY branch the
/// code is valid for, so one correct code can unlock several branches at once.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CMsgClientCheckAppBetaPasswordResponse {
    pub eresult: i32,
    pub betapasswords: Vec<BetaPassword>,
}

impl Default for CMsgClientCheckAppBetaPasswordResponse {
    fn default() -> Self {
        Self {
            eresult: 2,
            betapasswords: Vec::new(),
        }
    }
}

impl CMsgClientCheckAppBetaPasswordResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.eresult = reader.u64()? as u32 as i32,
                4 => msg.betapasswords.push(parse_beta_password(reader.bytes()?)?),
                _ => {
                    if !reader.skip(tag.wire_type) {
                        return None;
                    }
                }
            }
        }
        Some(msg)
    }
}

fn parse_beta_password(body: &[u8]) -> Option<BetaPassword> {
    let mut reader = Reader::new(body);
    let mut entry = BetaPassword::default();
    while !reader.eof() {
        let Some(tag) = reader.next_tag() else {
            return reader.ok().then_some(entry);
        };
        match tag.field_number {
            1 => entry.betaname = reader.string()?,
            2 => entry.betapassword = reader.string()?,
            _ => {
                if !reader.skip(tag.wire_type) {
                    return None;
                }
            }
        }
    }
    Some(entry)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto_wire::WireType;

    #[test]
    fn request_uses_steamkit_field_numbers() {
        let body = CMsgClientCheckAppBetaPassword {
            app_id: 480,
            betapassword: "hunter2".into(),
        }
        .serialize();
        let mut reader = Reader::new(&body);
        let t1 = reader.next_tag().unwrap();
        assert_eq!((t1.field_number, t1.wire_type), (1, WireType::Varint));
        assert_eq!(reader.u32().unwrap(), 480);
        let t2 = reader.next_tag().unwrap();
        assert_eq!((t2.field_number, t2.wire_type), (2, WireType::LengthDelimited));
        assert_eq!(reader.string().unwrap(), "hunter2");
        assert!(reader.eof());
    }

    #[test]
    fn response_roundtrips_branches() {
        let mut beta = Vec::new();
        {
            let mut w = Writer::new(&mut beta);
            w.string_field(1, "beta");
            w.string_field(2, "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");
        }
        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.int32_field(1, 1);
            w.submessage_field(4, &beta);
        }
        let parsed = CMsgClientCheckAppBetaPasswordResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.eresult, 1);
        assert_eq!(parsed.betapasswords.len(), 1);
        assert_eq!(parsed.betapasswords[0].betaname, "beta");
        assert_eq!(parsed.betapasswords[0].betapassword.len(), 64);
        // Empty body → SteamKit default (Fail), no branches.
        let empty = CMsgClientCheckAppBetaPasswordResponse::deserialize(&[]).unwrap();
        assert_eq!(empty.eresult, 2);
        assert!(empty.betapasswords.is_empty());
    }
}
