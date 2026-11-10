use crate::proto_wire::{Reader, Writer};

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientGetDepotDecryptionKey {
    pub depot_id: u32,
    pub app_id: u32,
}

impl CMsgClientGetDepotDecryptionKey {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.depot_id);
        w.uint32_field(2, self.app_id);
        out
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CMsgClientGetDepotDecryptionKeyResponse {
    pub eresult: u32,
    pub depot_id: u32,
    pub depot_encryption_key: Vec<u8>,
}

impl Default for CMsgClientGetDepotDecryptionKeyResponse {
    fn default() -> Self {
        Self {
            eresult: 2,
            depot_id: 0,
            depot_encryption_key: Vec::new(),
        }
    }
}

impl CMsgClientGetDepotDecryptionKeyResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.eresult = reader.u32()?,
                2 => msg.depot_id = reader.u32()?,
                3 => msg.depot_encryption_key = reader.bytes()?.to_vec(),
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
