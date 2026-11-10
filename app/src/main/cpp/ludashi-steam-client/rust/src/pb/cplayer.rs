use crate::proto_wire::{Reader, Writer};

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CPlayerSetRichPresenceKv {
    pub key: String,
    pub value: String,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CPlayerSetRichPresenceRequest {
    pub appid: u32,
    pub rich_presence: Vec<CPlayerSetRichPresenceKv>,
}

impl CPlayerSetRichPresenceRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field_force(1, self.appid);
        for kv in &self.rich_presence {
            let mut sub = Vec::new();
            let mut sw = Writer::new(&mut sub);
            sw.string_field(1, &kv.key);
            sw.string_field(2, &kv.value);
            w.submessage_field(2, &sub);
        }
        out
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CPlayerGetOwnedGamesRequest {
    pub steamid: u64,
    pub include_appinfo: bool,
    pub include_played_free_games: bool,
    pub include_free_sub: bool,
    pub include_extended_appinfo: bool,
}

impl CPlayerGetOwnedGamesRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint64_field(1, self.steamid);
        w.bool_field(2, self.include_appinfo);
        w.bool_field(3, self.include_played_free_games);
        w.bool_field(5, self.include_free_sub);
        w.bool_field(8, self.include_extended_appinfo);
        out
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CPlayerOwnedGame {
    pub appid: i32,
    pub name: String,
    pub playtime_2weeks: i32,
    pub playtime_forever: i32,
    pub img_icon_url: String,
    pub rtime_last_played: u32,
    pub sort_as: String,
}

impl CPlayerOwnedGame {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.appid = reader.u32()? as i32,
                2 => msg.name = reader.string()?,
                3 => msg.playtime_2weeks = reader.u32()? as i32,
                4 => msg.playtime_forever = reader.u32()? as i32,
                5 => msg.img_icon_url = reader.string()?,
                11 => msg.rtime_last_played = reader.u32()?,
                13 => msg.sort_as = reader.string()?,
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
pub struct CPlayerGetOwnedGamesResponse {
    pub game_count: u32,
    pub games: Vec<CPlayerOwnedGame>,
}

impl CPlayerGetOwnedGamesResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.game_count = reader.u32()?,
                2 => msg
                    .games
                    .push(CPlayerOwnedGame::deserialize(reader.bytes()?)?),
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

/// `CPlayer_GetProfileItemsEquipped_Request` — `steamid = 1`, `language = 2`.
///
/// Field numbers verified against the JavaSteam protobufs shipped with the app
/// (`SteammessagesPlayerSteamclient.CPlayer_GetProfileItemsEquipped_Request`).
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CPlayerGetProfileItemsEquippedRequest {
    pub steamid: u64,
    pub language: String,
}

impl CPlayerGetProfileItemsEquippedRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint64_field(1, self.steamid);
        w.string_field(2, &self.language);
        out
    }
}

/// `CPlayer_ProfileItem.ProfileColor` — `style_name = 1`, `color = 2`.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CPlayerProfileColor {
    pub style_name: String,
    pub color: String,
}

