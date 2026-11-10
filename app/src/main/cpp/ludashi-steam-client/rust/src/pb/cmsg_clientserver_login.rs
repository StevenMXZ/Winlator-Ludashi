use crate::proto_wire::{Reader, WireType, Writer};

pub const MSG_CLIENT_CURRENT_PROTOCOL: u32 = 65_581;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CMsgClientHello {
    pub protocol_version: u32,
}

impl Default for CMsgClientHello {
    fn default() -> Self {
        Self {
            protocol_version: MSG_CLIENT_CURRENT_PROTOCOL,
        }
    }
}

impl CMsgClientHello {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        Writer::new(&mut out).uint32_field(1, self.protocol_version);
        out
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientHeartBeat {
    pub send_reply: bool,
}

impl CMsgClientHeartBeat {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        Writer::new(&mut out).bool_field(1, self.send_reply);
        out
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientLogOff;

impl CMsgClientLogOff {
    pub fn serialize(&self) -> Vec<u8> {
        Vec::new()
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CMsgClientLoggedOff {
    pub eresult: i32,
}

impl Default for CMsgClientLoggedOff {
    fn default() -> Self {
        Self { eresult: 2 }
    }
}

impl CMsgClientLoggedOff {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.eresult = reader.i32()?,
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

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CMsgClientLogon {
    pub protocol_version: u32,
    pub cell_id: u32,
    pub client_package_version: u32,
    pub client_language: String,
    pub client_os_type: u32,
    pub should_remember_password: bool,
    pub qos_level: u32,
    pub client_supplied_steam_id: u64,
    pub machine_id: Vec<u8>,
    pub ui_mode: u32,
    pub chat_mode: u32,
    pub account_name: String,
    pub machine_name: String,
    pub client_instance_id: u64,
    pub supports_rate_limit_response: bool,
    pub access_token: String,
    pub obfuscated_private_ip: u32,
}

impl Default for CMsgClientLogon {
    fn default() -> Self {
        Self {
            protocol_version: MSG_CLIENT_CURRENT_PROTOCOL,
            cell_id: 0,
            client_package_version: 1771,
            client_language: "english".to_string(),
            client_os_type: 16,
            should_remember_password: true,
            qos_level: 2,
            client_supplied_steam_id: 0,
            machine_id: Vec::new(),
            ui_mode: 7,
            chat_mode: 2,
            account_name: String::new(),
            machine_name: "Ludashi-Steam-Client".to_string(),
            client_instance_id: 0,
            supports_rate_limit_response: true,
            access_token: String::new(),
            obfuscated_private_ip: 0,
        }
    }
}

impl CMsgClientLogon {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.protocol_version);
        w.uint32_field(3, self.cell_id);
        w.uint32_field(5, self.client_package_version);
        w.string_field(6, &self.client_language);
        w.uint32_field(7, self.client_os_type);
        w.bool_field(8, self.should_remember_password);
        w.uint32_field(21, self.qos_level);
        if self.client_supplied_steam_id != 0 {
            w.tag(22, WireType::Fixed64);
            w.raw_bytes(&self.client_supplied_steam_id.to_le_bytes());
        }
        w.bytes_field(30, &self.machine_id);
        if self.obfuscated_private_ip != 0 {
            w.uint32_field(31, self.obfuscated_private_ip);
            let mut ip_msg = Vec::new();
            Writer::new(&mut ip_msg).uint32_field(1, self.obfuscated_private_ip);
            w.bytes_field(95, &ip_msg);
        }
        w.uint32_field(32, self.ui_mode);
        w.uint32_field(33, self.chat_mode);
        w.string_field(50, &self.account_name);
        w.string_field(96, &self.machine_name);
        w.uint64_field(100, self.client_instance_id);
        w.bool_field(102, self.supports_rate_limit_response);
        w.string_field(108, &self.access_token);
        out
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CMsgClientLogonResponse {
    pub eresult: i32,
    pub heartbeat_seconds: i32,
    pub rtime32_server_time: u32,
    pub cell_id: u32,
    pub eresult_extended: i32,
    pub vanity_url: String,
    pub client_supplied_steamid: u64,
    pub client_instance_id: u64,
    pub force_client_update_check: bool,
    pub agreement_session_url: String,
    pub token_id: u64,
    pub family_group_id: u64,
}

impl Default for CMsgClientLogonResponse {
    fn default() -> Self {
        Self {
            eresult: 2,
            heartbeat_seconds: 0,
            rtime32_server_time: 0,
            cell_id: 0,
            eresult_extended: 0,
            vanity_url: String::new(),
            client_supplied_steamid: 0,
            client_instance_id: 0,
            force_client_update_check: false,
            agreement_session_url: String::new(),
            token_id: 0,
            family_group_id: 0,
        }
    }
}

impl CMsgClientLogonResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.eresult = reader.i32()?,
                3 => msg.heartbeat_seconds = reader.i32()?,
                5 => {
                    if tag.wire_type != WireType::Fixed32 {
                        return None;
                    }
                    msg.rtime32_server_time = reader.fixed32()?;
                }
                7 => msg.cell_id = reader.u32()?,
                10 => msg.eresult_extended = reader.i32()?,
                14 => msg.vanity_url = reader.string()?,
                20 => {
                    if tag.wire_type != WireType::Fixed64 {
                        return None;
                    }
                    msg.client_supplied_steamid = reader.fixed64()?;
                }
                27 => msg.client_instance_id = reader.u64()?,
                28 => msg.force_client_update_check = reader.boolean()?,
                29 => msg.agreement_session_url = reader.string()?,
                30 => msg.token_id = reader.u64()?,
                31 => msg.family_group_id = reader.u64()?,
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
    fn logon_serializes_canonical_fields() {
        let msg = CMsgClientLogon {
            account_name: "user".to_string(),
            access_token: "refresh.jwt".to_string(),
            client_instance_id: 99,
            obfuscated_private_ip: 0x0102_0304,
            ..Default::default()
        };
        let bytes = msg.serialize();
        let mut reader = Reader::new(&bytes);
        let mut fields = Vec::new();
        while let Some(tag) = reader.next_tag() {
            fields.push(tag.field_number);
            reader.skip(tag.wire_type);
        }
        assert!(fields.contains(&50));
        assert!(fields.contains(&95));
        assert!(fields.contains(&108));
        assert!(!fields.contains(&10));
    }

    #[test]
    fn logon_response_reads_token_id_as_varint() {
        let mut bytes = Vec::new();
        let mut w = Writer::new(&mut bytes);
        w.int32_field(1, 1);
        w.int32_field(3, 10);
        w.tag(5, WireType::Fixed32);
        w.raw_bytes(&123u32.to_le_bytes());
        w.uint64_field(30, 987);
        let msg = CMsgClientLogonResponse::deserialize(&bytes).unwrap();
        assert_eq!(msg.eresult, 1);
        assert_eq!(msg.rtime32_server_time, 123);
        assert_eq!(msg.token_id, 987);
    }
}
