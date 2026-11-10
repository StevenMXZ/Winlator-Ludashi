use crate::proto_wire::Writer;

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientKickPlayingSession {
    pub only_stop_game: bool,
}

impl CMsgClientKickPlayingSession {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        Writer::new(&mut out).bool_field_force(1, self.only_stop_game);
        out
    }
}
