use crate::emsg::{has_proto_flag, strip_proto_flag, EMsg, EUniverse};
use crate::wire_format;

pub const INVALID_JOB_ID: u64 = u64::MAX;
pub const MSG_HDR_BYTES: usize = 20;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct MsgHdr {
    pub msg: EMsg,
    pub target_job_id: u64,
    pub source_job_id: u64,
}

impl Default for MsgHdr {
    fn default() -> Self {
        Self {
            msg: EMsg::INVALID,
            target_job_id: INVALID_JOB_ID,
            source_job_id: INVALID_JOB_ID,
        }
    }
}

impl MsgHdr {
    pub fn serialize(&self, out: &mut Vec<u8>) {
        let mut writer = wire_format::Writer::new(out);
        writer.u32(self.msg.0);
        writer.u64(self.target_job_id);
        writer.u64(self.source_job_id);
    }

    pub fn deserialize(input: &[u8]) -> Option<(Self, usize)> {
        if input.len() < MSG_HDR_BYTES {
            return None;
        }
        let mut reader = wire_format::Reader::new(&input[..MSG_HDR_BYTES]);
        let raw_msg = reader.u32();
        if has_proto_flag(raw_msg) {
            return None;
        }
        let hdr = Self {
            msg: strip_proto_flag(raw_msg),
            target_job_id: reader.u64(),
            source_job_id: reader.u64(),
        };
        reader.ok().then_some((hdr, MSG_HDR_BYTES))
    }
}

pub const CHANNEL_ENCRYPT_PROTOCOL_VERSION: u32 = 1;
pub const CHANNEL_ENCRYPT_REQUEST_FIXED_BODY: usize = 8;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MsgChannelEncryptRequest {
    pub protocol_version: u32,
    pub universe: EUniverse,
    pub challenge: Vec<u8>,
}

impl Default for MsgChannelEncryptRequest {
    fn default() -> Self {
        Self {
            protocol_version: CHANNEL_ENCRYPT_PROTOCOL_VERSION,
            universe: EUniverse::Invalid,
            challenge: Vec::new(),
        }
    }
}

impl MsgChannelEncryptRequest {
    pub fn deserialize_body(body: &[u8]) -> Option<Self> {
        if body.len() < CHANNEL_ENCRYPT_REQUEST_FIXED_BODY {
            return None;
        }
        let mut reader = wire_format::Reader::new(body);
        let protocol_version = reader.u32();
        let universe = EUniverse::from_u32(reader.u32())?;
        if !reader.ok() {
            return None;
        }
        let challenge = body[CHANNEL_ENCRYPT_REQUEST_FIXED_BODY..].to_vec();
        Some(Self {
            protocol_version,
            universe,
            challenge,
        })
    }
}

pub const RSA_1024_CIPHER_BYTES: usize = 128;
pub const CHANNEL_ENCRYPT_RESPONSE_BODY_BYTES: usize = 144;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MsgChannelEncryptResponse {
    pub protocol_version: u32,
    pub key_size: u32,
    pub encrypted_handshake_blob: [u8; RSA_1024_CIPHER_BYTES],
    pub key_crc: u32,
    pub unknown_zero: u32,
}

impl Default for MsgChannelEncryptResponse {
    fn default() -> Self {
        Self {
            protocol_version: CHANNEL_ENCRYPT_PROTOCOL_VERSION,
            key_size: RSA_1024_CIPHER_BYTES as u32,
            encrypted_handshake_blob: [0; RSA_1024_CIPHER_BYTES],
            key_crc: 0,
            unknown_zero: 0,
        }
    }
}

impl MsgChannelEncryptResponse {
    pub fn serialize_body(&self, out: &mut Vec<u8>) {
        let mut writer = wire_format::Writer::new(out);
        writer.u32(self.protocol_version);
        writer.u32(self.key_size);
        writer.bytes(&self.encrypted_handshake_blob);
        writer.u32(self.key_crc);
        writer.u32(self.unknown_zero);
    }
}

pub const CHANNEL_ENCRYPT_RESULT_BODY_BYTES: usize = 4;

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct MsgChannelEncryptResult {
    pub result: u32,
}

impl MsgChannelEncryptResult {
    pub fn deserialize_body(body: &[u8]) -> Option<Self> {
        if body.len() < CHANNEL_ENCRYPT_RESULT_BODY_BYTES {
            return None;
        }
        let mut reader = wire_format::Reader::new(body);
        let result = reader.u32();
        reader.ok().then_some(Self { result })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::emsg::with_proto_flag;

    #[test]
    fn msg_hdr_roundtrips_and_rejects_proto_flag() {
        let hdr = MsgHdr {
            msg: EMsg::CHANNEL_ENCRYPT_REQUEST,
            target_job_id: 7,
            source_job_id: 9,
        };
        let mut bytes = Vec::new();
        hdr.serialize(&mut bytes);
        assert_eq!(bytes.len(), MSG_HDR_BYTES);
        assert_eq!(MsgHdr::deserialize(&bytes), Some((hdr, MSG_HDR_BYTES)));

        bytes[..4].copy_from_slice(&with_proto_flag(EMsg::CHANNEL_ENCRYPT_REQUEST).to_le_bytes());
        assert_eq!(MsgHdr::deserialize(&bytes), None);
    }

    #[test]
    fn channel_encrypt_request_parses_challenge() {
        let mut body = Vec::new();
        let mut writer = wire_format::Writer::new(&mut body);
        writer.u32(1);
        writer.u32(EUniverse::Public as u32);
        writer.bytes(&[0xaa; 16]);
        let msg = MsgChannelEncryptRequest::deserialize_body(&body).unwrap();
        assert_eq!(msg.protocol_version, 1);
        assert_eq!(msg.universe, EUniverse::Public);
        assert_eq!(msg.challenge, vec![0xaa; 16]);
    }

    #[test]
    fn channel_encrypt_response_is_144_bytes() {
        let msg = MsgChannelEncryptResponse {
            key_crc: 0xfeed_beef,
            ..Default::default()
        };
        let mut body = Vec::new();
        msg.serialize_body(&mut body);
        assert_eq!(body.len(), CHANNEL_ENCRYPT_RESPONSE_BODY_BYTES);
        assert_eq!(&body[0..4], &1u32.to_le_bytes());
        assert_eq!(
            &body[8 + RSA_1024_CIPHER_BYTES..12 + RSA_1024_CIPHER_BYTES],
            &0xfeed_beefu32.to_le_bytes()
        );
    }
}
