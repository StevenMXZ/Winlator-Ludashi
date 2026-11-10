use crate::proto_wire::{Reader, WireType, Writer};

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientRequestFriendData {
    pub persona_state_requested: u32,
    pub friends: Vec<u64>,
}

impl CMsgClientRequestFriendData {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.persona_state_requested);
        for id in &self.friends {
            w.fixed64_field(2, *id);
        }
        out
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct PersonaStateFriend {
    pub friendid: u64,
    pub persona_state: u32,
    pub game_played_app_id: u32,
    pub player_name: String,
    pub avatar_hash: Vec<u8>,
    pub game_name: String,
    pub gameid: u64,
    pub rich_presence: Vec<(String, String)>,
    pub has_persona_state: bool,
    pub has_game: bool,
}

impl PersonaStateFriend {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match (tag.field_number, tag.wire_type) {
                (1, WireType::Fixed64) => msg.friendid = reader.fixed64()?,
                (2, WireType::Varint) => {
                    msg.persona_state = reader.u32()?;
                    msg.has_persona_state = true;
                }
                (3, WireType::Varint) => {
                    msg.game_played_app_id = reader.u32()?;
                    msg.has_game = true;
                }
                (15, WireType::LengthDelimited) => msg.player_name = reader.string()?,
                (25, WireType::LengthDelimited) => msg
                    .rich_presence
                    .push(parse_kv_submessage(reader.bytes()?)?),
                (31, WireType::LengthDelimited) => msg.avatar_hash = reader.bytes()?.to_vec(),
                (55, WireType::LengthDelimited) => msg.game_name = reader.string()?,
                (56, WireType::Fixed64) => msg.gameid = reader.fixed64()?,
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

fn parse_kv_submessage(body: &[u8]) -> Option<(String, String)> {
    let mut reader = Reader::new(body);
    let mut key = String::new();
    let mut value = String::new();
    while !reader.eof() {
        let Some(tag) = reader.next_tag() else {
            return reader.ok().then_some((key, value));
        };
        match tag.field_number {
            1 => key = reader.string()?,
            2 => value = reader.string()?,
            _ => {
                if !reader.skip(tag.wire_type) {
                    return None;
                }
            }
        }
    }
    Some((key, value))
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientPersonaState {
    pub status_flags: u32,
    pub friends: Vec<PersonaStateFriend>,
}

impl CMsgClientPersonaState {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.status_flags = reader.u32()?,
                2 => msg
                    .friends
                    .push(PersonaStateFriend::deserialize(reader.bytes()?)?),
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
    use crate::proto_wire::Writer;

    #[test]
    fn parses_persona_state_friend_with_rich_presence() {
        let mut kv = Vec::new();
        {
            let mut w = Writer::new(&mut kv);
            w.string_field(1, "status");
            w.string_field(2, "Playing");
        }

        let mut friend = Vec::new();
        {
            let mut w = Writer::new(&mut friend);
            w.fixed64_field(1, 123);
            w.uint32_field(2, 1);
            w.uint32_field(3, 440);
            w.string_field(15, "Ada");
            w.submessage_field(25, &kv);
            w.bytes_field(31, &[1, 2, 3]);
            w.string_field(55, "Team Fortress 2");
            w.fixed64_field(56, 440);
        }

        let mut body = Vec::new();
        Writer::new(&mut body).submessage_field(2, &friend);

        let parsed = CMsgClientPersonaState::deserialize(&body).unwrap();
        let friend = &parsed.friends[0];
        assert_eq!(friend.friendid, 123);
        assert_eq!(friend.player_name, "Ada");
        assert_eq!(friend.rich_presence[0], ("status".into(), "Playing".into()));
        assert_eq!(friend.avatar_hash, [1, 2, 3]);
        assert_eq!(friend.game_name, "Team Fortress 2");
        assert!(friend.has_persona_state);
        assert!(friend.has_game);
    }

    #[test]
    fn stateful_push_with_field25_as_fixed64_still_parses() {
        // Live persona pushes carry field 25 as a fixed64, not the rich-presence submessage.
        let mut friend = Vec::new();
        {
            let mut w = Writer::new(&mut friend);
            w.fixed64_field(1, 77);
            w.uint32_field(2, 1);
            w.fixed64_field(25, 0);
            w.string_field(15, "Online Friend");
        }
        let mut body = Vec::new();
        Writer::new(&mut body).submessage_field(2, &friend);

        let parsed = CMsgClientPersonaState::deserialize(&body).unwrap();
        let friend = &parsed.friends[0];
        assert_eq!(friend.friendid, 77);
        assert_eq!(friend.persona_state, 1);
        assert!(friend.has_persona_state);
        assert_eq!(friend.player_name, "Online Friend");
    }
}
