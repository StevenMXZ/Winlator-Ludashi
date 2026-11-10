use crate::proto_wire::{Reader, WireType, Writer};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(i32)]
pub enum EAuthTokenPlatformType {
    Unknown = 0,
    SteamClient = 1,
    WebBrowser = 2,
    MobileApp = 3,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(i32)]
pub enum EAuthSessionGuardType {
    Unknown = 0,
    None = 1,
    EmailCode = 2,
    DeviceCode = 3,
    DeviceConfirmation = 4,
    EmailConfirmation = 5,
    MachineToken = 6,
    LegacyMachineAuth = 7,
}

impl From<i32> for EAuthSessionGuardType {
    fn from(value: i32) -> Self {
        match value {
            1 => Self::None,
            2 => Self::EmailCode,
            3 => Self::DeviceCode,
            4 => Self::DeviceConfirmation,
            5 => Self::EmailConfirmation,
            6 => Self::MachineToken,
            7 => Self::LegacyMachineAuth,
            _ => Self::Unknown,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(i32)]
pub enum ESessionPersistence {
    Invalid = -1,
    Ephemeral = 0,
    Persistent = 1,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CAuthenticationDeviceDetails {
    pub device_friendly_name: String,
    pub platform_type: EAuthTokenPlatformType,
    pub os_type: i32,
    pub gaming_device_type: u32,
    pub client_count: u32,
    pub machine_id: Vec<u8>,
}

impl Default for CAuthenticationDeviceDetails {
    fn default() -> Self {
        Self {
            device_friendly_name: String::new(),
            platform_type: EAuthTokenPlatformType::SteamClient,
            os_type: 16,
            gaming_device_type: 0,
            client_count: 0,
            machine_id: Vec::new(),
        }
    }
}

impl CAuthenticationDeviceDetails {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.string_field(1, &self.device_friendly_name);
        w.int32_field(2, self.platform_type as i32);
        w.int32_field(3, self.os_type);
        w.uint32_field(4, self.gaming_device_type);
        w.uint32_field(5, self.client_count);
        w.bytes_field(6, &self.machine_id);
        out
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct GetPasswordRsaPublicKeyRequest {
    pub account_name: String,
}

impl GetPasswordRsaPublicKeyRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        Writer::new(&mut out).string_field(1, &self.account_name);
        out
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct GetPasswordRsaPublicKeyResponse {
    pub publickey_mod: String,
    pub publickey_exp: String,
    pub timestamp: u64,
}

impl GetPasswordRsaPublicKeyResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.publickey_mod = reader.string()?,
                2 => msg.publickey_exp = reader.string()?,
                3 => msg.timestamp = reader.u64()?,
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
pub struct BeginAuthSessionViaCredentialsRequest {
    pub account_name: String,
    pub encrypted_password: String,
    pub encryption_timestamp: u64,
    pub website_id: String,
    pub persistence: ESessionPersistence,
    pub device_details: CAuthenticationDeviceDetails,
    pub guard_data: String,
    pub language: u32,
    pub qos_level: i32,
}

impl Default for BeginAuthSessionViaCredentialsRequest {
    fn default() -> Self {
        Self {
            account_name: String::new(),
            encrypted_password: String::new(),
            encryption_timestamp: 0,
            website_id: "Client".to_string(),
            persistence: ESessionPersistence::Persistent,
            device_details: CAuthenticationDeviceDetails::default(),
            guard_data: String::new(),
            language: 0,
            qos_level: 2,
        }
    }
}

impl BeginAuthSessionViaCredentialsRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.string_field(2, &self.account_name);
        w.string_field(3, &self.encrypted_password);
        w.uint64_field(4, self.encryption_timestamp);
        w.int32_field(7, self.persistence as i32);
        w.string_field(8, &self.website_id);
        let dd = self.device_details.serialize();
        if !dd.is_empty() {
            w.submessage_field(9, &dd);
        }
        w.string_field(10, &self.guard_data);
        w.uint32_field(11, self.language);
        w.int32_field(12, self.qos_level);
        out
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AllowedConfirmation {
    pub confirmation_type: EAuthSessionGuardType,
    pub associated_message: String,
}

impl Default for AllowedConfirmation {
    fn default() -> Self {
        Self {
            confirmation_type: EAuthSessionGuardType::Unknown,
            associated_message: String::new(),
        }
    }
}

impl AllowedConfirmation {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.confirmation_type = EAuthSessionGuardType::from(reader.i32()?),
                2 => msg.associated_message = reader.string()?,
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

#[derive(Clone, Debug, PartialEq)]
pub struct BeginAuthSessionViaCredentialsResponse {
    pub client_id: u64,
    pub request_id: Vec<u8>,
    pub interval: f32,
    pub allowed_confirmations: Vec<AllowedConfirmation>,
    pub steamid: u64,
    pub weak_token: String,
    pub agreement_session_url: String,
    pub extended_error_message: String,
}

impl Default for BeginAuthSessionViaCredentialsResponse {
    fn default() -> Self {
        Self {
            client_id: 0,
            request_id: Vec::new(),
            interval: 5.0,
            allowed_confirmations: Vec::new(),
            steamid: 0,
            weak_token: String::new(),
            agreement_session_url: String::new(),
            extended_error_message: String::new(),
        }
    }
}

impl BeginAuthSessionViaCredentialsResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.client_id = reader.u64()?,
                2 => msg.request_id = reader.bytes()?.to_vec(),
                3 => {
                    if tag.wire_type != WireType::Fixed32 {
                        return None;
                    }
                    msg.interval = f32::from_bits(reader.fixed32()?);
                }
                4 => msg
                    .allowed_confirmations
                    .push(AllowedConfirmation::deserialize(reader.bytes()?)?),
                5 => msg.steamid = reader.u64()?,
                6 => msg.weak_token = reader.string()?,
                7 => msg.agreement_session_url = reader.string()?,
                8 => msg.extended_error_message = reader.string()?,
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

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct PollAuthSessionStatusRequest {
    pub client_id: u64,
    pub request_id: Vec<u8>,
    pub token_to_revoke: u64,
}

impl PollAuthSessionStatusRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint64_field(1, self.client_id);
        w.bytes_field(2, &self.request_id);
        if self.token_to_revoke != 0 {
            w.tag(3, WireType::Fixed64);
            w.raw_bytes(&self.token_to_revoke.to_le_bytes());
        }
        out
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct PollAuthSessionStatusResponse {
    pub new_client_id: u64,
    pub new_challenge_url: String,
    pub refresh_token: String,
    pub access_token: String,
    pub had_remote_interaction: bool,
    pub account_name: String,
    pub new_guard_data: String,
    pub agreement_session_url: String,
}

impl PollAuthSessionStatusResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.new_client_id = reader.u64()?,
                2 => msg.new_challenge_url = reader.string()?,
                3 => msg.refresh_token = reader.string()?,
                4 => msg.access_token = reader.string()?,
                5 => msg.had_remote_interaction = reader.boolean()?,
                6 => msg.account_name = reader.string()?,
                7 => msg.new_guard_data = reader.string()?,
                8 => msg.agreement_session_url = reader.string()?,
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
pub struct UpdateAuthSessionWithSteamGuardCodeRequest {
    pub client_id: u64,
    pub steamid: u64,
    pub code: String,
    pub code_type: EAuthSessionGuardType,
}

impl Default for UpdateAuthSessionWithSteamGuardCodeRequest {
    fn default() -> Self {
        Self {
            client_id: 0,
            steamid: 0,
            code: String::new(),
            code_type: EAuthSessionGuardType::Unknown,
        }
    }
}

impl UpdateAuthSessionWithSteamGuardCodeRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint64_field(1, self.client_id);
        if self.steamid != 0 {
            w.tag(2, WireType::Fixed64);
            w.raw_bytes(&self.steamid.to_le_bytes());
        }
        w.string_field(3, &self.code);
        w.int32_field(4, self.code_type as i32);
        out
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct BeginAuthSessionViaQrRequest {
    pub device_friendly_name: String,
    pub platform_type: EAuthTokenPlatformType,
    pub device_details: CAuthenticationDeviceDetails,
    pub website_id: String,
}

impl Default for BeginAuthSessionViaQrRequest {
    fn default() -> Self {
        Self {
            device_friendly_name: String::new(),
            platform_type: EAuthTokenPlatformType::MobileApp,
            device_details: CAuthenticationDeviceDetails::default(),
            website_id: "Mobile".to_string(),
        }
    }
}

impl BeginAuthSessionViaQrRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.string_field(1, &self.device_friendly_name);
        w.int32_field(2, self.platform_type as i32);
        let dd = self.device_details.serialize();
        if !dd.is_empty() {
            w.submessage_field(3, &dd);
        }
        w.string_field(4, &self.website_id);
        out
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct BeginAuthSessionViaQrResponse {
    pub client_id: u64,
    pub challenge_url: String,
    pub request_id: Vec<u8>,
    pub interval: f32,
    pub allowed_confirmations: Vec<AllowedConfirmation>,
    pub version: i32,
}

impl Default for BeginAuthSessionViaQrResponse {
    fn default() -> Self {
        Self {
            client_id: 0,
            challenge_url: String::new(),
            request_id: Vec::new(),
            interval: 5.0,
            allowed_confirmations: Vec::new(),
            version: 0,
        }
    }
}

impl BeginAuthSessionViaQrResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.client_id = reader.u64()?,
                2 => msg.challenge_url = reader.string()?,
                3 => msg.request_id = reader.bytes()?.to_vec(),
                4 => {
                    if tag.wire_type != WireType::Fixed32 {
                        return None;
                    }
                    msg.interval = f32::from_bits(reader.fixed32()?);
                }
                5 => msg
                    .allowed_confirmations
                    .push(AllowedConfirmation::deserialize(reader.bytes()?)?),
                6 => msg.version = reader.i32()?,
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

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(i32)]
pub enum EAuthTokenRenewalType {
    None = 0,
    Allow = 1,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AccessTokenGenerateForAppRequest {
    pub refresh_token: String,
    pub steamid: u64,
    pub renewal_type: EAuthTokenRenewalType,
}

impl Default for AccessTokenGenerateForAppRequest {
    fn default() -> Self {
        Self {
            refresh_token: String::new(),
            steamid: 0,
            renewal_type: EAuthTokenRenewalType::None,
        }
    }
}

impl AccessTokenGenerateForAppRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.string_field(1, &self.refresh_token);
        w.fixed64_field(2, self.steamid);
        w.int32_field(3, self.renewal_type as i32);
        out
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct AccessTokenGenerateForAppResponse {
    pub access_token: String,
    pub refresh_token: String,
}

impl AccessTokenGenerateForAppResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.access_token = reader.string()?,
                2 => msg.refresh_token = reader.string()?,
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
    fn credentials_request_uses_audited_field_numbers() {
        let msg = BeginAuthSessionViaCredentialsRequest {
            account_name: "user".to_string(),
            encrypted_password: "enc".to_string(),
            encryption_timestamp: 123,
            ..Default::default()
        };
        let bytes = msg.serialize();
        let fields = field_numbers(&bytes);
        assert!(fields.contains(&2));
        assert!(fields.contains(&3));
        assert!(fields.contains(&4));
        assert!(fields.contains(&7));
        assert!(fields.contains(&8));
        assert!(fields.contains(&9));
        assert!(!fields.contains(&6));
    }

    #[test]
    fn credentials_response_reads_float_interval_and_confirmations() {
        let mut confirmation = Vec::new();
        {
            let mut w = Writer::new(&mut confirmation);
            w.int32_field(1, EAuthSessionGuardType::DeviceCode as i32);
            w.string_field(2, "mobile");
        }
        let mut bytes = Vec::new();
        {
            let mut w = Writer::new(&mut bytes);
            w.uint64_field(1, 55);
            w.bytes_field(2, &[1, 2]);
            w.tag(3, WireType::Fixed32);
            w.raw_bytes(&2.5f32.to_bits().to_le_bytes());
            w.submessage_field(4, &confirmation);
            w.uint64_field(5, 765);
        }
        let msg = BeginAuthSessionViaCredentialsResponse::deserialize(&bytes).unwrap();
        assert_eq!(msg.client_id, 55);
        assert_eq!(msg.interval, 2.5);
        assert_eq!(
            msg.allowed_confirmations[0].confirmation_type,
            EAuthSessionGuardType::DeviceCode
        );
    }

    fn field_numbers(bytes: &[u8]) -> Vec<i32> {
        let mut reader = Reader::new(bytes);
        let mut out = Vec::new();
        while let Some(tag) = reader.next_tag() {
            out.push(tag.field_number);
            reader.skip(tag.wire_type);
        }
        out
    }
}
