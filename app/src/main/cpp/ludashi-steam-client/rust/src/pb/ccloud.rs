use crate::proto_wire::{Reader, Writer};

pub struct CCloudGetUserQuotaRequest;

impl CCloudGetUserQuotaRequest {
    pub fn serialize(&self) -> Vec<u8> {
        Vec::new()
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CCloudGetUserQuotaResponse {
    pub total_bytes: u64,
    pub used_bytes: u64,
}

impl CCloudGetUserQuotaResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut r = Reader::new(body);
        let mut m = Self::default();
        while !r.eof() {
            let Some(t) = r.next_tag() else {
                return r.ok().then_some(m);
            };
            match t.field_number {
                1 => m.total_bytes = r.u64()?,
                2 => m.used_bytes = r.u64()?,
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
pub struct CCloudGetAppFileChangelistRequest {
    pub appid: u32,
    pub synced_change_number: u64,
}

impl CCloudGetAppFileChangelistRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.appid);
        w.uint64_field(2, self.synced_change_number);
        out
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CCloudAppFileInfo {
    pub file_name: String,
    pub sha_file: Vec<u8>,
    pub time_stamp: u64,
    pub raw_file_size: u32,
    pub persist_state: i32,
    pub platforms_to_sync: u32,
    pub path_prefix_index: u32,
    pub machine_name_index: u32,
}

impl CCloudAppFileInfo {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut r = Reader::new(body);
        let mut m = Self::default();
        while !r.eof() {
            let Some(t) = r.next_tag() else {
                return r.ok().then_some(m);
            };
            match t.field_number {
                1 => m.file_name = r.string()?,
                2 => m.sha_file = r.bytes()?.to_vec(),
                3 => m.time_stamp = r.u64()?,
                4 => m.raw_file_size = r.u32()?,
                5 => m.persist_state = r.u64()? as u32 as i32,
                6 => m.platforms_to_sync = r.u32()?,
                7 => m.path_prefix_index = r.u32()?,
                8 => m.machine_name_index = r.u32()?,
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
pub struct CCloudGetAppFileChangelistResponse {
    pub current_change_number: u64,
    pub files: Vec<CCloudAppFileInfo>,
    pub is_only_delta: bool,
    pub path_prefixes: Vec<String>,
    pub machine_names: Vec<String>,
    pub app_buildid_hwm: u64,
}

impl CCloudGetAppFileChangelistResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut r = Reader::new(body);
        let mut m = Self::default();
        while !r.eof() {
            let Some(t) = r.next_tag() else {
                return r.ok().then_some(m);
            };
            match t.field_number {
                1 => m.current_change_number = r.u64()?,
                2 => m.files.push(CCloudAppFileInfo::deserialize(r.bytes()?)?),
                3 => m.is_only_delta = r.boolean()?,
                4 => m.path_prefixes.push(r.string()?),
                5 => m.machine_names.push(r.string()?),
                6 => m.app_buildid_hwm = r.u64()?,
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

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CCloudClientFileDownloadRequest {
    pub appid: u32,
    pub filename: String,
    pub realm: u32,
}

impl Default for CCloudClientFileDownloadRequest {
    fn default() -> Self {
        Self {
            appid: 0,
            filename: String::new(),
            realm: 1,
        }
    }
}

impl CCloudClientFileDownloadRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.appid);
        w.string_field(2, &self.filename);
        w.uint32_field(3, self.realm);
        out
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CCloudHTTPHeader {
    pub name: String,
    pub value: String,
}

impl CCloudHTTPHeader {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut r = Reader::new(body);
        let mut m = Self::default();
        while !r.eof() {
            let Some(t) = r.next_tag() else {
                return r.ok().then_some(m);
            };
            match t.field_number {
                1 => m.name = r.string()?,
                2 => m.value = r.string()?,
                _ => {
                    if !r.skip(t.wire_type) {
                        return None;
                    }
                }
            }
        }
        Some(m)
    }

    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.string_field(1, &self.name);
        w.string_field(2, &self.value);
        out
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CCloudClientFileDownloadResponse {
    pub file_size: u32,
    pub raw_file_size: u32,
    pub sha_file: Vec<u8>,
    pub time_stamp: u64,
    pub is_explicit_delete: bool,
    pub url_host: String,
    pub url_path: String,
    pub use_https: bool,
    pub request_headers: Vec<CCloudHTTPHeader>,
    pub encrypted: bool,
}

impl CCloudClientFileDownloadResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut r = Reader::new(body);
        let mut m = Self::default();
        while !r.eof() {
            let Some(t) = r.next_tag() else {
                return r.ok().then_some(m);
            };
            match t.field_number {
                1 => {
                    if !r.skip(t.wire_type) {
                        return None;
                    }
                }
                2 => m.file_size = r.u32()?,
                3 => m.raw_file_size = r.u32()?,
                4 => m.sha_file = r.bytes()?.to_vec(),
                5 => m.time_stamp = r.u64()?,
                6 => m.is_explicit_delete = r.boolean()?,
                7 => m.url_host = r.string()?,
                8 => m.url_path = r.string()?,
                9 => m.use_https = r.boolean()?,
                10 => m
                    .request_headers
                    .push(CCloudHTTPHeader::deserialize(r.bytes()?)?),
                11 => m.encrypted = r.boolean()?,
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
pub struct CCloudBeginAppUploadBatchRequest {
    pub appid: u32,
    pub machine_name: String,
    pub files_to_upload: Vec<String>,
    pub files_to_delete: Vec<String>,
    pub client_id: u64,
    pub app_build_id: u64,
}

impl CCloudBeginAppUploadBatchRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.appid);
        w.string_field(2, &self.machine_name);
        for file in &self.files_to_upload {
            w.string_field(3, file);
        }
        for file in &self.files_to_delete {
            w.string_field(4, file);
        }
        w.uint64_field(5, self.client_id);
        w.uint64_field(6, self.app_build_id);
        out
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CCloudBeginAppUploadBatchResponse {
    pub batch_id: u64,
    pub app_change_number: u64,
}

impl CCloudBeginAppUploadBatchResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut r = Reader::new(body);
        let mut m = Self::default();
        while !r.eof() {
            let Some(t) = r.next_tag() else {
                return r.ok().then_some(m);
            };
            match t.field_number {
                1 => m.batch_id = r.u64()?,
                4 => m.app_change_number = r.u64()?,
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
pub struct CCloudClientBeginFileUploadRequest {
    pub appid: u32,
    pub file_size: u32,
    pub raw_file_size: u32,
    pub file_sha: Vec<u8>,
    pub time_stamp: u64,
    pub filename: String,
    pub upload_batch_id: u64,
}

impl CCloudClientBeginFileUploadRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.appid);
        w.uint32_field(2, self.file_size);
        w.uint32_field(3, self.raw_file_size);
        w.bytes_field(4, &self.file_sha);
        w.uint64_field(5, self.time_stamp);
        w.string_field(6, &self.filename);
        w.uint64_field(13, self.upload_batch_id);
        out
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CCloudUploadBlockDetails {
    pub url_host: String,
    pub url_path: String,
    pub use_https: bool,
    pub http_method: i32,
    pub request_headers: Vec<CCloudHTTPHeader>,
    pub block_offset: u64,
    pub block_length: u32,
    pub explicit_body_data: Vec<u8>,
    pub may_parallelize: bool,
}

impl CCloudUploadBlockDetails {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut r = Reader::new(body);
        let mut m = Self::default();
        while !r.eof() {
            let Some(t) = r.next_tag() else {
                return r.ok().then_some(m);
            };
            match t.field_number {
                1 => m.url_host = r.string()?,
                2 => m.url_path = r.string()?,
                3 => m.use_https = r.boolean()?,
                4 => m.http_method = r.u64()? as u32 as i32,
                5 => m
                    .request_headers
                    .push(CCloudHTTPHeader::deserialize(r.bytes()?)?),
                6 => m.block_offset = r.u64()?,
                7 => m.block_length = r.u32()?,
                8 => m.explicit_body_data = r.bytes()?.to_vec(),
                9 => m.may_parallelize = r.boolean()?,
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
pub struct CCloudClientBeginFileUploadResponse {
    pub encrypt_file: bool,
    pub block_requests: Vec<CCloudUploadBlockDetails>,
}

impl CCloudClientBeginFileUploadResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut r = Reader::new(body);
        let mut m = Self::default();
        while !r.eof() {
            let Some(t) = r.next_tag() else {
                return r.ok().then_some(m);
            };
            match t.field_number {
                1 => m.encrypt_file = r.boolean()?,
                2 => m
                    .block_requests
                    .push(CCloudUploadBlockDetails::deserialize(r.bytes()?)?),
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
pub struct CCloudClientCommitFileUploadRequest {
    pub transfer_succeeded: bool,
    pub appid: u32,
    pub file_sha: Vec<u8>,
    pub filename: String,
}

impl CCloudClientCommitFileUploadRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.bool_field_force(1, self.transfer_succeeded);
        w.uint32_field(2, self.appid);
        w.bytes_field(3, &self.file_sha);
        w.string_field(4, &self.filename);
        out
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CCloudClientCommitFileUploadResponse {
    pub file_committed: bool,
}

impl CCloudClientCommitFileUploadResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut r = Reader::new(body);
        let mut m = Self::default();
        while !r.eof() {
            let Some(t) = r.next_tag() else {
                return r.ok().then_some(m);
            };
            match t.field_number {
                1 => m.file_committed = r.boolean()?,
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
pub struct CCloudCompleteAppUploadBatchRequest {
    pub appid: u32,
    pub batch_id: u64,
    pub batch_eresult: u32,
}

impl CCloudCompleteAppUploadBatchRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.appid);
        w.uint64_field(2, self.batch_id);
        w.uint32_field(3, self.batch_eresult);
        out
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CCloudAppLaunchIntentRequest {
    pub appid: u32,
    pub client_id: u64,
    pub machine_name: String,
    pub ignore_pending_operations: bool,
    pub os_type: i32,
}

impl CCloudAppLaunchIntentRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.appid);
        w.uint64_field(2, self.client_id);
        w.string_field(3, &self.machine_name);
        w.bool_field(4, self.ignore_pending_operations);
        w.int32_field(5, self.os_type);
        out
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CCloudAppLaunchIntentResponse {
    pub pending_operation_codes: Vec<i32>,
}

impl CCloudAppLaunchIntentResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut r = Reader::new(body);
        let mut m = Self::default();
        while !r.eof() {
            let Some(t) = r.next_tag() else {
                return r.ok().then_some(m);
            };
            if t.field_number == 1 {
                m.pending_operation_codes
                    .push(parse_pending_operation(r.bytes()?)?);
            } else if !r.skip(t.wire_type) {
                return None;
            }
        }
        Some(m)
    }
}

fn parse_pending_operation(body: &[u8]) -> Option<i32> {
    let mut r = Reader::new(body);
    let mut op = 0;
    while !r.eof() {
        let Some(t) = r.next_tag() else {
            return r.ok().then_some(op);
        };
        if t.field_number == 1 {
            op = r.u64()? as u32 as i32;
        } else if !r.skip(t.wire_type) {
            return None;
        }
    }
    Some(op)
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CCloudAppExitSyncDoneNotification {
    pub appid: u32,
    pub client_id: u64,
    pub uploads_completed: bool,
    pub uploads_required: bool,
}

impl CCloudAppExitSyncDoneNotification {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.appid);
        w.uint64_field(2, self.client_id);
        w.bool_field(3, self.uploads_completed);
        w.bool_field(4, self.uploads_required);
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_changelist_file_info() {
        let mut file = Vec::new();
        {
            let mut w = Writer::new(&mut file);
            w.string_field(1, "save.dat");
            w.bytes_field(2, &[1; 20]);
            w.uint64_field(3, 100);
            w.uint32_field(4, 12);
            w.uint32_field(5, 2);
            w.uint32_field(6, 0xffff);
            w.uint32_field(7, 1);
            w.uint32_field(8, 2);
        }

        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.uint64_field(1, 55);
            w.submessage_field(2, &file);
            w.bool_field(3, true);
            w.string_field(4, "remote/");
            w.string_field(5, "machine");
            w.uint64_field(6, 777);
        }

        let parsed = CCloudGetAppFileChangelistResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.current_change_number, 55);
        assert_eq!(parsed.files[0].file_name, "save.dat");
        assert_eq!(parsed.files[0].persist_state, 2);
        assert_eq!(parsed.path_prefixes, ["remote/"]);
        assert_eq!(parsed.machine_names, ["machine"]);
    }

    #[test]
    fn parses_download_and_upload_http_headers() {
        let header = CCloudHTTPHeader {
            name: "Auth".into(),
            value: "token".into(),
        }
        .serialize();

        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.uint32_field(2, 10);
            w.uint32_field(3, 20);
            w.bytes_field(4, &[2; 20]);
            w.uint64_field(5, 1000);
            w.string_field(7, "host");
            w.string_field(8, "/path");
            w.bool_field(9, true);
            w.submessage_field(10, &header);
            w.bool_field(11, true);
        }

        let parsed = CCloudClientFileDownloadResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.file_size, 10);
        assert_eq!(parsed.request_headers[0].name, "Auth");
        assert!(parsed.use_https);
        assert!(parsed.encrypted);
    }

    #[test]
    fn commit_upload_force_emits_false_transfer_status() {
        let body = CCloudClientCommitFileUploadRequest {
            transfer_succeeded: false,
            appid: 480,
            file_sha: vec![1, 2],
            filename: "save.dat".into(),
        }
        .serialize();
        assert_eq!(&body[..4], &[8, 0, 16, 224]);
    }

    #[test]
    fn parses_launch_pending_operations() {
        let mut op = Vec::new();
        Writer::new(&mut op).uint32_field(1, 4);
        let mut body = Vec::new();
        Writer::new(&mut body).submessage_field(1, &op);
        let parsed = CCloudAppLaunchIntentResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.pending_operation_codes, [4]);
    }
}
