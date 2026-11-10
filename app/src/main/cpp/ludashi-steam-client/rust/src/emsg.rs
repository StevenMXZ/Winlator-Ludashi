#[derive(Clone, Copy, Debug, Default, Eq, Hash, PartialEq)]
#[repr(transparent)]
pub struct EMsg(pub u32);

impl EMsg {
    pub const INVALID: Self = Self(0);
    pub const MULTI: Self = Self(1);
    pub const CHANNEL_ENCRYPT_REQUEST: Self = Self(1303);
    pub const CHANNEL_ENCRYPT_RESPONSE: Self = Self(1304);
    pub const CHANNEL_ENCRYPT_RESULT: Self = Self(1305);
    pub const CLIENT_HELLO: Self = Self(9805);
    pub const CLIENT_LOGON: Self = Self(5514);
    pub const CLIENT_LOGON_RESPONSE: Self = Self(751);
    pub const CLIENT_LOG_OFF: Self = Self(706);
    pub const CLIENT_LOGGED_OFF: Self = Self(757);
    pub const CLIENT_HEART_BEAT: Self = Self(703);
    pub const CLIENT_GAMES_PLAYED_WITH_DATA_BLOB: Self = Self(5410);
    pub const CLIENT_KICK_PLAYING_SESSION: Self = Self(9601);
    pub const CLIENT_CHANGE_STATUS: Self = Self(716);
    pub const CLIENT_REQUEST_FRIEND_DATA: Self = Self(815);
    pub const CLIENT_SESSION_TOKEN: Self = Self(850);
    pub const CLIENT_SERVER_UNAVAILABLE: Self = Self(5500);
    pub const CLIENT_PERSONA_STATE: Self = Self(766);
    pub const CLIENT_FRIENDS_LIST: Self = Self(767);
    pub const CLIENT_REMOVE_FRIEND: Self = Self(714);
    pub const CLIENT_ADD_FRIEND: Self = Self(791);
    pub const CLIENT_ADD_FRIEND_RESPONSE: Self = Self(792);
    pub const CLIENT_FRIEND_PROFILE_INFO: Self = Self(5330);
    pub const CLIENT_FRIEND_PROFILE_INFO_RESPONSE: Self = Self(5331);
    pub const CLIENT_PLAYER_NICKNAME_LIST: Self = Self(5587);
    /// Steam level lookup for any set of account ids (verified against JavaSteam `EMsg.java`).
    pub const CLIENT_FS_GET_FRIENDS_STEAM_LEVELS: Self = Self(7528);
    pub const CLIENT_FS_GET_FRIENDS_STEAM_LEVELS_RESPONSE: Self = Self(7529);
    pub const CLIENT_PLAYING_SESSION_STATE: Self = Self(9600);
    pub const CLIENT_ACCOUNT_INFO: Self = Self(768);
    pub const CLIENT_EMAIL_ADDR_INFO: Self = Self(779);
    pub const CLIENT_LICENSE_LIST: Self = Self(780);
    /// Free/F2P/demo license grant (verified against JavaSteam `EMsg.java`).
    pub const CLIENT_REQUEST_FREE_LICENSE: Self = Self(5572);
    pub const CLIENT_REQUEST_FREE_LICENSE_RESPONSE: Self = Self(5573);
    pub const CLIENT_PICS_CHANGES_SINCE_REQUEST: Self = Self(8901);
    pub const CLIENT_PICS_CHANGES_SINCE_RESPONSE: Self = Self(8902);
    pub const CLIENT_PICS_PRODUCT_INFO_REQUEST: Self = Self(8903);
    pub const CLIENT_PICS_PRODUCT_INFO_RESPONSE: Self = Self(8904);
    pub const CLIENT_PICS_ACCESS_TOKEN_REQUEST: Self = Self(8905);
    pub const CLIENT_PICS_ACCESS_TOKEN_RESPONSE: Self = Self(8906);
    pub const CLIENT_GET_APP_OWNERSHIP_TICKET: Self = Self(857);
    pub const CLIENT_GET_APP_OWNERSHIP_TICKET_RESPONSE: Self = Self(858);
    pub const CLIENT_REQUEST_ENCRYPTED_APP_TICKET: Self = Self(5526);
    pub const CLIENT_REQUEST_ENCRYPTED_APP_TICKET_RESPONSE: Self = Self(5527);
    pub const CLIENT_GET_DEPOT_DECRYPTION_KEY: Self = Self(5438);
    pub const CLIENT_GET_DEPOT_DECRYPTION_KEY_RESPONSE: Self = Self(5439);
    pub const CLIENT_GET_USER_STATS: Self = Self(818);
    pub const CLIENT_GET_USER_STATS_RESPONSE: Self = Self(819);
    pub const CLIENT_STORE_USER_STATS_2: Self = Self(5466);
    pub const CLIENT_STORE_USER_STATS_RESPONSE: Self = Self(5467);
    pub const CLIENT_CHECK_APP_BETA_PASSWORD: Self = Self(5426);
    pub const CLIENT_CHECK_APP_BETA_PASSWORD_RESPONSE: Self = Self(5427);
    pub const SERVICE_METHOD: Self = Self(146);
    pub const SERVICE_METHOD_CALL_FROM_CLIENT: Self = Self(151);
    pub const SERVICE_METHOD_RESPONSE: Self = Self(147);
    pub const SERVICE_METHOD_SEND_TO_CLIENT: Self = Self(152);
    pub const SERVICE_METHOD_CALL_FROM_CLIENT_NON_AUTHED: Self = Self(9804);
    pub const CLIENT_MMS_CREATE_LOBBY: Self = Self(6601);
    pub const CLIENT_MMS_CREATE_LOBBY_RESPONSE: Self = Self(6602);
    pub const CLIENT_MMS_JOIN_LOBBY: Self = Self(6603);
    pub const CLIENT_MMS_JOIN_LOBBY_RESPONSE: Self = Self(6604);
    pub const CLIENT_MMS_LEAVE_LOBBY: Self = Self(6605);
    pub const CLIENT_MMS_LEAVE_LOBBY_RESPONSE: Self = Self(6606);
    pub const CLIENT_MMS_GET_LOBBY_LIST: Self = Self(6607);
    pub const CLIENT_MMS_GET_LOBBY_LIST_RESPONSE: Self = Self(6608);
    pub const CLIENT_MMS_SET_LOBBY_DATA: Self = Self(6609);
    pub const CLIENT_MMS_SET_LOBBY_DATA_RESPONSE: Self = Self(6610);
    pub const CLIENT_MMS_GET_LOBBY_DATA: Self = Self(6611);
    pub const CLIENT_MMS_LOBBY_DATA: Self = Self(6612);
    pub const CLIENT_MMS_SEND_LOBBY_CHAT_MSG: Self = Self(6613);
    pub const CLIENT_MMS_LOBBY_CHAT_MSG: Self = Self(6614);
    pub const CLIENT_MMS_SET_LOBBY_OWNER: Self = Self(6615);
    pub const CLIENT_MMS_SET_LOBBY_OWNER_RESPONSE: Self = Self(6616);
    pub const CLIENT_MMS_SET_LOBBY_GAME_SERVER: Self = Self(6617);
    pub const CLIENT_MMS_LOBBY_GAME_SERVER_SET: Self = Self(6618);
    pub const CLIENT_MMS_USER_JOINED_LOBBY: Self = Self(6619);
    pub const CLIENT_MMS_USER_LEFT_LOBBY: Self = Self(6620);
    pub const CLIENT_MMS_INVITE_TO_LOBBY: Self = Self(6621);
    pub const CLIENT_MMS_GET_LOBBY_STATUS: Self = Self(6626);
    pub const CLIENT_MMS_GET_LOBBY_STATUS_RESPONSE: Self = Self(6627);
}

