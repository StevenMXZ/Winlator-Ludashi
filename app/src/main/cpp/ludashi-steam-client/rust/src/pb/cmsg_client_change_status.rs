use crate::proto_wire::Writer;

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientChangeStatus {
    pub persona_state: u32,
    pub player_name: String,
    pub persona_set_by_user: bool,
    pub need_persona_response: bool,
}

impl CMsgClientChangeStatus {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field_force(1, self.persona_state);
        w.string_field(2, &self.player_name);
        if self.persona_set_by_user {
            w.bool_field(5, true);
        }
        if self.need_persona_response {
            w.bool_field(7, true);
        }
        out
    }
}
