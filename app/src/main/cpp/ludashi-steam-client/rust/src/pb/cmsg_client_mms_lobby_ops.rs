use crate::proto_wire::{Reader, Writer};

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientMMSCreateLobby {
    pub app_id: u32,
    pub max_members: i32,
    pub lobby_type: i32,
    pub lobby_flags: i32,
    pub metadata: Vec<u8>,
    pub persona_name_owner: String,
}

impl CMsgClientMMSCreateLobby {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.app_id);
        w.int32_field(2, self.max_members);
        w.int32_field(3, self.lobby_type);
        w.int32_field(4, self.lobby_flags);
        w.bytes_field(7, &self.metadata);
        w.string_field(8, &self.persona_name_owner);
        out
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CMsgClientMMSCreateLobbyResponse {
    pub app_id: u32,
    pub steam_id_lobby: u64,
    pub eresult: i32,
}

impl Default for CMsgClientMMSCreateLobbyResponse {
    fn default() -> Self {
        Self {
            app_id: 0,
            steam_id_lobby: 0,
            eresult: 2,
        }
    }
}

impl CMsgClientMMSCreateLobbyResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        parse_simple_lobby_response(body)
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientMMSJoinLobby {
    pub app_id: u32,
    pub steam_id_lobby: u64,
    pub persona_name: String,
}

impl CMsgClientMMSJoinLobby {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.app_id);
        w.fixed64_field(2, self.steam_id_lobby);
        w.string_field(3, &self.persona_name);
        out
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientMMSJoinLobbyResponseMember {
    pub steam_id: u64,
    pub persona_name: String,
    pub metadata: Vec<u8>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CMsgClientMMSJoinLobbyResponse {
    pub app_id: u32,
    pub steam_id_lobby: u64,
    pub chat_room_enter_response: i32,
    pub max_members: i32,
    pub lobby_type: i32,
    pub lobby_flags: i32,
    pub steam_id_owner: u64,
    pub metadata: Vec<u8>,
    pub members: Vec<CMsgClientMMSJoinLobbyResponseMember>,
}

impl Default for CMsgClientMMSJoinLobbyResponse {
    fn default() -> Self {
        Self {
            app_id: 0,
            steam_id_lobby: 0,
            chat_room_enter_response: 2,
            max_members: 0,
            lobby_type: 0,
            lobby_flags: 0,
            steam_id_owner: 0,
            metadata: Vec::new(),
            members: Vec::new(),
        }
    }
}

impl CMsgClientMMSJoinLobbyResponse {
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
                3 => m.chat_room_enter_response = r.u64()? as u32 as i32,
                4 => m.max_members = r.u64()? as u32 as i32,
                5 => m.lobby_type = r.u64()? as u32 as i32,
                6 => m.lobby_flags = r.u64()? as u32 as i32,
                7 => m.steam_id_owner = r.fixed64()?,
                8 => m.metadata = r.bytes()?.to_vec(),
                9 => m.members.push(parse_join_member(r.bytes()?)?),
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

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientMMSLeaveLobby {
    pub app_id: u32,
    pub steam_id_lobby: u64,
}

impl CMsgClientMMSLeaveLobby {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.app_id);
        w.fixed64_field(2, self.steam_id_lobby);
        out
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientMMSSetLobbyData {
    pub app_id: u32,
    pub steam_id_lobby: u64,
    pub steam_id_member: u64,
    pub max_members: i32,
    pub lobby_type: i32,
    pub lobby_flags: i32,
    pub metadata: Vec<u8>,
}

impl CMsgClientMMSSetLobbyData {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.app_id);
        w.fixed64_field(2, self.steam_id_lobby);
        w.fixed64_field(3, self.steam_id_member);
        w.int32_field(4, self.max_members);
        w.int32_field(5, self.lobby_type);
        w.int32_field(6, self.lobby_flags);
        w.bytes_field(7, &self.metadata);
        out
    }
}

