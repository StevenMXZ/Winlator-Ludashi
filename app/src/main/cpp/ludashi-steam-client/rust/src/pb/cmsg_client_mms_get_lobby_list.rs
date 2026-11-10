use crate::proto_wire::{Reader, WireType, Writer};

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientMMSGetLobbyListFilter {
    pub key: String,
    pub value: String,
    pub comparision: i32,
    pub filter_type: i32,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CMsgClientMMSGetLobbyList {
    pub app_id: u32,
    pub num_lobbies_requested: i32,
    pub cell_id: u32,
    pub filters: Vec<CMsgClientMMSGetLobbyListFilter>,
}

impl Default for CMsgClientMMSGetLobbyList {
    fn default() -> Self {
        Self {
            app_id: 0,
            num_lobbies_requested: 50,
            cell_id: 0,
            filters: Vec::new(),
        }
    }
}

impl CMsgClientMMSGetLobbyList {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.app_id);
        if self.num_lobbies_requested > 0 {
            w.int32_field(3, self.num_lobbies_requested);
        }
        w.uint32_field(4, self.cell_id);
        for filter in &self.filters {
            let mut sub = Vec::new();
            let mut fw = Writer::new(&mut sub);
            fw.string_field(1, &filter.key);
            fw.string_field(2, &filter.value);
            fw.int32_field(3, filter.comparision);
            fw.int32_field(4, filter.filter_type);
            w.submessage_field(6, &sub);
        }
        out
    }
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct MMSLobbyListEntry {
    pub steam_id: u64,
    pub max_members: i32,
    pub lobby_type: i32,
    pub lobby_flags: i32,
    pub metadata: Vec<u8>,
    pub num_members: i32,
    pub distance: f32,
    pub weight: i64,
    pub ping: i32,
}

#[derive(Clone, Debug, PartialEq)]
pub struct CMsgClientMMSGetLobbyListResponse {
    pub app_id: u32,
    pub eresult: i32,
    pub lobbies: Vec<MMSLobbyListEntry>,
}

impl Default for CMsgClientMMSGetLobbyListResponse {
    fn default() -> Self {
        Self {
            app_id: 0,
            eresult: 2,
            lobbies: Vec::new(),
        }
    }
}

impl CMsgClientMMSGetLobbyListResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut r = Reader::new(body);
        let mut m = Self::default();
        while !r.eof() {
            let Some(t) = r.next_tag() else {
                return r.ok().then_some(m);
            };
            match t.field_number {
                1 => m.app_id = r.u32()?,
                3 => m.eresult = r.u64()? as u32 as i32,
                4 => m.lobbies.push(parse_lobby_entry(r.bytes()?)?),
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

fn parse_lobby_entry(body: &[u8]) -> Option<MMSLobbyListEntry> {
    let mut r = Reader::new(body);
    let mut e = MMSLobbyListEntry::default();
    while !r.eof() {
        let Some(t) = r.next_tag() else {
            return r.ok().then_some(e);
        };
        match t.field_number {
            1 => e.steam_id = r.fixed64()?,
            2 => e.max_members = r.u64()? as u32 as i32,
            3 => e.lobby_type = r.u64()? as u32 as i32,
            4 => e.lobby_flags = r.u64()? as u32 as i32,
            5 => e.metadata = r.bytes()?.to_vec(),
            6 => e.num_members = r.u64()? as u32 as i32,
            7 => {
                if t.wire_type != WireType::Fixed32 {
                    return None;
                }
                e.distance = f32::from_bits(r.fixed32()?);
            }
            8 => e.weight = r.u64()? as i64,
            9 => e.ping = r.u64()? as u32 as i32,
            _ => {
                if !r.skip(t.wire_type) {
                    return None;
                }
            }
        }
    }
    Some(e)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_lobby_list_entry_with_float_distance() {
        let mut lobby = Vec::new();
        {
            let mut w = Writer::new(&mut lobby);
            w.fixed64_field(1, 100);
            w.int32_field(2, 4);
            w.int32_field(3, 2);
            w.bytes_field(5, b"kv");
            w.tag(7, WireType::Fixed32);
            w.raw_bytes(&1.5f32.to_bits().to_le_bytes());
            w.int64_field(8, -5);
        }
        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.uint32_field(1, 480);
            w.submessage_field(4, &lobby);
        }
        let parsed = CMsgClientMMSGetLobbyListResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.app_id, 480);
        assert_eq!(parsed.eresult, 2);
        assert_eq!(parsed.lobbies[0].distance, 1.5);
        assert_eq!(parsed.lobbies[0].weight, -5);
    }
}
