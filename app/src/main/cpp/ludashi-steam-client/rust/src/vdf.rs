#[derive(Clone, Debug, PartialEq)]
pub enum KVValue {
    Object,
    String(String),
    Int32(i32),
    Float32(f32),
    UInt32(u32),
    WideString(Vec<u16>),
    UInt64(u64),
    Int64(i64),
}

#[derive(Clone, Debug, PartialEq)]
pub struct KVNode {
    pub name: String,
    pub value: KVValue,
    pub children: Vec<KVNode>,
}

impl KVNode {
    pub fn new(name: impl Into<String>) -> Self {
        Self {
            name: name.into(),
            value: KVValue::Object,
            children: Vec::new(),
        }
    }

    pub fn is_object(&self) -> bool {
        matches!(self.value, KVValue::Object)
    }

    pub fn child(&self, key: &str) -> Option<&KVNode> {
        self.children
            .iter()
            .find(|c| c.name.eq_ignore_ascii_case(key))
    }

    pub fn as_string(&self, fallback: &str) -> String {
        match &self.value {
            KVValue::String(v) => v.clone(),
            KVValue::Int32(v) => v.to_string(),
            KVValue::Float32(v) => v.to_string(),
            KVValue::UInt32(v) => v.to_string(),
            KVValue::UInt64(v) => v.to_string(),
            KVValue::Int64(v) => v.to_string(),
            _ => fallback.to_string(),
        }
    }

    pub fn as_int(&self, fallback: i64) -> i64 {
        match &self.value {
            KVValue::Int32(v) => *v as i64,
            KVValue::Int64(v) => *v,
            KVValue::UInt32(v) => *v as i64,
            KVValue::UInt64(v) => *v as i64,
            KVValue::String(v) => v.parse().unwrap_or(fallback),
            _ => fallback,
        }
    }

    pub fn as_uint(&self, fallback: u64) -> u64 {
        match &self.value {
            KVValue::UInt32(v) => *v as u64,
            KVValue::UInt64(v) => *v,
            KVValue::Int32(v) => *v as u64,
            KVValue::Int64(v) => *v as u64,
            KVValue::String(v) => v.parse().unwrap_or(fallback),
            _ => fallback,
        }
    }

    pub fn as_bool(&self, fallback: bool) -> bool {
        match &self.value {
            KVValue::Int32(v) => *v != 0,
            KVValue::Int64(v) => *v != 0,
            KVValue::UInt32(v) => *v != 0,
            KVValue::UInt64(v) => *v != 0,
            KVValue::String(v)
                if v == "1" || v.eq_ignore_ascii_case("true") || v.eq_ignore_ascii_case("yes") =>
            {
                true
            }
            KVValue::String(v)
                if v == "0" || v.eq_ignore_ascii_case("false") || v.eq_ignore_ascii_case("no") =>
            {
                false
            }
            _ => fallback,
        }
    }
}

const TYPE_NONE: u8 = 0x00;
const TYPE_STRING: u8 = 0x01;
const TYPE_INT32: u8 = 0x02;
const TYPE_FLOAT32: u8 = 0x03;
const TYPE_POINTER: u8 = 0x04;
const TYPE_WIDE_STRING: u8 = 0x05;
const TYPE_COLOR: u8 = 0x06;
const TYPE_UINT64: u8 = 0x07;
const TYPE_END: u8 = 0x08;
const TYPE_INT64: u8 = 0x09;
const TYPE_END_ALT: u8 = 0x0b;

struct Cursor<'a> {
    buf: &'a [u8],
    pos: usize,
    ok: bool,
}

impl<'a> Cursor<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Self {
            buf,
            pos: 0,
            ok: true,
        }
    }

    fn read_u8(&mut self) -> u8 {
        if self.pos + 1 > self.buf.len() {
            self.ok = false;
            return 0;
        }
        let v = self.buf[self.pos];
        self.pos += 1;
        v
    }

    fn read_u32_le(&mut self) -> u32 {
        if self.pos + 4 > self.buf.len() {
            self.ok = false;
            return 0;
        }
        let v = u32::from_le_bytes(self.buf[self.pos..self.pos + 4].try_into().unwrap());
        self.pos += 4;
        v
    }

    fn read_u64_le(&mut self) -> u64 {
        let lo = self.read_u32_le() as u64;
        let hi = self.read_u32_le() as u64;
        lo | (hi << 32)
    }

    fn read_f32_le(&mut self) -> f32 {
        f32::from_bits(self.read_u32_le())
    }

    fn read_cstring(&mut self) -> String {
        let mut out = Vec::new();
        while self.pos < self.buf.len() {
            let b = self.buf[self.pos];
            self.pos += 1;
            if b == 0 {
                return String::from_utf8_lossy(&out).into_owned();
            }
            out.push(b);
        }
        self.ok = false;
        String::from_utf8_lossy(&out).into_owned()
    }

    fn read_wide_cstring(&mut self) -> Vec<u16> {
        let mut out = Vec::new();
        while self.pos + 1 < self.buf.len() {
            let u = u16::from_le_bytes([self.buf[self.pos], self.buf[self.pos + 1]]);
            self.pos += 2;
            if u == 0 {
                return out;
            }
            out.push(u);
        }
        self.ok = false;
        out
    }
}

