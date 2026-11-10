use crate::proto_wire::{Reader, Writer};

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CInventoryGetItemDefMetaRequest {
    pub appid: u32,
}

impl CInventoryGetItemDefMetaRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        Writer::new(&mut out).uint32_field(1, self.appid);
        out
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CInventoryGetItemDefMetaResponse {
    pub modified: u32,
    pub digest: String,
}

impl CInventoryGetItemDefMetaResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.modified = reader.u32()?,
                2 => msg.digest = reader.string()?,
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_item_def_meta_response() {
        let mut body = Vec::new();
        let mut w = Writer::new(&mut body);
        w.uint32_field(1, 12345);
        w.string_field(2, "digest");

        let parsed = CInventoryGetItemDefMetaResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.modified, 12345);
        assert_eq!(parsed.digest, "digest");
    }
}
