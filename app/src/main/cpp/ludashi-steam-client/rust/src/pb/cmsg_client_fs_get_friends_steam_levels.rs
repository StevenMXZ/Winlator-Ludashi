
use crate::proto_wire::{Reader, WireType, Writer};

/// `CMsgClientFSGetFriendsSteamLevels` (EMsg 7528): `repeated uint32 accountids = 1;`
/// (proto2, unpacked on the wire).
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientFSGetFriendsSteamLevels {
    pub accountids: Vec<u32>,
}

impl CMsgClientFSGetFriendsSteamLevels {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        for accountid in &self.accountids {
            // A zero account id is not addressable, but force-emit so the request length still
            // matches the caller's id count rather than silently dropping a slot.
            w.uint32_field_force(1, *accountid);
        }
        out
    }
}

/// One `CMsgClientFSGetFriendsSteamLevelsResponse.Friend`.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientFSGetFriendsSteamLevelsFriend {
    pub accountid: u32,
    pub level: u32,
}

impl CMsgClientFSGetFriendsSteamLevelsFriend {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match (tag.field_number, tag.wire_type) {
                (1, WireType::Varint) => msg.accountid = reader.u32()?,
                (2, WireType::Varint) => msg.level = reader.u32()?,
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

/// `CMsgClientFSGetFriendsSteamLevelsResponse` (EMsg 7529).
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientFSGetFriendsSteamLevelsResponse {
    pub friends: Vec<CMsgClientFSGetFriendsSteamLevelsFriend>,
}

impl CMsgClientFSGetFriendsSteamLevelsResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match (tag.field_number, tag.wire_type) {
                (1, WireType::LengthDelimited) => msg.friends.push(
                    CMsgClientFSGetFriendsSteamLevelsFriend::deserialize(reader.bytes()?)?,
                ),
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
    fn request_emits_one_unpacked_field_per_account_id() {
        let body = CMsgClientFSGetFriendsSteamLevels {
            accountids: vec![1, 300, 0],
        }
        .serialize();
        // tag(1,varint)=0x08 for every element, 300 -> two-byte varint, 0 still emitted.
        assert_eq!(body, [0x08, 1, 0x08, 0xac, 0x02, 0x08, 0]);
    }

    #[test]
    fn parses_friend_levels_response() {
        let mut friend = Vec::new();
        {
            let mut w = Writer::new(&mut friend);
            w.uint32_field(1, 12345);
            w.uint32_field(2, 42);
        }
        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.submessage_field(1, &friend);
            w.submessage_field(1, &friend);
        }
        let parsed = CMsgClientFSGetFriendsSteamLevelsResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.friends.len(), 2);
        assert_eq!(parsed.friends[0].accountid, 12345);
        assert_eq!(parsed.friends[0].level, 42);
    }

    #[test]
    fn level_zero_survives_the_roundtrip() {
        // A level-0 account omits field 2 entirely; the default must stay 0, not garbage.
        let mut friend = Vec::new();
        Writer::new(&mut friend).uint32_field(1, 7);
        let mut body = Vec::new();
        Writer::new(&mut body).submessage_field(1, &friend);
        let parsed = CMsgClientFSGetFriendsSteamLevelsResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.friends[0].accountid, 7);
        assert_eq!(parsed.friends[0].level, 0);
    }

    #[test]
    fn unknown_fields_are_skipped() {
        let mut friend = Vec::new();
        {
            let mut w = Writer::new(&mut friend);
            w.uint32_field(1, 9);
            w.string_field(99, "future field");
            w.uint32_field(2, 5);
        }
        let mut body = Vec::new();
        Writer::new(&mut body).submessage_field(1, &friend);
        let parsed = CMsgClientFSGetFriendsSteamLevelsResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.friends[0].level, 5);
    }
}