fn parse_value(cursor: &mut Cursor<'_>, ty: u8, node: &mut KVNode) -> bool {
    match ty {
        TYPE_NONE => {
            while cursor.ok {
                let inner_type = cursor.read_u8();
                if !cursor.ok {
                    return false;
                }
                if inner_type == TYPE_END || inner_type == TYPE_END_ALT {
                    return true;
                }
                let name = cursor.read_cstring();
                if !cursor.ok {
                    return false;
                }
                let mut child = KVNode::new(name);
                if !parse_value(cursor, inner_type, &mut child) {
                    return false;
                }
                node.children.push(child);
            }
            false
        }
        TYPE_STRING => {
            node.value = KVValue::String(cursor.read_cstring());
            cursor.ok
        }
        TYPE_INT32 => {
            node.value = KVValue::Int32(cursor.read_u32_le() as i32);
            cursor.ok
        }
        TYPE_FLOAT32 => {
            node.value = KVValue::Float32(cursor.read_f32_le());
            cursor.ok
        }
        TYPE_POINTER | TYPE_COLOR => {
            node.value = KVValue::UInt32(cursor.read_u32_le());
            cursor.ok
        }
        TYPE_WIDE_STRING => {
            node.value = KVValue::WideString(cursor.read_wide_cstring());
            cursor.ok
        }
        TYPE_UINT64 => {
            node.value = KVValue::UInt64(cursor.read_u64_le());
            cursor.ok
        }
        TYPE_INT64 => {
            node.value = KVValue::Int64(cursor.read_u64_le() as i64);
            cursor.ok
        }
        _ => false,
    }
}

pub fn parse_binary(body: &[u8]) -> Option<KVNode> {
    if body.is_empty() {
        return None;
    }
    let mut cursor = Cursor::new(body);
    let ty = cursor.read_u8();
    if !cursor.ok {
        return None;
    }
    let mut root = KVNode::new(cursor.read_cstring());
    if !cursor.ok || !parse_value(&mut cursor, ty, &mut root) {
        return None;
    }
    Some(root)
}

pub fn parse_binary_package(body: &[u8]) -> Option<(u32, KVNode)> {
    if body.len() < 4 {
        return None;
    }
    let package_id = u32::from_le_bytes(body[..4].try_into().unwrap());
    Some((package_id, parse_binary(&body[4..])?))
}

pub fn parse_auto(body: &[u8]) -> Option<KVNode> {
    let first = body
        .iter()
        .copied()
        .find(|b| !matches!(b, b' ' | b'\t' | b'\n' | b'\r'))?;
    if first == b'"' {
        parse_text(body)
    } else {
        parse_binary(body)
    }
}

struct TextCursor<'a> {
    buf: &'a [u8],
    pos: usize,
    ok: bool,
}

impl<'a> TextCursor<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Self {
            buf,
            pos: 0,
            ok: true,
        }
    }

    fn eof(&self) -> bool {
        self.pos >= self.buf.len()
    }

    fn peek(&self) -> u8 {
        self.buf.get(self.pos).copied().unwrap_or(0)
    }

    fn next(&mut self) -> u8 {
        let b = self.peek();
        if !self.eof() {
            self.pos += 1;
        }
        b
    }

    fn skip_ws_and_comments(&mut self) {
        while self.pos < self.buf.len() {
            match self.buf[self.pos] {
                b' ' | b'\t' | b'\n' | b'\r' => self.pos += 1,
                b'/' if self.buf.get(self.pos + 1) == Some(&b'/') => {
                    self.pos += 2;
                    while self.pos < self.buf.len() && self.buf[self.pos] != b'\n' {
                        self.pos += 1;
                    }
                }
                b'/' if self.buf.get(self.pos + 1) == Some(&b'*') => {
                    self.pos += 2;
                    while self.pos + 1 < self.buf.len()
                        && !(self.buf[self.pos] == b'*' && self.buf[self.pos + 1] == b'/')
                    {
                        self.pos += 1;
                    }
                    self.pos = (self.pos + 2).min(self.buf.len());
                }
                _ => break,
            }
        }
    }

    fn read_token(&mut self) -> Option<String> {
        self.skip_ws_and_comments();
        if self.eof() {
            return None;
        }
        if self.peek() == b'"' {
            self.pos += 1;
            let mut out = Vec::new();
            while !self.eof() {
                let c = self.next();
                if c == b'"' {
                    return Some(String::from_utf8_lossy(&out).into_owned());
                }
                if c == b'\\' && !self.eof() {
                    out.push(match self.next() {
                        b'n' => b'\n',
                        b't' => b'\t',
                        b'r' => b'\r',
                        b'\\' => b'\\',
                        b'"' => b'"',
                        other => other,
                    });
                } else {
                    out.push(c);
                }
            }
            self.ok = false;
            return None;
        }

        let start = self.pos;
        while !self.eof() {
            let c = self.peek();
            if matches!(c, b' ' | b'\t' | b'\n' | b'\r' | b'{' | b'}' | b'"') {
                break;
            }
            self.pos += 1;
        }
        (self.pos > start).then(|| String::from_utf8_lossy(&self.buf[start..self.pos]).into_owned())
    }
}

