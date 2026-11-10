use crate::proto_wire::{Reader, Writer};

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CFamilyGroupsGetFamilyGroupRequest {
    pub family_groupid: u64,
}

impl CFamilyGroupsGetFamilyGroupRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        Writer::new(&mut out).uint64_field(1, self.family_groupid);
        out
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct FamilyGroupMember {
    pub steamid: u64,
}

impl FamilyGroupMember {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.steamid = reader.fixed64()?,
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
pub struct CFamilyGroupsGetFamilyGroupResponse {
    pub name: String,
    pub members: Vec<FamilyGroupMember>,
}

impl CFamilyGroupsGetFamilyGroupResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.name = reader.string()?,
                2 => msg
                    .members
                    .push(FamilyGroupMember::deserialize(reader.bytes()?)?),
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
    fn parses_family_group_members() {
        let mut member = Vec::new();
        Writer::new(&mut member).fixed64_field(1, 123);

        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.string_field(1, "Family");
            w.submessage_field(2, &member);
        }

        let parsed = CFamilyGroupsGetFamilyGroupResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.name, "Family");
        assert_eq!(parsed.members[0].steamid, 123);
    }
}
