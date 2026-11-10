use crate::cmsg_protobuf_header::CMsgProtoBufHeader;
use crate::emsg::{has_proto_flag, strip_proto_flag, with_proto_flag, EMsg};
use crate::wire_format;

pub const PROTO_ENVELOPE_PREFIX_BYTES: usize = 8;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ProtoEnvelope {
    pub emsg: EMsg,
    pub header: CMsgProtoBufHeader,
    pub body: Vec<u8>,
}

impl Default for ProtoEnvelope {
    fn default() -> Self {
        Self {
            emsg: EMsg::INVALID,
            header: CMsgProtoBufHeader::default(),
            body: Vec::new(),
        }
    }
}

pub fn encode_proto_envelope(emsg: EMsg, header: &CMsgProtoBufHeader, body: &[u8]) -> Vec<u8> {
    let mut hdr_bytes = Vec::with_capacity(64);
    header.serialize(&mut hdr_bytes);

    let mut out = Vec::with_capacity(PROTO_ENVELOPE_PREFIX_BYTES + hdr_bytes.len() + body.len());
    let mut writer = wire_format::Writer::new(&mut out);
    writer.u32(with_proto_flag(emsg));
    writer.u32(hdr_bytes.len() as u32);
    writer.bytes(&hdr_bytes);
    writer.bytes(body);
    out
}

pub fn decode_proto_envelope(wire: &[u8]) -> Option<ProtoEnvelope> {
    if wire.len() < PROTO_ENVELOPE_PREFIX_BYTES {
        return None;
    }
    let mut reader = wire_format::Reader::new(wire);
    let raw_emsg = reader.u32();
    let hdr_len = reader.u32() as usize;
    if !reader.ok() || !has_proto_flag(raw_emsg) || reader.remaining() < hdr_len {
        return None;
    }
    let header = CMsgProtoBufHeader::deserialize(reader.bytes(hdr_len))?;
    if !reader.ok() {
        return None;
    }
    let body_off = reader.position();
    Some(ProtoEnvelope {
        emsg: strip_proto_flag(raw_emsg),
        header,
        body: wire[body_off..].to_vec(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn proto_envelope_roundtrips() {
        let header = CMsgProtoBufHeader {
            steamid: 76561197960287930,
            jobid_source: 42,
            target_job_name: "Player.GetOwnedGames#1".to_string(),
            ..Default::default()
        };
        let body = [1, 2, 3, 4, 5];
        let bytes = encode_proto_envelope(EMsg::SERVICE_METHOD_CALL_FROM_CLIENT, &header, &body);
        let decoded = decode_proto_envelope(&bytes).unwrap();
        assert_eq!(decoded.emsg, EMsg::SERVICE_METHOD_CALL_FROM_CLIENT);
        assert_eq!(decoded.header, header);
        assert_eq!(decoded.body, body);
    }

    #[test]
    fn decode_rejects_non_proto_messages() {
        let mut bytes = Vec::new();
        let mut writer = wire_format::Writer::new(&mut bytes);
        writer.u32(EMsg::MULTI.0);
        writer.u32(0);
        assert_eq!(decode_proto_envelope(&bytes), None);
    }
}