impl CPlayerProfileColor {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.style_name = reader.string()?,
                2 => msg.color = reader.string()?,
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
pub struct CPlayerProfileItem {
    pub communityitemid: u64,
    pub image_small: String,
    pub image_large: String,
    pub name: String,
    pub item_title: String,
    pub item_description: String,
    pub appid: u32,
    pub item_type: u32,
    pub item_class: u32,
    pub movie_webm: String,
    pub movie_mp4: String,
    pub equipped_flags: u32,
    pub movie_webm_small: String,
    pub movie_mp4_small: String,
    pub profile_colors: Vec<CPlayerProfileColor>,
    pub tiled: bool,
}

impl CPlayerProfileItem {
    /// True when the CM sent an empty submessage (nothing equipped in that slot).
    pub fn is_empty(&self) -> bool {
        self.communityitemid == 0
            && self.image_small.is_empty()
            && self.image_large.is_empty()
            && self.name.is_empty()
            && self.appid == 0
    }

    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.communityitemid = reader.u64()?,
                2 => msg.image_small = reader.string()?,
                3 => msg.image_large = reader.string()?,
                4 => msg.name = reader.string()?,
                5 => msg.item_title = reader.string()?,
                6 => msg.item_description = reader.string()?,
                7 => msg.appid = reader.u32()?,
                8 => msg.item_type = reader.u32()?,
                9 => msg.item_class = reader.u32()?,
                10 => msg.movie_webm = reader.string()?,
                11 => msg.movie_mp4 = reader.string()?,
                12 => msg.equipped_flags = reader.u32()?,
                13 => msg.movie_webm_small = reader.string()?,
                14 => msg.movie_mp4_small = reader.string()?,
                15 => msg
                    .profile_colors
                    .push(CPlayerProfileColor::deserialize(reader.bytes()?)?),
                16 => msg.tiled = reader.boolean()?,
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

/// `CPlayer_GetProfileItemsEquipped_Response`: `profile_background = 1`,
/// `mini_profile_background = 2`, `avatar_frame = 3`, `animated_avatar = 4`,
/// `profile_modifier = 5`, `steam_deck_keyboard_skin = 6`.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CPlayerGetProfileItemsEquippedResponse {
    pub profile_background: Option<CPlayerProfileItem>,
    pub mini_profile_background: Option<CPlayerProfileItem>,
    pub avatar_frame: Option<CPlayerProfileItem>,
    pub animated_avatar: Option<CPlayerProfileItem>,
    pub profile_modifier: Option<CPlayerProfileItem>,
    pub steam_deck_keyboard_skin: Option<CPlayerProfileItem>,
}

impl CPlayerGetProfileItemsEquippedResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => {
                    msg.profile_background = Some(CPlayerProfileItem::deserialize(reader.bytes()?)?)
                }
                2 => {
                    msg.mini_profile_background =
                        Some(CPlayerProfileItem::deserialize(reader.bytes()?)?)
                }
                3 => msg.avatar_frame = Some(CPlayerProfileItem::deserialize(reader.bytes()?)?),
                4 => msg.animated_avatar = Some(CPlayerProfileItem::deserialize(reader.bytes()?)?),
                5 => msg.profile_modifier = Some(CPlayerProfileItem::deserialize(reader.bytes()?)?),
                6 => {
                    msg.steam_deck_keyboard_skin =
                        Some(CPlayerProfileItem::deserialize(reader.bytes()?)?)
                }
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

/// `CPlayer_GetFavoriteBadge_Request` — `steamid = 1`.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CPlayerGetFavoriteBadgeRequest {
    pub steamid: u64,
}

impl CPlayerGetFavoriteBadgeRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        Writer::new(&mut out).uint64_field(1, self.steamid);
        out
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CPlayerGetFavoriteBadgeResponse {
    pub has_favorite_badge: bool,
    pub badgeid: u32,
    pub communityitemid: u64,
    pub item_type: u32,
    pub border_color: u32,
    pub appid: u32,
    pub level: u32,
}

impl CPlayerGetFavoriteBadgeResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.has_favorite_badge = reader.boolean()?,
                2 => msg.badgeid = reader.u32()?,
                3 => msg.communityitemid = reader.u64()?,
                4 => msg.item_type = reader.u32()?,
                5 => msg.border_color = reader.u32()?,
                6 => msg.appid = reader.u32()?,
                7 => msg.level = reader.u32()?,
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
    fn rich_presence_force_emits_zero_appid() {
        let body = CPlayerSetRichPresenceRequest {
            appid: 0,
            rich_presence: vec![CPlayerSetRichPresenceKv {
                key: "status".to_string(),
                value: "Playing".to_string(),
            }],
        }
        .serialize();
        assert_eq!(
            body,
            [
                8, 0, 18, 17, 10, 6, b's', b't', b'a', b't', b'u', b's', 18, 7, b'P', b'l', b'a',
                b'y', b'i', b'n', b'g'
            ]
        );
    }

    #[test]
    fn parses_owned_games_response() {
        let mut game = Vec::new();
        {
            let mut w = Writer::new(&mut game);
            w.uint32_field(1, 42);
            w.string_field(2, "Half-Life");
            w.uint32_field(3, 10);
            w.uint32_field(4, 200);
            w.string_field(5, "icon");
            w.uint32_field(11, 12345);
            w.string_field(13, "Half Life");
        }

        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.uint32_field(1, 1);
            w.submessage_field(2, &game);
        }

        let parsed = CPlayerGetOwnedGamesResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.game_count, 1);
        assert_eq!(parsed.games[0].appid, 42);
        assert_eq!(parsed.games[0].name, "Half-Life");
        assert_eq!(parsed.games[0].sort_as, "Half Life");
    }

    #[test]
    fn owned_games_already_carries_recent_playtime() {
        // Guards the decision NOT to add IPlayerService/GetRecentlyPlayedGames#1: the
        // recently-played surface is derived from these two fields of GetOwnedGames.
        let mut game = Vec::new();
        {
            let mut w = Writer::new(&mut game);
            w.uint32_field(1, 570);
            w.uint32_field(3, 120); // playtime_2weeks
            w.uint32_field(11, 1_725_000_000); // rtime_last_played
        }
        let mut body = Vec::new();
        Writer::new(&mut body).submessage_field(2, &game);
        let parsed = CPlayerGetOwnedGamesResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.games[0].playtime_2weeks, 120);
        assert_eq!(parsed.games[0].rtime_last_played, 1_725_000_000);
    }

    #[test]
    fn profile_items_equipped_request_matches_wire() {
        let body = CPlayerGetProfileItemsEquippedRequest {
            steamid: 76561197960287930,
            language: "english".to_string(),
        }
        .serialize();
        let mut expected = Vec::new();
        {
            let mut w = Writer::new(&mut expected);
            w.uint64_field(1, 76561197960287930);
            w.string_field(2, "english");
        }
        assert_eq!(body, expected);
        // Field 1 is a plain uint64 varint (not the fixed64 the clientserver messages use).
        assert_eq!(body[0], 0x08);
    }

    #[test]
    fn parses_equipped_profile_items_into_the_right_slots() {
        fn item(id: u64, name: &str) -> Vec<u8> {
            let mut out = Vec::new();
            let mut w = Writer::new(&mut out);
            w.uint64_field(1, id);
            w.string_field(2, "small.png");
            w.string_field(3, "large.png");
            w.string_field(4, name);
            w.uint32_field(7, 753);
            w.bool_field(16, true);
            let mut color = Vec::new();
            {
                let mut cw = Writer::new(&mut color);
                cw.string_field(1, "Style");
                cw.string_field(2, "#112233");
            }
            w.submessage_field(15, &color);
            out
        }

        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.submessage_field(1, &item(1, "background"));
            w.submessage_field(2, &item(2, "mini"));
            w.submessage_field(3, &item(3, "frame"));
            w.submessage_field(4, &item(4, "animated"));
            w.submessage_field(5, &item(5, "modifier"));
            w.submessage_field(6, &item(6, "deck skin"));
        }

        let parsed = CPlayerGetProfileItemsEquippedResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.profile_background.unwrap().name, "background");
        assert_eq!(parsed.mini_profile_background.unwrap().name, "mini");
        let frame = parsed.avatar_frame.unwrap();
        assert_eq!(frame.name, "frame");
        assert_eq!(frame.communityitemid, 3);
        assert_eq!(frame.image_large, "large.png");
        assert_eq!(frame.appid, 753);
        assert!(frame.tiled);
        assert_eq!(frame.profile_colors[0].style_name, "Style");
        assert_eq!(frame.profile_colors[0].color, "#112233");
        assert_eq!(parsed.animated_avatar.unwrap().name, "animated");
        assert_eq!(parsed.profile_modifier.unwrap().name, "modifier");
        assert_eq!(parsed.steam_deck_keyboard_skin.unwrap().name, "deck skin");
    }

    #[test]
    fn absent_equipped_slots_stay_none() {
        let parsed = CPlayerGetProfileItemsEquippedResponse::deserialize(&[]).unwrap();
        assert!(parsed.avatar_frame.is_none());
        assert!(parsed.animated_avatar.is_none());
    }

    #[test]
    fn empty_equipped_slot_is_reported_empty() {
        // Steam sends a present-but-empty submessage for a slot with nothing equipped.
        let mut body = Vec::new();
        Writer::new(&mut body).submessage_field(3, &[]);
        let parsed = CPlayerGetProfileItemsEquippedResponse::deserialize(&body).unwrap();
        assert!(parsed.avatar_frame.as_ref().unwrap().is_empty());
    }

    #[test]
    fn parses_favorite_badge_response() {
        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.bool_field(1, true);
            w.uint32_field(2, 13);
            w.uint64_field(3, 987_654_321);
            w.uint32_field(4, 4);
            w.uint32_field(5, 1);
            w.uint32_field(6, 440);
            w.uint32_field(7, 5);
        }
        let parsed = CPlayerGetFavoriteBadgeResponse::deserialize(&body).unwrap();
        assert!(parsed.has_favorite_badge);
        assert_eq!(parsed.badgeid, 13);
        assert_eq!(parsed.communityitemid, 987_654_321);
        assert_eq!(parsed.item_type, 4);
        assert_eq!(parsed.border_color, 1);
        assert_eq!(parsed.appid, 440);
        assert_eq!(parsed.level, 5);
    }

    #[test]
    fn favorite_badge_absent_parses_as_no_badge() {
        let parsed = CPlayerGetFavoriteBadgeResponse::deserialize(&[]).unwrap();
        assert!(!parsed.has_favorite_badge);
        assert_eq!(parsed.level, 0);
    }
}
