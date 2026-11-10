use crate::proto_wire::{WireType, Writer};

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct GamePlayedProcessInfo {
    pub process_id: u32,
    pub process_id_parent: u32,
    pub parent_is_steam: bool,
}

impl GamePlayedProcessInfo {
    fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field_force(1, self.process_id);
        w.uint32_field_force(2, self.process_id_parent);
        w.bool_field_force(3, self.parent_is_steam);
        out
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct GamePlayedEntry {
    pub game_id: u64,
    pub process_id: u32,
    pub owner_id: u32,
    pub launch_source: u32,
    pub game_build_id: u32,
    pub process_id_list: Vec<GamePlayedProcessInfo>,
}

impl GamePlayedEntry {
    fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        if self.game_id != 0 {
            w.tag(2, WireType::Fixed64);
            w.raw_bytes(&self.game_id.to_le_bytes());
        }
        w.uint32_field_force(9, self.process_id);
        w.uint32_field_force(12, self.owner_id);
        w.uint32_field_force(21, self.launch_source);
        w.uint32_field_force(26, self.game_build_id);
        for process in &self.process_id_list {
            w.submessage_field(32, &process.serialize());
        }
        out
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientGamesPlayed {
    pub games_played: Vec<GamePlayedEntry>,
    pub client_os_type: u32,
}

impl CMsgClientGamesPlayed {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        for game in &self.games_played {
            w.submessage_field(1, &game.serialize());
        }
        w.uint32_field(2, self.client_os_type);
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto_wire::Reader;

    #[test]
    fn games_played_force_emits_zero_process_fields() {
        let msg = CMsgClientGamesPlayed {
            games_played: vec![GamePlayedEntry {
                game_id: 480,
                process_id_list: vec![GamePlayedProcessInfo::default()],
                ..Default::default()
            }],
            client_os_type: 16,
        };
        let body = msg.serialize();
        let mut reader = Reader::new(&body);
        let tag = reader.next_tag().unwrap();
        assert_eq!(tag.field_number, 1);
        let game = reader.bytes().unwrap();
        assert!(game.windows(2).any(|w| w == [0x48, 0x00])); // field 9 process_id = 0
    }
}
