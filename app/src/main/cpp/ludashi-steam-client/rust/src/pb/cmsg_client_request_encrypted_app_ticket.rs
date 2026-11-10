use crate::proto_wire::{Reader, Writer};

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientRequestEncryptedAppTicket {
    pub app_id: u32,
}

impl CMsgClientRequestEncryptedAppTicket {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        Writer::new(&mut out).uint32_field(1, self.app_id);
        out
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CMsgClientRequestEncryptedAppTicketResponse {
    pub app_id: u32,
    pub eresult: i32,
    pub encrypted_app_ticket: Vec<u8>,
}

impl Default for CMsgClientRequestEncryptedAppTicketResponse {
    fn default() -> Self {
        Self {
            app_id: 0,
            eresult: 2,
            encrypted_app_ticket: Vec::new(),
        }
    }
}

impl CMsgClientRequestEncryptedAppTicketResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.app_id = reader.u32()?,
                2 => msg.eresult = reader.i32()?,
                3 => msg.encrypted_app_ticket = reader.bytes()?.to_vec(),
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