fn parse_text_object(cursor: &mut TextCursor<'_>, parent: &mut KVNode) -> bool {
    loop {
        cursor.skip_ws_and_comments();
        if cursor.eof() {
            return true;
        }
        if cursor.peek() == b'}' {
            cursor.pos += 1;
            return true;
        }
        let Some(key) = cursor.read_token() else {
            return cursor.ok;
        };
        cursor.skip_ws_and_comments();
        if cursor.eof() {
            return false;
        }
        let mut child = KVNode::new(key);
        if cursor.peek() == b'{' {
            cursor.pos += 1;
            if !parse_text_object(cursor, &mut child) {
                return false;
            }
        } else {
            let Some(value) = cursor.read_token() else {
                return false;
            };
            child.value = KVValue::String(value);
        }
        parent.children.push(child);
    }
}

pub fn parse_text(body: &[u8]) -> Option<KVNode> {
    let mut cursor = TextCursor::new(body);
    cursor.skip_ws_and_comments();
    let key = cursor.read_token()?;
    cursor.skip_ws_and_comments();
    if cursor.peek() != b'{' {
        return None;
    }
    cursor.pos += 1;
    let mut root = KVNode::new(key);
    parse_text_object(&mut cursor, &mut root).then_some(root)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_text_vdf_with_comments_and_case_insensitive_lookup() {
        let text = br#"
            // comment
            "appinfo" {
                "appid" "480"
                "Common" { "name" "Spacewar" "type" "Game" }
                /* block */ "enabled" "true"
            }
        "#;
        let root = parse_text(text).unwrap();
        assert_eq!(root.name, "appinfo");
        assert_eq!(root.child("APPID").unwrap().as_int(0), 480);
        assert_eq!(
            root.child("common")
                .unwrap()
                .child("NAME")
                .unwrap()
                .as_string(""),
            "Spacewar"
        );
        assert!(root.child("enabled").unwrap().as_bool(false));
    }

    #[test]
    fn parses_binary_tree() {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&[TYPE_NONE]);
        bytes.extend_from_slice(b"root\0");
        bytes.extend_from_slice(&[TYPE_STRING]);
        bytes.extend_from_slice(b"name\0Spacewar\0");
        bytes.extend_from_slice(&[TYPE_INT32]);
        bytes.extend_from_slice(b"appid\0");
        bytes.extend_from_slice(&480i32.to_le_bytes());
        bytes.extend_from_slice(&[TYPE_END]);

        let root = parse_binary(&bytes).unwrap();
        assert!(root.is_object());
        assert_eq!(root.child("name").unwrap().as_string(""), "Spacewar");
        assert_eq!(root.child("appid").unwrap().as_int(0), 480);
    }

    #[test]
    fn parses_package_prefix() {
        let mut bytes = 123u32.to_le_bytes().to_vec();
        bytes.extend_from_slice(&[TYPE_NONE]);
        bytes.extend_from_slice(b"package\0");
        bytes.extend_from_slice(&[TYPE_END]);
        let (package_id, root) = parse_binary_package(&bytes).unwrap();
        assert_eq!(package_id, 123);
        assert_eq!(root.name, "package");
    }

    // Manifest gid/size/download: proves the parser reads download (the 3rd value)
    // correctly, ruling out a parser cause for the stale corrupt download sizes.
    #[test]
    fn parses_manifest_uint64_gid_size_download() {
        let gid: u64 = 8072044898226043193;
        let size: u64 = 30738676601;
        let download: u64 = 15000000000;
        let mut b = vec![TYPE_NONE];
        b.extend_from_slice(b"public\0");
        b.push(TYPE_UINT64);
        b.extend_from_slice(b"gid\0");
        b.extend_from_slice(&gid.to_le_bytes());
        b.push(TYPE_UINT64);
        b.extend_from_slice(b"size\0");
        b.extend_from_slice(&size.to_le_bytes());
        b.push(TYPE_UINT64);
        b.extend_from_slice(b"download\0");
        b.extend_from_slice(&download.to_le_bytes());
        b.push(TYPE_END);

        let root = parse_binary(&b).unwrap();
        assert_eq!(root.child("gid").unwrap().as_string(""), gid.to_string());
        assert_eq!(root.child("size").unwrap().as_string(""), size.to_string());
        assert_eq!(
            root.child("download").unwrap().as_string(""),
            download.to_string()
        );
    }

    #[test]
    fn parses_manifest_text_vdf() {
        let text = br#"
            "public"
            {
                "gid"		"8072044898226043193"
                "size"		"30738676601"
                "download"		"15000000000"
            }
        "#;
        let root = parse_text(text).unwrap();
        assert_eq!(root.child("size").unwrap().as_string(""), "30738676601");
        assert_eq!(root.child("download").unwrap().as_string(""), "15000000000");
    }
}
