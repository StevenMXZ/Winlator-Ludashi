use crate::proto_wire::{Reader, WireType};

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct ClientFriendsListEntry {
    pub ulfriendid: u64,
    pub efriendrelationship: u32,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientFriendsList {
    pub bincremental: bool,
    pub friends: Vec<ClientFriendsListEntry>,
    pub max_friend_count: u32,
    pub active_friend_count: u32,
    pub friends_limit_hit: bool,
}

impl CMsgClientFriendsList {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.bincremental = reader.boolean()?,
                2 => msg.friends.push(parse_entry(reader.bytes()?)?),
                3 => msg.max_friend_count = reader.u32()?,
                4 => msg.active_friend_count = reader.u32()?,
                5 => msg.friends_limit_hit = reader.boolean()?,
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

fn parse_entry(body: &[u8]) -> Option<ClientFriendsListEntry> {
    let mut reader = Reader::new(body);
    let mut entry = ClientFriendsListEntry::default();
    while !reader.eof() {
        let Some(tag) = reader.next_tag() else {
            return reader.ok().then_some(entry);
        };
        match tag.field_number {
            1 => {
                if tag.wire_type != WireType::Fixed64 {
                    return None;
                }
                entry.ulfriendid = reader.fixed64()?;
            }
            2 => entry.efriendrelationship = reader.u32()?,
            _ => {
                if !reader.skip(tag.wire_type) {
                    return None;
                }
            }
        }
    }
    Some(entry)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto_wire::Writer;

    #[test]
    fn parses_friends_list_entries() {
        let mut entry = Vec::new();
        {
            let mut w = Writer::new(&mut entry);
            w.fixed64_field(1, 123);
            w.uint32_field(2, 3);
        }

        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.bool_field(1, true);
            w.submessage_field(2, &entry);
            w.uint32_field(3, 250);
            w.uint32_field(4, 1);
            w.bool_field(5, true);
        }

        let parsed = CMsgClientFriendsList::deserialize(&body).unwrap();
        assert!(parsed.bincremental);
        assert_eq!(parsed.friends[0].ulfriendid, 123);
        assert_eq!(parsed.friends[0].efriendrelationship, 3);
        assert_eq!(parsed.max_friend_count, 250);
        assert_eq!(parsed.active_friend_count, 1);
        assert!(parsed.friends_limit_hit);
    }
}
