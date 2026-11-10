use crate::proto_wire::Reader;

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientPlayingSessionState {
    pub playing_blocked: bool,
    pub playing_app: u32,
}

impl CMsgClientPlayingSessionState {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                2 => msg.playing_blocked = reader.boolean()?,
                3 => msg.playing_app = reader.u32()?,
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
    use crate::pb::cmsg_client_change_status::CMsgClientChangeStatus;
    use crate::pb::cmsg_client_kick_playing_session::CMsgClientKickPlayingSession;
    use crate::proto_wire::Writer;

    #[test]
    fn force_emits_zero_and_false_values() {
        assert_eq!(
            CMsgClientChangeStatus::default().serialize(),
            vec![0x08, 0x00]
        );
        assert_eq!(
            CMsgClientKickPlayingSession {
                only_stop_game: false
            }
            .serialize(),
            vec![0x08, 0x00]
        );
    }

    #[test]
    fn parses_playing_session_state() {
        let mut body = Vec::new();
        let mut w = Writer::new(&mut body);
        w.bool_field(2, true);
        w.uint32_field(3, 480);
        let parsed = CMsgClientPlayingSessionState::deserialize(&body).unwrap();
        assert!(parsed.playing_blocked);
        assert_eq!(parsed.playing_app, 480);
    }
}
