
use crate::proto_wire::{Reader, WireType, Writer};

/// Proto2 `[default = 2]` on `CMsgClientRequestFreeLicenseResponse.eresult` — EResult.Fail.
/// An omitted field means the grant did NOT happen.
pub const FREE_LICENSE_DEFAULT_ERESULT: i32 = 2;

/// `CMsgClientRequestFreeLicense` (EMsg 5572): `repeated uint32 appids = 2;`
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientRequestFreeLicense {
    pub appids: Vec<u32>,
}

impl CMsgClientRequestFreeLicense {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        for appid in &self.appids {
            // Field 2 (not 1). Force-emit so an appid of 0 still occupies a slot rather than
            // silently shortening the request.
            w.uint32_field_force(2, *appid);
        }
        out
    }
}

/// `CMsgClientRequestFreeLicenseResponse` (EMsg 5573):
/// `eresult = 1 [default = 2]`, `granted_packageids = 2`, `granted_appids = 3`.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CMsgClientRequestFreeLicenseResponse {
    pub eresult: i32,
    pub granted_packageids: Vec<u32>,
    pub granted_appids: Vec<u32>,
}

impl Default for CMsgClientRequestFreeLicenseResponse {
    fn default() -> Self {
        Self {
            eresult: FREE_LICENSE_DEFAULT_ERESULT,
            granted_packageids: Vec::new(),
            granted_appids: Vec::new(),
        }
    }
}

impl CMsgClientRequestFreeLicenseResponse {
    /// True only when Steam reported EResult.OK *and* actually handed something over.
    pub fn is_granted(&self) -> bool {
        self.eresult == 1 && !(self.granted_packageids.is_empty() && self.granted_appids.is_empty())
    }

    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match (tag.field_number, tag.wire_type) {
                (1, WireType::Varint) => msg.eresult = reader.i32()?,
                // Repeated uint32 is unpacked in proto2, but accept a packed block too so a
                // server-side encoding change cannot silently empty the grant list.
                (2, WireType::Varint) => msg.granted_packageids.push(reader.u32()?),
                (2, WireType::LengthDelimited) => {
                    read_packed_u32(reader.bytes()?, &mut msg.granted_packageids)?
                }
                (3, WireType::Varint) => msg.granted_appids.push(reader.u32()?),
                (3, WireType::LengthDelimited) => {
                    read_packed_u32(reader.bytes()?, &mut msg.granted_appids)?
                }
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

/// Drain a packed repeated-varint block into `out`.
fn read_packed_u32(block: &[u8], out: &mut Vec<u32>) -> Option<()> {
    let mut reader = Reader::new(block);
    while !reader.eof() {
        out.push(reader.u32()?);
    }
    Some(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn request_puts_appids_on_field_two() {
        let body = CMsgClientRequestFreeLicense {
            appids: vec![440, 570],
        }
        .serialize();
        // tag = (2 << 3) | 0 = 0x10. Field 1 (0x08) would be the wrong-by-one guess.
        assert_eq!(body[0], 0x10);
        let mut expected = Vec::new();
        {
            let mut w = Writer::new(&mut expected);
            w.uint32_field_force(2, 440);
            w.uint32_field_force(2, 570);
        }
        assert_eq!(body, expected);
    }

    #[test]
    fn absent_eresult_defaults_to_fail_not_success() {
        // Proto2 [default = 2]: an empty body must NEVER read as granted.
        let parsed = CMsgClientRequestFreeLicenseResponse::deserialize(&[]).unwrap();
        assert_eq!(parsed.eresult, FREE_LICENSE_DEFAULT_ERESULT);
        assert_eq!(parsed.eresult, 2);
        assert!(!parsed.is_granted());
    }

    #[test]
    fn parses_unpacked_grant_response() {
        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.int32_field(1, 1);
            w.uint32_field_force(2, 303_386);
            w.uint32_field_force(3, 440);
        }
        let parsed = CMsgClientRequestFreeLicenseResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.eresult, 1);
        assert_eq!(parsed.granted_packageids, vec![303_386]);
        assert_eq!(parsed.granted_appids, vec![440]);
        assert!(parsed.is_granted());
    }

    #[test]
    fn parses_packed_grant_response() {
        let mut packed_packages = Vec::new();
        {
            let mut w = Writer::new(&mut packed_packages);
            w.varint(100);
            w.varint(300);
        }
        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.int32_field(1, 1);
            w.submessage_field(2, &packed_packages);
        }
        let parsed = CMsgClientRequestFreeLicenseResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.granted_packageids, vec![100, 300]);
    }

    #[test]
    fn ok_with_no_grant_is_not_a_grant() {
        // eresult OK but nothing handed over: must not be reported as success.
        let mut body = Vec::new();
        Writer::new(&mut body).int32_field(1, 1);
        let parsed = CMsgClientRequestFreeLicenseResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.eresult, 1);
        assert!(!parsed.is_granted());
    }

    #[test]
    fn failure_eresult_is_preserved_verbatim() {
        // A paid/unowned title comes back with a non-OK EResult; surface it, don't flatten it.
        for eresult in [2, 8, 15, 16] {
            let mut body = Vec::new();
            Writer::new(&mut body).int32_field(1, eresult);
            let parsed = CMsgClientRequestFreeLicenseResponse::deserialize(&body).unwrap();
            assert_eq!(parsed.eresult, eresult);
            assert!(!parsed.is_granted());
        }
    }
}
