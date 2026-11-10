//! Friend-management client messages (SteamKit `steammessages_clientserver_friends.proto`):
//! add / remove a friend and the friend profile-info lookup used by the friend profile screen.

use crate::proto_wire::{Reader, WireType, Writer};

/// `CMsgClientAddFriend` (EMsg 791): either a SteamID64 or an account name / e-mail.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientAddFriend {
    pub steamid_to_add: u64,
    pub accountname_or_email_to_add: String,
}

impl CMsgClientAddFriend {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.fixed64_field(1, self.steamid_to_add);
        w.string_field(2, &self.accountname_or_email_to_add);
        out
    }
}

/// `CMsgClientAddFriendResponse` (EMsg 792).
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientAddFriendResponse {
    pub eresult: i32,
    pub steam_id_added: u64,
    pub persona_name_added: String,
}

impl CMsgClientAddFriendResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match (tag.field_number, tag.wire_type) {
                (1, WireType::Varint) => msg.eresult = reader.i32()?,
                (2, WireType::Fixed64) => msg.steam_id_added = reader.fixed64()?,
                (3, WireType::LengthDelimited) => msg.persona_name_added = reader.string()?,
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

/// `CMsgClientRemoveFriend` (EMsg 714): also declines a pending incoming invite / cancels an
/// outgoing one (the relationship is removed either way, which is what the Steam client sends).
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientRemoveFriend {
    pub friendid: u64,
}

impl CMsgClientRemoveFriend {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        Writer::new(&mut out).fixed64_field(1, self.friendid);
        out
    }
}

/// `CMsgClientFriendProfileInfo` (EMsg 5330), answered by a job-matched 5331.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientFriendProfileInfo {
    pub steamid_friend: u64,
}

impl CMsgClientFriendProfileInfo {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        Writer::new(&mut out).fixed64_field(1, self.steamid_friend);
        out
    }
}

/// `CMsgClientFriendProfileInfoResponse` (EMsg 5331).
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientFriendProfileInfoResponse {
    pub eresult: i32,
    pub steamid_friend: u64,
    pub time_created: u32,
    pub real_name: String,
    pub city_name: String,
    pub state_name: String,
    pub country_name: String,
    pub headline: String,
    pub summary: String,
}

impl CMsgClientFriendProfileInfoResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match (tag.field_number, tag.wire_type) {
                (1, WireType::Varint) => msg.eresult = reader.i32()?,
                (2, WireType::Fixed64) => msg.steamid_friend = reader.fixed64()?,
                (3, WireType::Varint) => msg.time_created = reader.u32()?,
                (4, WireType::LengthDelimited) => msg.real_name = reader.string()?,
                (5, WireType::LengthDelimited) => msg.city_name = reader.string()?,
                (6, WireType::LengthDelimited) => msg.state_name = reader.string()?,
                (7, WireType::LengthDelimited) => msg.country_name = reader.string()?,
                (8, WireType::LengthDelimited) => msg.headline = reader.string()?,
                (9, WireType::LengthDelimited) => msg.summary = reader.string()?,
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
    fn add_friend_serializes_id_or_name() {
        let by_id = CMsgClientAddFriend {
            steamid_to_add: 76561198000000001,
            accountname_or_email_to_add: String::new(),
        }
        .serialize();
        assert_eq!(by_id[0], 0x09); // field 1, fixed64
        assert_eq!(by_id.len(), 9);

        let by_name = CMsgClientAddFriend {
            steamid_to_add: 0,
            accountname_or_email_to_add: "gaben".into(),
        }
        .serialize();
        assert_eq!(by_name, vec![0x12, 5, b'g', b'a', b'b', b'e', b'n']);
    }

    #[test]
    fn add_friend_response_roundtrip() {
        let mut body = Vec::new();
        let mut w = Writer::new(&mut body);
        w.int32_field(1, 1);
        w.fixed64_field(2, 42);
        w.string_field(3, "ada");
        let resp = CMsgClientAddFriendResponse::deserialize(&body).unwrap();
        assert_eq!(resp.eresult, 1);
        assert_eq!(resp.steam_id_added, 42);
        assert_eq!(resp.persona_name_added, "ada");
    }

    #[test]
    fn profile_info_response_roundtrip() {
        let mut body = Vec::new();
        let mut w = Writer::new(&mut body);
        w.int32_field(1, 1);
        w.fixed64_field(2, 7);
        w.uint32_field(3, 1_400_000_000);
        w.string_field(4, "Ada L.");
        w.string_field(7, "United Kingdom");
        w.string_field(9, "hello");
        let resp = CMsgClientFriendProfileInfoResponse::deserialize(&body).unwrap();
        assert_eq!(resp.steamid_friend, 7);
        assert_eq!(resp.time_created, 1_400_000_000);
        assert_eq!(resp.real_name, "Ada L.");
        assert_eq!(resp.country_name, "United Kingdom");
        assert_eq!(resp.summary, "hello");
        assert!(resp.city_name.is_empty());
    }
}
