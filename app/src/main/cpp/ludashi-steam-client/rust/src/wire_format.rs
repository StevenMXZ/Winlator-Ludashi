pub fn read_u16_le(p: &[u8]) -> u16 {
    u16::from_le_bytes([p[0], p[1]])
}

pub fn read_u32_le(p: &[u8]) -> u32 {
    u32::from_le_bytes([p[0], p[1], p[2], p[3]])
}

pub fn read_u64_le(p: &[u8]) -> u64 {
    u64::from_le_bytes([p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]])
}

pub fn write_u16_le(out: &mut [u8], v: u16) {
    out[..2].copy_from_slice(&v.to_le_bytes());
}

pub fn write_u32_le(out: &mut [u8], v: u32) {
    out[..4].copy_from_slice(&v.to_le_bytes());
}

pub fn write_u64_le(out: &mut [u8], v: u64) {
    out[..8].copy_from_slice(&v.to_le_bytes());
}

pub struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
    ok: bool,
}

impl<'a> Reader<'a> {
    pub fn new(buf: &'a [u8]) -> Self {
        Self {
            buf,
            pos: 0,
            ok: true,
        }
    }

    pub fn ok(&self) -> bool {
        self.ok
    }

    pub fn position(&self) -> usize {
        self.pos
    }

    pub fn remaining(&self) -> usize {
        self.buf.len().saturating_sub(self.pos)
    }

    pub fn u16(&mut self) -> u16 {
        if !self.check(2) {
            return 0;
        }
        let v = read_u16_le(&self.buf[self.pos..]);
        self.pos += 2;
        v
    }

    pub fn u32(&mut self) -> u32 {
        if !self.check(4) {
            return 0;
        }
        let v = read_u32_le(&self.buf[self.pos..]);
        self.pos += 4;
        v
    }

    pub fn u64(&mut self) -> u64 {
        if !self.check(8) {
            return 0;
        }
        let v = read_u64_le(&self.buf[self.pos..]);
        self.pos += 8;
        v
    }

    pub fn bytes(&mut self, n: usize) -> &'a [u8] {
        if !self.check(n) {
            return &[];
        }
        let out = &self.buf[self.pos..self.pos + n];
        self.pos += n;
        out
    }

    fn check(&mut self, n: usize) -> bool {
        if !self.ok || self.remaining() < n {
            self.ok = false;
            return false;
        }
        true
    }
}

pub struct Writer<'a> {
    out: &'a mut Vec<u8>,
}

impl<'a> Writer<'a> {
    pub fn new(out: &'a mut Vec<u8>) -> Self {
        Self { out }
    }

    pub fn u16(&mut self, v: u16) {
        self.out.extend_from_slice(&v.to_le_bytes());
    }

    pub fn u32(&mut self, v: u32) {
        self.out.extend_from_slice(&v.to_le_bytes());
    }

    pub fn u64(&mut self, v: u64) {
        self.out.extend_from_slice(&v.to_le_bytes());
    }

    pub fn bytes(&mut self, bytes: &[u8]) {
        self.out.extend_from_slice(bytes);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reader_writer_little_endian_roundtrip() {
        let mut buf = Vec::new();
        let mut writer = Writer::new(&mut buf);
        writer.u16(0x1234);
        writer.u32(0x89ab_cdef);
        writer.u64(0x0123_4567_89ab_cdef);

        assert_eq!(
            buf,
            [0x34, 0x12, 0xef, 0xcd, 0xab, 0x89, 0xef, 0xcd, 0xab, 0x89, 0x67, 0x45, 0x23, 0x01]
        );

        let mut reader = Reader::new(&buf);
        assert_eq!(reader.u16(), 0x1234);
        assert_eq!(reader.u32(), 0x89ab_cdef);
        assert_eq!(reader.u64(), 0x0123_4567_89ab_cdef);
        assert!(reader.ok());
        assert_eq!(reader.remaining(), 0);
    }

    #[test]
    fn reader_short_input_flips_ok() {
        let mut reader = Reader::new(&[1, 2, 3]);
        assert_eq!(reader.u32(), 0);
        assert!(!reader.ok());
    }
}
