use crate::proto_wire::{Reader, WireType, Writer};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CMsgClientPICSChangesSinceRequest {
    pub since_change_number: u32,
    pub send_app_info_changes: bool,
    pub send_package_info_changes: bool,
}

impl Default for CMsgClientPICSChangesSinceRequest {
    fn default() -> Self {
        Self {
            since_change_number: 0,
            send_app_info_changes: true,
            send_package_info_changes: true,
        }
    }
}

impl CMsgClientPICSChangesSinceRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field_force(1, self.since_change_number);
        w.bool_field_force(2, self.send_app_info_changes);
        w.bool_field_force(3, self.send_package_info_changes);
        out
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct PicsAppChange {
    pub appid: u32,
    pub change_number: u32,
    pub needs_token: bool,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct PicsPackageChange {
    pub packageid: u32,
    pub change_number: u32,
    pub needs_token: bool,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientPICSChangesSinceResponse {
    pub current_change_number: u32,
    pub since_change_number: u32,
    pub force_full_update: bool,
    pub package_changes: Vec<PicsPackageChange>,
    pub app_changes: Vec<PicsAppChange>,
    pub force_full_app_update: bool,
    pub force_full_package_update: bool,
}

impl CMsgClientPICSChangesSinceResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.current_change_number = reader.u32()?,
                2 => msg.since_change_number = reader.u32()?,
                3 => msg.force_full_update = reader.boolean()?,
                4 => {
                    let c = parse_pics_change(reader.bytes()?)?;
                    msg.package_changes.push(PicsPackageChange {
                        packageid: c.id,
                        change_number: c.change_number,
                        needs_token: c.needs_token,
                    });
                }
                5 => {
                    let c = parse_pics_change(reader.bytes()?)?;
                    msg.app_changes.push(PicsAppChange {
                        appid: c.id,
                        change_number: c.change_number,
                        needs_token: c.needs_token,
                    });
                }
                6 => msg.force_full_app_update = reader.boolean()?,
                7 => msg.force_full_package_update = reader.boolean()?,
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
pub struct CMsgClientPICSAccessTokenRequest {
    pub packageids: Vec<u32>,
    pub appids: Vec<u32>,
}

impl CMsgClientPICSAccessTokenRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        for id in &self.packageids {
            w.tag(1, WireType::Varint);
            w.varint(*id as u64);
        }
        for id in &self.appids {
            w.tag(2, WireType::Varint);
            w.varint(*id as u64);
        }
        out
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct PicsPackageToken {
    pub packageid: u32,
    pub access_token: u64,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct PicsAppToken {
    pub appid: u32,
    pub access_token: u64,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientPICSAccessTokenResponse {
    pub package_access_tokens: Vec<PicsPackageToken>,
    pub package_denied_tokens: Vec<u32>,
    pub app_access_tokens: Vec<PicsAppToken>,
    pub app_denied_tokens: Vec<u32>,
}

impl CMsgClientPICSAccessTokenResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg
                    .package_access_tokens
                    .push(parse_package_token(reader.bytes()?)?),
                2 => read_repeated_uint32(
                    &mut reader,
                    tag.wire_type,
                    &mut msg.package_denied_tokens,
                )?,
                3 => msg
                    .app_access_tokens
                    .push(parse_app_token(reader.bytes()?)?),
                4 => read_repeated_uint32(&mut reader, tag.wire_type, &mut msg.app_denied_tokens)?,
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

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct PicsAppInfoReq {
    pub appid: u32,
    pub access_token: u64,
    pub only_public_obsolete: bool,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct PicsPackageInfoReq {
    pub packageid: u32,
    pub access_token: u64,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientPICSProductInfoRequest {
    pub packages: Vec<PicsPackageInfoReq>,
    pub apps: Vec<PicsAppInfoReq>,
    pub meta_data_only: bool,
    pub num_prev_failed: u32,
    pub sequence_number: u32,
    pub single_response: bool,
}

impl CMsgClientPICSProductInfoRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        for package in &self.packages {
            let mut sub = Vec::new();
            let mut sw = Writer::new(&mut sub);
            sw.uint32_field(1, package.packageid);
            sw.uint64_field(2, package.access_token);
            w.submessage_field(1, &sub);
        }
        for app in &self.apps {
            let mut sub = Vec::new();
            let mut sw = Writer::new(&mut sub);
            sw.uint32_field(1, app.appid);
            sw.uint64_field(2, app.access_token);
            sw.bool_field(3, app.only_public_obsolete);
            w.submessage_field(2, &sub);
        }
        w.bool_field(3, self.meta_data_only);
        w.uint32_field(4, self.num_prev_failed);
        w.uint32_field(6, self.sequence_number);
        w.bool_field(7, self.single_response);
        out
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct PicsAppInfoResp {
    pub appid: u32,
    pub change_number: u32,
    pub missing_token: bool,
    pub sha: Vec<u8>,
    pub buffer: Vec<u8>,
    pub only_public: bool,
    pub size: u32,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct PicsPackageInfoResp {
    pub packageid: u32,
    pub change_number: u32,
    pub missing_token: bool,
    pub sha: Vec<u8>,
    pub buffer: Vec<u8>,
    pub size: u32,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientPICSProductInfoResponse {
    pub apps: Vec<PicsAppInfoResp>,
    pub unknown_appids: Vec<u32>,
    pub packages: Vec<PicsPackageInfoResp>,
    pub unknown_packageids: Vec<u32>,
    pub meta_data_only: bool,
    pub response_pending: bool,
    pub http_min_size: u32,
    pub http_host: String,
}

impl CMsgClientPICSProductInfoResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.apps.push(parse_app_info_resp(reader.bytes()?)?),
                2 => read_repeated_uint32(&mut reader, tag.wire_type, &mut msg.unknown_appids)?,
                3 => msg.packages.push(parse_package_info_resp(reader.bytes()?)?),
                4 => read_repeated_uint32(&mut reader, tag.wire_type, &mut msg.unknown_packageids)?,
                5 => msg.meta_data_only = reader.boolean()?,
                6 => msg.response_pending = reader.boolean()?,
                7 => msg.http_min_size = reader.u32()?,
                8 => msg.http_host = reader.string()?,
                _ => {
                    if !reader.skip(tag.wire_type) {
                        return None;
                    }
                }
            }
        }
        Some(msg)
    }

    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        for app in &self.apps {
            w.submessage_field(1, &app.serialize());
        }
        for appid in &self.unknown_appids {
            w.uint32_field(2, *appid);
        }
        for package in &self.packages {
            w.submessage_field(3, &package.serialize());
        }
        for packageid in &self.unknown_packageids {
            w.uint32_field(4, *packageid);
        }
        w.bool_field(5, self.meta_data_only);
        w.bool_field(6, self.response_pending);
        w.uint32_field(7, self.http_min_size);
        w.string_field(8, &self.http_host);
        out
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
struct PicsChangeRaw {
    id: u32,
    change_number: u32,
    needs_token: bool,
}

fn parse_pics_change(body: &[u8]) -> Option<PicsChangeRaw> {
    let mut reader = Reader::new(body);
    let mut msg = PicsChangeRaw::default();
    while !reader.eof() {
        let Some(tag) = reader.next_tag() else {
            return reader.ok().then_some(msg);
        };
        match tag.field_number {
            1 => msg.id = reader.u32()?,
            2 => msg.change_number = reader.u32()?,
            3 => msg.needs_token = reader.boolean()?,
            _ => {
                if !reader.skip(tag.wire_type) {
                    return None;
                }
            }
        }
    }
    Some(msg)
}

fn parse_package_token(body: &[u8]) -> Option<PicsPackageToken> {
    let raw = parse_token(body)?;
    Some(PicsPackageToken {
        packageid: raw.id,
        access_token: raw.access_token,
    })
}

fn parse_app_token(body: &[u8]) -> Option<PicsAppToken> {
    let raw = parse_token(body)?;
    Some(PicsAppToken {
        appid: raw.id,
        access_token: raw.access_token,
    })
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
struct TokenRaw {
    id: u32,
    access_token: u64,
}

fn parse_token(body: &[u8]) -> Option<TokenRaw> {
    let mut reader = Reader::new(body);
    let mut msg = TokenRaw::default();
    while !reader.eof() {
        let Some(tag) = reader.next_tag() else {
            return reader.ok().then_some(msg);
        };
        match tag.field_number {
            1 => msg.id = reader.u32()?,
            2 => msg.access_token = reader.u64()?,
            _ => {
                if !reader.skip(tag.wire_type) {
                    return None;
                }
            }
        }
    }
    Some(msg)
}

fn parse_app_info_resp(body: &[u8]) -> Option<PicsAppInfoResp> {
    let mut reader = Reader::new(body);
    let mut msg = PicsAppInfoResp::default();
    while !reader.eof() {
        let Some(tag) = reader.next_tag() else {
            return reader.ok().then_some(msg);
        };
        match tag.field_number {
            1 => msg.appid = reader.u32()?,
            2 => msg.change_number = reader.u32()?,
            3 => msg.missing_token = reader.boolean()?,
            4 => msg.sha = reader.bytes()?.to_vec(),
            5 => msg.buffer = reader.bytes()?.to_vec(),
            6 => msg.only_public = reader.boolean()?,
            7 => msg.size = reader.u32()?,
            _ => {
                if !reader.skip(tag.wire_type) {
                    return None;
                }
            }
        }
    }
    Some(msg)
}

impl PicsAppInfoResp {
    fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.appid);
        w.uint32_field(2, self.change_number);
        w.bool_field(3, self.missing_token);
        w.bytes_field(4, &self.sha);
        w.bytes_field(5, &self.buffer);
        w.bool_field(6, self.only_public);
        w.uint32_field(7, self.size);
        out
    }
}

fn parse_package_info_resp(body: &[u8]) -> Option<PicsPackageInfoResp> {
    let mut reader = Reader::new(body);
    let mut msg = PicsPackageInfoResp::default();
    while !reader.eof() {
        let Some(tag) = reader.next_tag() else {
            return reader.ok().then_some(msg);
        };
        match tag.field_number {
            1 => msg.packageid = reader.u32()?,
            2 => msg.change_number = reader.u32()?,
            3 => msg.missing_token = reader.boolean()?,
            4 => msg.sha = reader.bytes()?.to_vec(),
            5 => msg.buffer = reader.bytes()?.to_vec(),
            6 => msg.size = reader.u32()?,
            _ => {
                if !reader.skip(tag.wire_type) {
                    return None;
                }
            }
        }
    }
    Some(msg)
}

impl PicsPackageInfoResp {
    fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.packageid);
        w.uint32_field(2, self.change_number);
        w.bool_field(3, self.missing_token);
        w.bytes_field(4, &self.sha);
        w.bytes_field(5, &self.buffer);
        w.uint32_field(6, self.size);
        out
    }
}

fn read_repeated_uint32(
    reader: &mut Reader<'_>,
    wire_type: WireType,
    out: &mut Vec<u32>,
) -> Option<()> {
    match wire_type {
        WireType::Varint => out.push(reader.u32()?),
        WireType::LengthDelimited => {
            let mut packed = Reader::new(reader.bytes()?);
            while !packed.eof() {
                out.push(packed.varint()? as u32);
            }
        }
        _ => return None,
    }
    Some(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn token_body(id: u32, token: u64) -> Vec<u8> {
        let mut body = Vec::new();
        let mut w = Writer::new(&mut body);
        w.uint32_field(1, id);
        w.uint64_field(2, token);
        body
    }

    #[test]
    fn changes_since_request_force_emits_zero_and_bools() {
        assert_eq!(
            CMsgClientPICSChangesSinceRequest::default().serialize(),
            [8, 0, 16, 1, 24, 1]
        );
    }

    #[test]
    fn parses_access_tokens_and_packed_denied_lists() {
        let mut packed = Vec::new();
        {
            let mut w = Writer::new(&mut packed);
            w.varint(10);
            w.varint(11);
        }

        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.submessage_field(1, &token_body(100, 555));
            w.uint32_field(2, 9);
            w.bytes_field(2, &packed);
            w.submessage_field(3, &token_body(480, 777));
            w.uint32_field(4, 12);
        }

        let parsed = CMsgClientPICSAccessTokenResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.package_access_tokens[0].packageid, 100);
        assert_eq!(parsed.package_denied_tokens, [9, 10, 11]);
        assert_eq!(parsed.app_access_tokens[0].appid, 480);
        assert_eq!(parsed.app_denied_tokens, [12]);
    }

    #[test]
    fn parses_product_info_response_without_stripping_package_prefix() {
        let mut app = Vec::new();
        {
            let mut w = Writer::new(&mut app);
            w.uint32_field(1, 480);
            w.uint32_field(2, 22);
            w.bool_field(3, true);
            w.bytes_field(4, &[1; 20]);
            w.bytes_field(5, b"appvdf");
            w.bool_field(6, true);
            w.uint32_field(7, 6);
        }

        let mut package = Vec::new();
        {
            let mut w = Writer::new(&mut package);
            w.uint32_field(1, 100);
            w.uint32_field(2, 33);
            w.bytes_field(5, &[100, 0, 0, 0, b'v', b'd', b'f']);
            w.uint32_field(6, 7);
        }

        let mut packed_unknown = Vec::new();
        Writer::new(&mut packed_unknown).varint(888);

        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.submessage_field(1, &app);
            w.uint32_field(2, 404);
            w.submessage_field(3, &package);
            w.bytes_field(4, &packed_unknown);
            w.bool_field(6, true);
            w.uint32_field(7, 1024);
            w.string_field(8, "cdn.example");
        }

        let parsed = CMsgClientPICSProductInfoResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.apps[0].appid, 480);
        assert_eq!(parsed.unknown_appids, [404]);
        assert_eq!(parsed.unknown_packageids, [888]);
        assert_eq!(parsed.packages[0].buffer, [100, 0, 0, 0, b'v', b'd', b'f']);
        assert!(parsed.response_pending);
        assert_eq!(parsed.http_host, "cdn.example");
    }
}
