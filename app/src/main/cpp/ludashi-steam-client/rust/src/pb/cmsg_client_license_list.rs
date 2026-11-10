use crate::proto_wire::{Reader, WireType};

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct License {
    pub package_id: u32,
    pub time_created: u32,
    pub time_next_process: u32,
    pub minute_limit: i32,
    pub minutes_used: i32,
    pub payment_method: u32,
    pub flags: u32,
    pub purchase_country_code: String,
    pub license_type: u32,
    pub territory_code: i32,
    pub change_number: i32,
    pub owner_id: u32,
    pub initial_period: u32,
    pub initial_time_unit: u32,
    pub renewal_period: u32,
    pub renewal_time_unit: u32,
    pub access_token: u64,
    pub master_package_id: u32,
}

impl License {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.package_id = reader.u32()?,
                2 => {
                    if tag.wire_type != WireType::Fixed32 {
                        return None;
                    }
                    msg.time_created = reader.fixed32()?;
                }
                3 => {
                    if tag.wire_type != WireType::Fixed32 {
                        return None;
                    }
                    msg.time_next_process = reader.fixed32()?;
                }
                4 => msg.minute_limit = reader.i32()?,
                5 => msg.minutes_used = reader.i32()?,
                6 => msg.payment_method = reader.u32()?,
                7 => msg.flags = reader.u32()?,
                8 => msg.purchase_country_code = reader.string()?,
                9 => msg.license_type = reader.u32()?,
                10 => msg.territory_code = reader.i32()?,
                11 => msg.change_number = reader.i32()?,
                12 => msg.owner_id = reader.u32()?,
                13 => msg.initial_period = reader.u32()?,
                14 => msg.initial_time_unit = reader.u32()?,
                15 => msg.renewal_period = reader.u32()?,
                16 => msg.renewal_time_unit = reader.u32()?,
                17 => msg.access_token = reader.u64()?,
                18 => msg.master_package_id = reader.u32()?,
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
pub struct CMsgClientLicenseList {
    pub eresult: i32,
    pub licenses: Vec<License>,
}

impl Default for CMsgClientLicenseList {
    fn default() -> Self {
        Self {
            eresult: 2,
            licenses: Vec::new(),
        }
    }
}

impl CMsgClientLicenseList {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.eresult = reader.i32()?,
                2 => msg.licenses.push(License::deserialize(reader.bytes()?)?),
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
    use crate::proto_wire::Writer;

    #[test]
    fn parses_license_with_fixed_times_and_two_byte_tags() {
        let mut lic = Vec::new();
        {
            let mut w = Writer::new(&mut lic);
            w.uint32_field(1, 123);
            w.tag(2, WireType::Fixed32);
            w.raw_bytes(&1000u32.to_le_bytes());
            w.tag(3, WireType::Fixed32);
            w.raw_bytes(&2000u32.to_le_bytes());
            w.string_field(8, "US");
            w.uint32_field(16, 7);
            w.uint64_field(17, 0x1234);
            w.uint32_field(18, 456);
        }
        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.int32_field(1, 1);
            w.submessage_field(2, &lic);
        }
        let parsed = CMsgClientLicenseList::deserialize(&body).unwrap();
        assert_eq!(parsed.eresult, 1);
        assert_eq!(parsed.licenses[0].package_id, 123);
        assert_eq!(parsed.licenses[0].time_created, 1000);
        assert_eq!(parsed.licenses[0].renewal_time_unit, 7);
        assert_eq!(parsed.licenses[0].master_package_id, 456);
    }
}