pub type CMsgClientMMSSetLobbyDataResponse = CMsgClientMMSCreateLobbyResponse;

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientMMSSendLobbyChatMsg {
    pub app_id: u32,
    pub steam_id_lobby: u64,
    pub lobby_message: Vec<u8>,
}

impl CMsgClientMMSSendLobbyChatMsg {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.app_id);
        w.fixed64_field(2, self.steam_id_lobby);
        w.bytes_field(4, &self.lobby_message);
        out
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientMMSLobbyChatMsg {
    pub app_id: u32,
    pub steam_id_lobby: u64,
    pub steam_id_sender: u64,
    pub lobby_message: Vec<u8>,
}

impl CMsgClientMMSLobbyChatMsg {
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
                3 => m.steam_id_sender = r.fixed64()?,
                4 => m.lobby_message = r.bytes()?.to_vec(),
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
pub struct CMsgClientMMSUserJoinedOrLeftLobby {
    pub app_id: u32,
    pub steam_id_lobby: u64,
    pub steam_id_user: u64,
    pub persona_name: String,
}

impl CMsgClientMMSUserJoinedOrLeftLobby {
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
                3 => m.steam_id_user = r.fixed64()?,
                4 => m.persona_name = r.string()?,
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

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientMMSInviteToLobby {
    pub app_id: u32,
    pub steam_id_lobby: u64,
    pub steam_id_user_invited: u64,
}

impl CMsgClientMMSInviteToLobby {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.app_id);
        w.fixed64_field(2, self.steam_id_lobby);
        w.fixed64_field(3, self.steam_id_user_invited);
        out
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientMMSSetLobbyOwner {
    pub app_id: u32,
    pub steam_id_lobby: u64,
    pub steam_id_new_owner: u64,
}

impl CMsgClientMMSSetLobbyOwner {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.app_id);
        w.fixed64_field(2, self.steam_id_lobby);
        w.fixed64_field(3, self.steam_id_new_owner);
        out
    }
}

pub type CMsgClientMMSSetLobbyOwnerResponse = CMsgClientMMSCreateLobbyResponse;

fn parse_simple_lobby_response(body: &[u8]) -> Option<CMsgClientMMSCreateLobbyResponse> {
    let mut r = Reader::new(body);
    let mut m = CMsgClientMMSCreateLobbyResponse::default();
    while !r.eof() {
        let Some(t) = r.next_tag() else {
            return r.ok().then_some(m);
        };
        match t.field_number {
            1 => m.app_id = r.u32()?,
            2 => m.steam_id_lobby = r.fixed64()?,
            3 => m.eresult = r.u64()? as u32 as i32,
            _ => {
                if !r.skip(t.wire_type) {
                    return None;
                }
            }
        }
    }
    Some(m)
}

fn parse_join_member(body: &[u8]) -> Option<CMsgClientMMSJoinLobbyResponseMember> {
    let mut r = Reader::new(body);
    let mut m = CMsgClientMMSJoinLobbyResponseMember::default();
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_join_response_members() {
        let mut member = Vec::new();
        {
            let mut w = Writer::new(&mut member);
            w.fixed64_field(1, 9);
            w.string_field(2, "Ada");
            w.bytes_field(3, b"m");
        }
        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.uint32_field(1, 480);
            w.fixed64_field(2, 100);
            w.uint32_field(3, 1);
            w.uint32_field(4, 4);
            w.fixed64_field(7, 9);
            w.bytes_field(8, b"lobby");
            w.submessage_field(9, &member);
        }
        let parsed = CMsgClientMMSJoinLobbyResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.chat_room_enter_response, 1);
        assert_eq!(parsed.members[0].persona_name, "Ada");
    }

    #[test]
    fn serializes_lobby_chat_message() {
        let body = CMsgClientMMSSendLobbyChatMsg {
            app_id: 480,
            steam_id_lobby: 100,
            lobby_message: b"hello".to_vec(),
        }
        .serialize();
        assert_eq!(body[0], 8);
        assert!(body.ends_with(b"hello"));
    }
}
