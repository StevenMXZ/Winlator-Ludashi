use crate::proto_wire::{Reader, WireType, Writer};

pub const CHAT_ENTRY_TYPE_TEXT: i32 = 1;
pub const CHAT_ENTRY_TYPE_TYPING: i32 = 2;

#[derive(Clone, Debug, Default)]
pub struct CFriendMessagesSendMessageRequest {
    pub steamid: u64,
    pub chat_entry_type: i32,
    pub message: String,
    pub contains_bbcode: bool,
    pub echo_to_sender: bool,
    pub low_priority: bool,
}

impl CFriendMessagesSendMessageRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.fixed64_field(1, self.steamid);
        w.int32_field(2, self.chat_entry_type);
        w.string_field(3, &self.message);
        if self.contains_bbcode {
            w.bool_field(4, true);
        }
        if self.echo_to_sender {
            w.bool_field(5, true);
        }
        if self.low_priority {
            w.bool_field(6, true);
        }
        out
    }
}

#[derive(Clone, Debug, Default)]
pub struct CFriendMessagesSendMessageResponse {
    pub modified_message: String,
    pub server_timestamp: u32,
    pub ordinal: i32,
    pub message_without_bb_code: String,
}

impl CFriendMessagesSendMessageResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match (tag.field_number, tag.wire_type) {
                (1, WireType::LengthDelimited) => msg.modified_message = reader.string()?,
                (2, WireType::Varint) => msg.server_timestamp = reader.u32()?,
                (3, WireType::Varint) => msg.ordinal = reader.i32()?,
                (4, WireType::LengthDelimited) => {
                    msg.message_without_bb_code = reader.string()?
                }
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

#[derive(Clone, Debug, Default)]
pub struct CFriendMessagesGetRecentMessagesRequest {
    pub steamid1: u64,
    pub steamid2: u64,
    pub count: u32,
    pub most_recent_conversation: bool,
}

impl CFriendMessagesGetRecentMessagesRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.fixed64_field(1, self.steamid1);
        w.fixed64_field(2, self.steamid2);
        w.uint32_field(3, self.count);
        if self.most_recent_conversation {
            w.bool_field(4, true);
        }
        out
    }
}

#[derive(Clone, Debug, Default)]
pub struct FriendMessage {
    pub accountid: u32,
    pub timestamp: u32,
    pub message: String,
    pub ordinal: i32,
}

impl FriendMessage {
    fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match (tag.field_number, tag.wire_type) {
                (1, WireType::Varint) => msg.accountid = reader.u32()?,
                (2, WireType::Varint) => msg.timestamp = reader.u32()?,
                (3, WireType::LengthDelimited) => msg.message = reader.string()?,
                (4, WireType::Varint) => msg.ordinal = reader.i32()?,
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

#[derive(Clone, Debug, Default)]
pub struct CFriendMessagesGetRecentMessagesResponse {
    pub messages: Vec<FriendMessage>,
    pub more_available: bool,
}

impl CFriendMessagesGetRecentMessagesResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match (tag.field_number, tag.wire_type) {
                (1, WireType::LengthDelimited) => {
                    msg.messages.push(FriendMessage::deserialize(reader.bytes()?)?)
                }
                (4, WireType::Varint) => msg.more_available = reader.u32()? != 0,
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

#[derive(Clone, Debug, Default)]
pub struct CFriendMessagesIncomingMessageNotification {
    pub steamid_friend: u64,
    pub chat_entry_type: i32,
    pub message: String,
    pub rtime32_server_timestamp: u32,
    pub ordinal: i32,
    pub local_echo: bool,
    pub message_no_bbcode: String,
}

impl CFriendMessagesIncomingMessageNotification {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match (tag.field_number, tag.wire_type) {
                (1, WireType::Fixed64) => msg.steamid_friend = reader.fixed64()?,
                (2, WireType::Varint) => msg.chat_entry_type = reader.i32()?,
                (4, WireType::LengthDelimited) => msg.message = reader.string()?,
                (5, WireType::Fixed32) => msg.rtime32_server_timestamp = reader.fixed32()?,
                (6, WireType::Varint) => msg.ordinal = reader.i32()?,
                (7, WireType::Varint) => msg.local_echo = reader.u32()? != 0,
                (8, WireType::LengthDelimited) => msg.message_no_bbcode = reader.string()?,
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
    fn send_request_roundtrips_fields() {
        let req = CFriendMessagesSendMessageRequest {
            steamid: 76561198000000000,
            chat_entry_type: CHAT_ENTRY_TYPE_TEXT,
            message: "hello".into(),
            echo_to_sender: true,
            ..Default::default()
        };
        let bytes = req.serialize();
        let mut reader = Reader::new(&bytes);
        let tag = reader.next_tag().unwrap();
        assert_eq!(tag.field_number, 1);
        assert_eq!(reader.fixed64().unwrap(), 76561198000000000);
    }

    #[test]
    fn incoming_notification_parses() {
        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.fixed64_field(1, 123);
            w.int32_field(2, CHAT_ENTRY_TYPE_TEXT);
            w.string_field(4, "hi there");
            w.fixed32_field(5, 1700000000);
            w.int32_field(6, 0);
        }
        let parsed = CFriendMessagesIncomingMessageNotification::deserialize(&body).unwrap();
        assert_eq!(parsed.steamid_friend, 123);
        assert_eq!(parsed.message, "hi there");
        assert_eq!(parsed.rtime32_server_timestamp, 1700000000);
    }

    #[test]
    fn recent_message_parses_ordinal_from_field4_and_skips_reactions() {
        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.uint32_field(1, 42);
            w.uint32_field(2, 1700000000);
            w.string_field(3, "yo");
            w.int32_field(4, 7);
            w.string_field(5, "reactions-submessage");
        }
        let parsed = FriendMessage::deserialize(&body).unwrap();
        assert_eq!(parsed.accountid, 42);
        assert_eq!(parsed.timestamp, 1700000000);
        assert_eq!(parsed.message, "yo");
        assert_eq!(parsed.ordinal, 7);
    }
}
