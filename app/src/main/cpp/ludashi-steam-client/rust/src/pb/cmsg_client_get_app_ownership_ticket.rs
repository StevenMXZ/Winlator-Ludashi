use crate::proto_wire::{Reader, Writer};

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientGetAppOwnershipTicket {
    pub app_id: u32,
}

impl CMsgClientGetAppOwnershipTicket {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        Writer::new(&mut out).uint32_field(1, self.app_id);
        out
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CMsgClientGetAppOwnershipTicketResponse {
    pub eresult: u32,
    pub app_id: u32,
    pub ticket: Vec<u8>,
}

impl Default for CMsgClientGetAppOwnershipTicketResponse {
    fn default() -> Self {
        Self {
            eresult: 2,
            app_id: 0,
            ticket: Vec::new(),
        }
    }
}

impl CMsgClientGetAppOwnershipTicketResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.eresult = reader.u32()?,
                2 => msg.app_id = reader.u32()?,
                3 => msg.ticket = reader.bytes()?.to_vec(),
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
    fn ownership_ticket_roundtrip_fields() {
        let req = CMsgClientGetAppOwnershipTicket { app_id: 480 }.serialize();
        assert_eq!(req, vec![0x08, 0xe0, 0x03]);

        let mut resp = Vec::new();
        let mut w = Writer::new(&mut resp);
        w.uint32_field(1, 1);
        w.uint32_field(2, 480);
        w.bytes_field(3, &[1, 2, 3]);
        let parsed = CMsgClientGetAppOwnershipTicketResponse::deserialize(&resp).unwrap();
        assert_eq!(parsed.eresult, 1);
        assert_eq!(parsed.ticket, vec![1, 2, 3]);
    }
}
