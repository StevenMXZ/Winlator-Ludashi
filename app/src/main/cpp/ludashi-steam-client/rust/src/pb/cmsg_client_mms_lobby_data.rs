use crate::proto_wire::Reader;

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct MMSLobbyDataMember {
    pub steam_id: u64,
    pub persona_name: String,
    pub metadata: Vec<u8>,
}

impl MMSLobbyDataMember {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut r = Reader::new(body);
        let mut m = Self::default();
        while !r.eof() {
            let Some(t) = r.next_tag() else {
                return r.ok().then_some(m);
            };
            match t.field_number {
                1 => m.steam_id = r.fixed64()?,
                2 => m.persona_name = r.string()?,
                3 => m.metadata = r.bytes()?.to_vec(),
                _ => {
                    if !r.skip(t.wire_type) {
                        return None;
                    }
                }
            }
        }
        Some(m)
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientMMSLobbyData {
    pub app_id: u32,
    pub steam_id_lobby: u64,
    pub num_members: i32,
    pub max_members: i32,
    pub lobby_type: i32,
    pub lobby_flags: i32,
    pub steam_id_owner: u64,
    pub metadata: Vec<u8>,
    pub members: Vec<MMSLobbyDataMember>,
}

impl CMsgClientMMSLobbyData {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut r = Reader::new(body);
        let mut m = Self::default();
        while !r.eof() {
            let Some(t) = r.next_tag() else {
                return r.ok().then_some(m);
            };
            match t.field_number {
                1 => m.app_id = r.u32()?,
                2 => m.steam_id_lobby = r.fixed64()?,
                3 => m.num_members = r.u64()? as u32 as i32,
                4 => m.max_members = r.u64()? as u32 as i32,
                5 => m.lobby_type = r.u64()? as u32 as i32,
                6 => m.lobby_flags = r.u64()? as u32 as i32,
                7 => m.steam_id_owner = r.fixed64()?,
                8 => m.metadata = r.bytes()?.to_vec(),
                9 => m.members.push(MMSLobbyDataMember::deserialize(r.bytes()?)?),
                _ => {
                    if !r.skip(t.wire_type) {
                        return None;
                    }
                }
            }
        }
        Some(m)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto_wire::Writer;

    #[test]
    fn parses_lobby_data_members() {
        let mut member = Vec::new();
        {
            let mut w = Writer::new(&mut member);
            w.fixed64_field(1, 200);
            w.string_field(2, "Ada");
            w.bytes_field(3, b"meta");
        }
        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.uint32_field(1, 480);
            w.fixed64_field(2, 100);
            w.uint32_field(3, 1);
            w.fixed64_field(7, 200);
            w.submessage_field(9, &member);
        }
        let parsed = CMsgClientMMSLobbyData::deserialize(&body).unwrap();
        assert_eq!(parsed.members[0].persona_name, "Ada");
        assert_eq!(parsed.steam_id_owner, 200);
    }
}
