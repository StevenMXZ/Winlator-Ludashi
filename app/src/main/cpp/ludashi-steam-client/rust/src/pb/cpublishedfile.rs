use crate::proto_wire::{Reader, WireType, Writer};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CPublishedFileGetUserFilesRequest {
    pub steamid: u64,
    pub appid: u32,
    pub page: u32,
    pub numperpage: u32,
    pub request_type: String,
    pub filetype: u32,
}

impl Default for CPublishedFileGetUserFilesRequest {
    fn default() -> Self {
        Self {
            steamid: 0,
            appid: 0,
            page: 1,
            numperpage: 1,
            request_type: String::new(),
            filetype: 0,
        }
    }
}

impl CPublishedFileGetUserFilesRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.fixed64_field(1, self.steamid);
        w.uint32_field(2, self.appid);
        w.uint32_field(4, self.page);
        w.uint32_field(5, self.numperpage);
        w.string_field(6, &self.request_type);
        w.uint32_field(14, self.filetype);
        out
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct PublishedFileDetails {
    pub result: u32,
    pub publishedfileid: u64,
    pub consumer_appid: u32,
    pub filename: String,
    pub file_size: u64,
    pub file_url: String,
    pub preview_url: String,
    pub hcontent_file: u64,
    pub title: String,
    pub time_updated: u32,
}

impl PublishedFileDetails {
    pub fn parse(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            let handled = match (tag.field_number, tag.wire_type) {
                (1, WireType::Varint) => {
                    msg.result = reader.u32()?;
                    true
                }
                (2, WireType::Varint) => {
                    msg.publishedfileid = reader.u64()?;
                    true
                }
                (5, WireType::Varint) => {
                    msg.consumer_appid = reader.u32()?;
                    true
                }
                (7, WireType::LengthDelimited) => {
                    msg.filename = reader.string()?;
                    true
                }
                (8, WireType::Varint) => {
                    msg.file_size = reader.u64()?;
                    true
                }
                (10, WireType::LengthDelimited) => {
                    msg.file_url = reader.string()?;
                    true
                }
                (11, WireType::LengthDelimited) => {
                    msg.preview_url = reader.string()?;
                    true
                }
                (14, WireType::Fixed64) => {
                    msg.hcontent_file = reader.fixed64()?;
                    true
                }
                (16, WireType::LengthDelimited) => {
                    msg.title = reader.string()?;
                    true
                }
                (20, WireType::Varint) => {
                    msg.time_updated = reader.u32()?;
                    true
                }
                _ => false,
            };
            if !handled && !reader.skip(tag.wire_type) {
                return None;
            }
        }
        Some(msg)
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CPublishedFileGetUserFilesResponse {
    pub total: u32,
    pub startindex: u32,
    pub publishedfiledetails: Vec<PublishedFileDetails>,
}

impl CPublishedFileGetUserFilesResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            let handled = match (tag.field_number, tag.wire_type) {
                (1, WireType::Varint) => {
                    msg.total = reader.u32()?;
                    true
                }
                (2, WireType::Varint) => {
                    msg.startindex = reader.u32()?;
                    true
                }
                (3, WireType::LengthDelimited) => {
                    msg.publishedfiledetails
                        .push(PublishedFileDetails::parse(reader.bytes()?)?);
                    true
                }
                _ => false,
            };
            if !handled && !reader.skip(tag.wire_type) {
                return None;
            }
        }
        Some(msg)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_user_files_response_and_skips_schema_drift() {
        let mut detail = Vec::new();
        {
            let mut w = Writer::new(&mut detail);
            w.uint32_field(1, 1);
            w.uint64_field(2, 99);
            w.uint32_field(5, 480);
            w.string_field(7, "file.bin");
            w.uint64_field(8, 1024);
            w.string_field(10, "https://example/file");
            w.string_field(11, "https://example/preview");
            w.fixed64_field(14, 777);
            w.string_field(16, "Workshop Item");
            w.uint32_field(20, 123456);
            w.fixed64_field(5, 0xfeed);
        }

        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.uint32_field(1, 1);
            w.uint32_field(2, 0);
            w.submessage_field(3, &detail);
        }

        let parsed = CPublishedFileGetUserFilesResponse::deserialize(&body).unwrap();
        let detail = &parsed.publishedfiledetails[0];
        assert_eq!(parsed.total, 1);
        assert_eq!(detail.publishedfileid, 99);
        assert_eq!(detail.consumer_appid, 480);
        assert_eq!(detail.hcontent_file, 777);
        assert_eq!(detail.title, "Workshop Item");
    }
}