pub const EMSG_PROTO_FLAG: u32 = 0x8000_0000;
pub const EMSG_MASK: u32 = 0x7fff_ffff;

pub const fn has_proto_flag(raw: u32) -> bool {
    (raw & EMSG_PROTO_FLAG) != 0
}

pub const fn strip_proto_flag(raw: u32) -> EMsg {
    EMsg(raw & EMSG_MASK)
}

pub const fn with_proto_flag(msg: EMsg) -> u32 {
    msg.0 | EMSG_PROTO_FLAG
}

#[derive(Clone, Copy, Debug, Default, Eq, Hash, PartialEq)]
#[repr(u32)]
pub enum EUniverse {
    #[default]
    Invalid = 0,
    Public = 1,
    Beta = 2,
    Internal = 3,
    Dev = 4,
    Max = 5,
}

impl EUniverse {
    pub fn from_u32(v: u32) -> Option<Self> {
        match v {
            1 => Some(Self::Public),
            2 => Some(Self::Beta),
            3 => Some(Self::Internal),
            4 => Some(Self::Dev),
            5 => Some(Self::Max),
            _ => None,
        }
    }

    pub fn is_valid_universe(self) -> bool {
        matches!(self, Self::Public | Self::Beta | Self::Internal | Self::Dev)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn universe_values_match_cpp_enum() {
        assert_eq!(EUniverse::Invalid as u32, 0);
        assert_eq!(EUniverse::Public as u32, 1);
        assert_eq!(EUniverse::Beta as u32, 2);
        assert_eq!(EUniverse::Internal as u32, 3);
        assert_eq!(EUniverse::Dev as u32, 4);
        assert_eq!(EUniverse::Max as u32, 5);
        assert!(!EUniverse::Max.is_valid_universe());
        assert!(EUniverse::Public.is_valid_universe());
    }

    #[test]
    fn friends_steam_levels_emsgs_match_steamkit() {
        // JavaSteam EMsg.java: ClientFSGetFriendsSteamLevels(7528) / ...Response(7529).
        assert_eq!(EMsg::CLIENT_FS_GET_FRIENDS_STEAM_LEVELS.0, 7528);
        assert_eq!(EMsg::CLIENT_FS_GET_FRIENDS_STEAM_LEVELS_RESPONSE.0, 7529);
    }

    #[test]
    fn free_license_emsgs_match_steamkit() {
        // JavaSteam EMsg.java: ClientRequestFreeLicense(5572) / ...Response(5573).
        assert_eq!(EMsg::CLIENT_REQUEST_FREE_LICENSE.0, 5572);
        assert_eq!(EMsg::CLIENT_REQUEST_FREE_LICENSE_RESPONSE.0, 5573);
    }
}
