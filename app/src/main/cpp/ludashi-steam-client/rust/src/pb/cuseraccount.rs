//! `UserAccount` unified service — the friend "Quick Invite" link token
//! (`CUserAccount_CreateFriendInviteToken`).

use crate::proto_wire::{Reader, WireType, Writer};

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CUserAccountCreateFriendInviteTokenRequest {
    pub invite_limit: u32,
    pub invite_duration: u32,
    pub invite_note: String,
}

impl CUserAccountCreateFriendInviteTokenRequest {
    /// An all-default request serializes to nothing — Steam then applies its own defaults (a
    /// valid, shareable token), exactly what the desktop client's copy-link does.
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.invite_limit);
        w.uint32_field(2, self.invite_duration);
        w.string_field(3, &self.invite_note);
        out
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CUserAccountCreateFriendInviteTokenResponse {
    pub invite_token: String,
    pub invite_limit: u64,
    pub invite_duration: u32,
    pub time_created: u32,
    pub valid: bool,
}

impl CUserAccountCreateFriendInviteTokenResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match (tag.field_number, tag.wire_type) {
                (1, WireType::LengthDelimited) => msg.invite_token = reader.string()?,
                (2, WireType::Varint) => msg.invite_limit = reader.u64()?,
                (3, WireType::Varint) => msg.invite_duration = reader.u32()?,
                (4, WireType::Fixed32) => msg.time_created = reader.fixed32()?,
                (5, WireType::Varint) => msg.valid = reader.boolean()?,
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
    fn default_request_is_empty() {
        assert!(CUserAccountCreateFriendInviteTokenRequest::default()
            .serialize()
            .is_empty());
    }

    #[test]
    fn response_roundtrip() {
        let mut body = Vec::new();
        let mut w = Writer::new(&mut body);
        w.string_field(1, "abcd-efgh");
        w.uint64_field(2, 1);
        w.bool_field(5, true);
        let resp = CUserAccountCreateFriendInviteTokenResponse::deserialize(&body).unwrap();
        assert_eq!(resp.invite_token, "abcd-efgh");
        assert_eq!(resp.invite_limit, 1);
        assert!(resp.valid);
    }
}
