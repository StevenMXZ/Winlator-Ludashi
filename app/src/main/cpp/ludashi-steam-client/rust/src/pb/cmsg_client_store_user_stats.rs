use crate::proto_wire::{Reader, Writer};

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct Stat {
    pub stat_id: u32,
    pub stat_value: u32,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientStoreUserStats2 {
    pub game_id: u64,
    pub settor_steam_id: u64,
    pub settee_steam_id: u64,
    pub crc_stats: u32,
    pub stats: Vec<Stat>,
}

impl CMsgClientStoreUserStats2 {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.fixed64_field(1, self.game_id);
        w.fixed64_field(2, self.settor_steam_id);
        w.fixed64_field(3, self.settee_steam_id);
        w.uint32_field_force(4, self.crc_stats);
        for stat in &self.stats {
            let mut sub = Vec::new();
            let mut sw = Writer::new(&mut sub);
            sw.uint32_field_force(1, stat.stat_id);
            sw.uint32_field_force(2, stat.stat_value);
            w.submessage_field(6, &sub);
        }
        out
    }
}

/// `CMsgClientStoreUserStatsResponse` (EMsg 5467): the CM's verdict on a `StoreUserStats2`.
/// `eresult` defaults to 2 (Fail) like the SteamKit definition; `stats_failed_to_set` lists the
/// stat ids the server refused (a stale `crc_stats`, a protected stat, …).
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CMsgClientStoreUserStatsResponse {
    pub game_id: u64,
    pub eresult: i32,
    pub crc_stats: u32,
    pub stats_failed_to_set: Vec<Stat>,
}

impl Default for CMsgClientStoreUserStatsResponse {
    fn default() -> Self {
        Self {
            game_id: 0,
            eresult: 2,
            crc_stats: 0,
            stats_failed_to_set: Vec::new(),
        }
    }
}

impl CMsgClientStoreUserStatsResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.game_id = reader.fixed64()?,
                2 => msg.eresult = reader.u64()? as u32 as i32,
                3 => msg.crc_stats = reader.u32()?,
                4 => msg.stats_failed_to_set.push(parse_failed_stat(reader.bytes()?)?),
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

fn parse_failed_stat(body: &[u8]) -> Option<Stat> {
    let mut reader = Reader::new(body);
    let mut stat = Stat::default();
    while !reader.eof() {
        let Some(tag) = reader.next_tag() else {
            return reader.ok().then_some(stat);
        };
        match tag.field_number {
            1 => stat.stat_id = reader.u32()?,
            2 => stat.stat_value = reader.u32()?,
            _ => {
                if !reader.skip(tag.wire_type) {
                    return None;
                }
            }
        }
    }
    Some(stat)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto_wire::WireType;

    #[test]
    fn parses_store_response_with_failed_stats() {
        let mut failed = Vec::new();
        {
            let mut w = Writer::new(&mut failed);
            w.uint32_field(1, 7);
            w.uint32_field(2, 99);
        }
        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.fixed64_field(1, 440);
            w.int32_field(2, 1);
            w.uint32_field(3, 0xdead);
            w.submessage_field(4, &failed);
        }
        let parsed = CMsgClientStoreUserStatsResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.game_id, 440);
        assert_eq!(parsed.eresult, 1);
        assert_eq!(parsed.crc_stats, 0xdead);
        assert_eq!(parsed.stats_failed_to_set, vec![Stat { stat_id: 7, stat_value: 99 }]);
        // An empty body keeps the SteamKit default (Fail).
        assert_eq!(CMsgClientStoreUserStatsResponse::deserialize(&[]).unwrap().eresult, 2);
    }

    #[test]
    fn store_request_roundtrips_through_response_stat_parser() {
        // The request's `stats` submessage (field 6) has the same {stat_id=1, stat_value=2} shape
        // the response echoes back in `stats_failed_to_set` (field 4).
        let msg = CMsgClientStoreUserStats2 {
            game_id: 220,
            settor_steam_id: 1,
            settee_steam_id: 1,
            crc_stats: 5,
            stats: vec![Stat { stat_id: 3, stat_value: 0x10 }],
        };
        let body = msg.serialize();
        let mut reader = Reader::new(&body);
        let mut parsed = None;
        while !reader.eof() {
            let tag = reader.next_tag().unwrap();
            if tag.field_number == 6 {
                parsed = parse_failed_stat(reader.bytes().unwrap());
            } else {
                assert!(reader.skip(tag.wire_type));
            }
        }
        assert_eq!(parsed, Some(Stat { stat_id: 3, stat_value: 0x10 }));
    }

    #[test]
    fn keeps_zero_crc_and_zero_stat_values_present() {
        let msg = CMsgClientStoreUserStats2 {
            game_id: 7,
            settor_steam_id: 8,
            settee_steam_id: 9,
            crc_stats: 0,
            stats: vec![Stat {
                stat_id: 0,
                stat_value: 0,
            }],
        };
        let body = msg.serialize();
        let mut reader = Reader::new(&body);
        let mut saw_crc = false;
        let mut saw_stat = false;
        while !reader.eof() {
            let tag = reader.next_tag().unwrap();
            match (tag.field_number, tag.wire_type) {
                (4, WireType::Varint) => {
                    assert_eq!(reader.u32().unwrap(), 0);
                    saw_crc = true;
                }
                (6, WireType::LengthDelimited) => {
                    let sub = reader.bytes().unwrap();
                    assert_eq!(sub, &[8, 0, 16, 0]);
                    saw_stat = true;
                }
                _ => assert!(reader.skip(tag.wire_type)),
            }
        }
        assert!(saw_crc);
        assert!(saw_stat);
    }
}
