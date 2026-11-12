use crate::cmsg_protobuf_header::{CMsgProtoBufHeader, INVALID_JOB_ID};
use crate::crypto::generate_session_key;
use crate::emsg::EMsg;
use crate::job_manager::JobResult;
use crate::library_store::LudashiLibraryStore;
use crate::pb::ccloud::{
    CCloudAppExitSyncDoneNotification, CCloudAppLaunchIntentRequest,
    CCloudBeginAppUploadBatchRequest, CCloudClientBeginFileUploadRequest,
    CCloudClientCommitFileUploadRequest, CCloudClientFileDownloadRequest,
    CCloudCompleteAppUploadBatchRequest, CCloudGetAppFileChangelistRequest,
    CCloudGetUserQuotaRequest,
};
use crate::pb::ccontentserverdirectory::{
    CContentServerDirectoryGetManifestRequestCodeRequest,
    CContentServerDirectoryGetServersForSteamPipeRequest,
};
use crate::pb::cfamilygroups::CFamilyGroupsGetFamilyGroupRequest;
use crate::pb::cinventory::CInventoryGetItemDefMetaRequest;
use crate::pb::cmsg_client_change_status::CMsgClientChangeStatus;
use crate::pb::cmsg_client_check_app_beta_password::CMsgClientCheckAppBetaPassword;
use crate::pb::cmsg_client_friends_list::CMsgClientFriendsList;
use crate::pb::cmsg_client_friends_ops::{
    CMsgClientAddFriend, CMsgClientFriendProfileInfo, CMsgClientRemoveFriend,
};
use crate::pb::cmsg_client_fs_get_friends_steam_levels::CMsgClientFSGetFriendsSteamLevels;
use crate::pb::cmsg_client_games_played::{
    CMsgClientGamesPlayed, GamePlayedEntry, GamePlayedProcessInfo,
};
use crate::pb::cmsg_client_get_app_ownership_ticket::CMsgClientGetAppOwnershipTicket;
use crate::pb::cmsg_client_get_depot_decryption_key::CMsgClientGetDepotDecryptionKey;
use crate::pb::cmsg_client_kick_playing_session::CMsgClientKickPlayingSession;
use crate::pb::cmsg_client_license_list::{CMsgClientLicenseList, License};
use crate::pb::cmsg_client_mms_get_lobby_list::{
    CMsgClientMMSGetLobbyList, CMsgClientMMSGetLobbyListFilter,
};
use crate::pb::cmsg_client_mms_lobby_ops::{
    CMsgClientMMSCreateLobby, CMsgClientMMSInviteToLobby, CMsgClientMMSJoinLobby,
    CMsgClientMMSLeaveLobby, CMsgClientMMSSendLobbyChatMsg, CMsgClientMMSSetLobbyData,
    CMsgClientMMSSetLobbyOwner,
};
use crate::pb::cmsg_client_persona::{CMsgClientPersonaState, PersonaStateFriend};
use crate::pb::cmsg_client_pics::{
    CMsgClientPICSAccessTokenRequest, CMsgClientPICSChangesSinceRequest,
    CMsgClientPICSProductInfoRequest, PicsAppInfoReq, PicsPackageInfoReq,
};
use crate::pb::cmsg_client_playing_session_state::CMsgClientPlayingSessionState;
use crate::pb::cmsg_client_request_free_license::CMsgClientRequestFreeLicense;
use crate::pb::cmsg_client_store_user_stats::{CMsgClientStoreUserStats2, Stat};
use crate::pb::cmsg_clientserver_login::{
    CMsgClientHeartBeat, CMsgClientHello, CMsgClientLogOff, CMsgClientLogon,
    CMsgClientLogonResponse,
};
use crate::pb::cplayer::{
    CPlayerGetFavoriteBadgeRequest, CPlayerGetOwnedGamesRequest,
    CPlayerGetProfileItemsEquippedRequest, CPlayerSetRichPresenceKv, CPlayerSetRichPresenceRequest,
};
use crate::pb::cpublishedfile::CPublishedFileGetUserFilesRequest;
use crate::pb::cuseraccount::CUserAccountCreateFriendInviteTokenRequest;
use crate::proto_envelope::encode_proto_envelope;
use crate::ticket_cache::LudashiTicketCache;
use flate2::read::{GzDecoder, ZlibDecoder};
use std::collections::HashMap;
use std::io::Read;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU32, AtomicU64, AtomicU8, Ordering};
use std::sync::Mutex;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum ClientState {
    Disconnected,
    Connecting,
    Connected,
    LoggedOn,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct FriendPersonaSnapshot {
    pub sid: u64,
    pub player_name: String,
    pub persona_state: u32,
    pub game_played_app_id: u32,
    pub avatar_hash: Vec<u8>,
    pub rich_presence: Vec<(String, String)>,
    pub game_name: String,
    pub gameid: u64,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct GamesPlayedExtras {
    pub process_id: u32,
    pub owner_id: u32,
    pub launch_source: u32,
    pub game_build_id: u32,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct AccountInfoSnapshot {
    pub persona_name: String,
    pub ip_country: String,
    pub two_factor_enabled: bool,
    pub phone_verified: bool,
    pub phone_identifying: bool,
    pub phone_requires_verification: bool,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum InboundAction {
    DeliverJob(JobResult),
    Multi,
    PicsProductInfo,
    LogonOk,
    LoggedOff,
    LicenseList(usize),
    FriendsList(usize),
    PersonaState(usize),
    PlayingSessionState(bool),
    AccountInfo(AccountInfoSnapshot),
    LobbyPush,
    ClientMessage,
    Ignored,
    ParseFailed(&'static str),
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgMultiBody {
    pub size_unzipped: u32,
    pub message_body: Vec<u8>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct OutboundProtoMessage {
    pub emsg: EMsg,
    pub routing_appid: u32,
    pub body: Vec<u8>,
    pub wire: Vec<u8>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct OutboundServiceCall {
    pub method_name: String,
    pub authed: bool,
    pub job_id: u64,
    pub request_body: Vec<u8>,
    pub wire: Vec<u8>,
}

pub struct CMClientCore {
    state: AtomicU8,
    steam_id: AtomicU64,
    session_id: AtomicI32,
    family_group_id: AtomicU64,
    server_realtime: AtomicU32,
    playing_blocked: AtomicBool,
    license_list: Mutex<Vec<License>>,
    friends: Mutex<HashMap<u64, u32>>,
    self_persona: Mutex<Option<PersonaStateFriend>>,
    friend_personas: Mutex<HashMap<u64, FriendPersonaSnapshot>>,
    incoming_messages: Mutex<Vec<IncomingFriendMessage>>,
    library: LudashiLibraryStore,
    tickets: LudashiTicketCache,
    outbound_wires: Mutex<Vec<Vec<u8>>>,
}

#[derive(Clone, Debug, Default)]
pub struct IncomingFriendMessage {
    pub friend_id: u64,
    pub from_self: bool,
    pub message: String,
    pub timestamp: u32,
    pub ordinal: i32,
    /// `EChatEntryType`: 1 = text (`message` set), 2 = typing notification (`message` empty).
    pub chat_entry_type: i32,
}

impl Default for CMClientCore {
    fn default() -> Self {
        Self {
            state: AtomicU8::new(ClientState::Disconnected as u8),
            steam_id: AtomicU64::new(0),
            session_id: AtomicI32::new(0),
            family_group_id: AtomicU64::new(0),
            server_realtime: AtomicU32::new(0),
            playing_blocked: AtomicBool::new(false),
            license_list: Mutex::new(Vec::new()),
            friends: Mutex::new(HashMap::new()),
            self_persona: Mutex::new(None),
            friend_personas: Mutex::new(HashMap::new()),
            incoming_messages: Mutex::new(Vec::new()),
            library: LudashiLibraryStore::default(),
            tickets: LudashiTicketCache::default(),
            outbound_wires: Mutex::new(Vec::new()),
        }
    }
}

impl CMClientCore {
    pub fn state(&self) -> ClientState {
        match self.state.load(Ordering::Relaxed) {
            1 => ClientState::Connecting,
            2 => ClientState::Connected,
            3 => ClientState::LoggedOn,
            _ => ClientState::Disconnected,
        }
    }

    pub fn set_state(&self, state: ClientState) {
        self.state.store(state as u8, Ordering::Relaxed);
    }

    pub fn reset_session_identity(&self) {
        self.steam_id.store(0, Ordering::Relaxed);
        self.session_id.store(0, Ordering::Relaxed);
        self.family_group_id.store(0, Ordering::Relaxed);
        self.server_realtime.store(0, Ordering::Relaxed);
        self.outbound_wires
            .lock()
            .expect("outbound queue poisoned")
            .clear();
    }

    pub fn steam_id(&self) -> u64 {
        self.steam_id.load(Ordering::Relaxed)
    }

    pub fn session_id(&self) -> i32 {
        self.session_id.load(Ordering::Relaxed)
    }

    pub fn family_group_id(&self) -> u64 {
        self.family_group_id.load(Ordering::Relaxed)
    }

    pub fn server_realtime(&self) -> u32 {
        self.server_realtime.load(Ordering::Relaxed)
    }

    pub fn is_playing_blocked(&self) -> bool {
        self.playing_blocked.load(Ordering::Relaxed)
    }

    pub fn mark_playing_blocked(&self) {
        self.playing_blocked.store(true, Ordering::Relaxed);
    }

    pub fn library(&self) -> &LudashiLibraryStore {
        &self.library
    }

    pub fn tickets(&self) -> &LudashiTicketCache {
        &self.tickets
    }

    pub fn enqueue_proto_message(&self, message: Option<OutboundProtoMessage>) -> bool {
        let Some(message) = message else {
            return false;
        };
        self.enqueue_wire(message.wire)
    }

    pub fn enqueue_service_call(&self, call: Option<OutboundServiceCall>) -> bool {
        let Some(call) = call else {
            return false;
        };
        self.enqueue_wire(call.wire)
    }

    pub fn enqueue_wire(&self, wire: Vec<u8>) -> bool {
        if wire.is_empty() {
            return false;
        }
        self.outbound_wires
            .lock()
            .expect("outbound queue poisoned")
            .push(wire);
        true
    }

    pub fn restore_outbound_wires_front(&self, mut wires: Vec<Vec<u8>>) {
        if wires.is_empty() {
            return;
        }
        let mut queue = self.outbound_wires.lock().expect("outbound queue poisoned");
        if queue.is_empty() {
            *queue = wires;
            return;
        }
        let mut restored = Vec::with_capacity(wires.len() + queue.len());
        restored.append(&mut wires);
        restored.append(&mut queue);
        *queue = restored;
    }

    pub fn take_outbound_wires(&self) -> Vec<Vec<u8>> {
        std::mem::take(&mut *self.outbound_wires.lock().expect("outbound queue poisoned"))
    }

    pub fn build_proto_message(&self, emsg: EMsg, body: &[u8], routing_appid: u32) -> Vec<u8> {
        let mut header = CMsgProtoBufHeader {
            steamid: self.steam_id(),
            client_sessionid: self.session_id(),
            routing_appid,
            ..Default::default()
        };
        if emsg == EMsg::CLIENT_LOGON && header.steamid == 0 {
            header.steamid = 0x0110_0001_0000_0000;
        }
        encode_proto_envelope(emsg, &header, body)
    }

    pub fn build_client_hello(&self) -> OutboundProtoMessage {
        let body = CMsgClientHello::default().serialize();
        let wire = self.build_proto_message(EMsg::CLIENT_HELLO, &body, 0);
        OutboundProtoMessage {
            emsg: EMsg::CLIENT_HELLO,
            routing_appid: 0,
            body,
            wire,
        }
    }

    pub fn build_heartbeat(&self) -> OutboundProtoMessage {
        let body = CMsgClientHeartBeat::default().serialize();
        let wire = self.build_proto_message(EMsg::CLIENT_HEART_BEAT, &body, 0);
        OutboundProtoMessage {
            emsg: EMsg::CLIENT_HEART_BEAT,
            routing_appid: 0,
            body,
            wire,
        }
    }

    pub fn build_logoff(&self) -> Option<OutboundProtoMessage> {
        if self.state() != ClientState::LoggedOn {
            return None;
        }
        self.build_outbound_proto_message(EMsg::CLIENT_LOG_OFF, CMsgClientLogOff.serialize(), 0)
    }

    pub fn build_logon_with_refresh_token(
        &self,
        refresh_token: impl Into<String>,
        account_name: impl Into<String>,
        client_supplied_steam_id: u64,
    ) -> Option<OutboundProtoMessage> {
        if self.state() != ClientState::Connected {
            return None;
        }
        let refresh_token = refresh_token.into();
        let account_name = account_name.into();
        if refresh_token.is_empty() {
            return None;
        }

        let mut msg = CMsgClientLogon {
            access_token: refresh_token,
            account_name,
            client_supplied_steam_id,
            machine_id: b"Ludashi-Steam-Client".to_vec(),
            protocol_version: 65580,
            client_os_type: 16,
            supports_rate_limit_response: true,
            ..Default::default()
        };
        if let Some(key) = generate_session_key() {
            let mut client_instance_id = 0u64;
            for (idx, byte) in key.bytes.iter().take(8).enumerate() {
                client_instance_id |= (*byte as u64) << (idx * 8);
            }
            if client_instance_id == 0 {
                client_instance_id = 1;
            }
            msg.client_instance_id = client_instance_id;

            let mut login_id = 0u32;
            for (idx, byte) in key.bytes.iter().skip(8).take(4).enumerate() {
                login_id |= (*byte as u32) << (idx * 8);
            }
            if login_id == 0 {
                login_id = 0x574e_5301;
            }
            msg.obfuscated_private_ip = login_id;
        }

        let body = msg.serialize();
        let wire = self.build_proto_message(EMsg::CLIENT_LOGON, &body, 0);
        Some(OutboundProtoMessage {
            emsg: EMsg::CLIENT_LOGON,
            routing_appid: 0,
            body,
            wire,
        })
    }

    pub fn build_service_method_call(
        &self,
        method_name: &str,
        authed: bool,
        job_id: u64,
        request_body: &[u8],
    ) -> Vec<u8> {
        let header = CMsgProtoBufHeader {
            steamid: self.steam_id(),
            client_sessionid: self.session_id(),
            jobid_source: job_id,
            jobid_target: INVALID_JOB_ID,
            target_job_name: method_name.to_string(),
            ..Default::default()
        };
        let emsg = if authed {
            EMsg::SERVICE_METHOD_CALL_FROM_CLIENT
        } else {
            EMsg::SERVICE_METHOD_CALL_FROM_CLIENT_NON_AUTHED
        };
        encode_proto_envelope(emsg, &header, request_body)
    }

    pub fn build_outbound_proto_message(
        &self,
        emsg: EMsg,
        body: Vec<u8>,
        routing_appid: u32,
    ) -> Option<OutboundProtoMessage> {
        if self.state() != ClientState::LoggedOn {
            return None;
        }
        let wire = self.build_proto_message(emsg, &body, routing_appid);
        Some(OutboundProtoMessage {
            emsg,
            routing_appid,
            body,
            wire,
        })
    }

    pub fn build_job_proto_message(
        &self,
        emsg: EMsg,
        job_id: u64,
        body: Vec<u8>,
        routing_appid: u32,
    ) -> Option<OutboundProtoMessage> {
        if self.state() != ClientState::LoggedOn {
            return None;
        }
        let header = CMsgProtoBufHeader {
            steamid: self.steam_id(),
            client_sessionid: self.session_id(),
            routing_appid,
            jobid_source: job_id,
            jobid_target: INVALID_JOB_ID,
            ..Default::default()
        };
        let wire = encode_proto_envelope(emsg, &header, &body);
        Some(OutboundProtoMessage {
            emsg,
            routing_appid,
            body,
            wire,
        })
    }

    pub fn build_authed_service_call(
        &self,
        method_name: &str,
        job_id: u64,
        request_body: Vec<u8>,
    ) -> Option<OutboundServiceCall> {
        if self.state() != ClientState::LoggedOn {
            return None;
        }
        let wire = self.build_service_method_call(method_name, true, job_id, &request_body);
        Some(OutboundServiceCall {
            method_name: method_name.to_string(),
            authed: true,
            job_id,
            request_body,
            wire,
        })
    }

    pub fn build_request_friend_persona_states(
        &self,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        self.build_authed_service_call("Chat.RequestFriendPersonaStates#1", job_id, Vec::new())
    }

    pub fn build_send_friend_message(
        &self,
        steamid: u64,
        message: &str,
        contains_bbcode: bool,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        self.build_send_friend_message_typed(
            steamid,
            crate::pb::cfriendmessages::CHAT_ENTRY_TYPE_TEXT,
            message,
            contains_bbcode,
            job_id,
        )
    }

    /// `FriendMessages.SendMessage` with an explicit `EChatEntryType` — 1 text, 2 typing (empty
    /// message; the peer shows "typing…" for a few seconds, exactly what the Steam client sends).
    pub fn build_send_friend_message_typed(
        &self,
        steamid: u64,
        chat_entry_type: i32,
        message: &str,
        contains_bbcode: bool,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        self.build_authed_service_call(
            "FriendMessages.SendMessage#1",
            job_id,
            crate::pb::cfriendmessages::CFriendMessagesSendMessageRequest {
                steamid,
                chat_entry_type,
                message: message.to_string(),
                contains_bbcode,
                echo_to_sender: chat_entry_type == crate::pb::cfriendmessages::CHAT_ENTRY_TYPE_TEXT,
                low_priority: chat_entry_type != crate::pb::cfriendmessages::CHAT_ENTRY_TYPE_TEXT,
            }
            .serialize(),
        )
    }

    /// `CMsgClientAddFriend`: by SteamID64 (`steamid` != 0) or by account name / e-mail. Steam
    /// answers with `CMsgClientAddFriendResponse` (EMsg 792) on the message firehose.
    pub fn build_add_friend(
        &self,
        steamid: u64,
        accountname_or_email: &str,
    ) -> Option<OutboundProtoMessage> {
        if steamid == 0 && accountname_or_email.is_empty() {
            return None;
        }
        self.build_outbound_proto_message(
            EMsg::CLIENT_ADD_FRIEND,
            CMsgClientAddFriend {
                steamid_to_add: steamid,
                accountname_or_email_to_add: accountname_or_email.to_string(),
            }
            .serialize(),
            0,
        )
    }

    /// `CMsgClientRemoveFriend`: remove a friend, decline an incoming invite or cancel an
    /// outgoing one. The updated relationship arrives as an incremental `CMsgClientFriendsList`.
    pub fn build_remove_friend(&self, steamid: u64) -> Option<OutboundProtoMessage> {
        if steamid == 0 {
            return None;
        }
        self.build_outbound_proto_message(
            EMsg::CLIENT_REMOVE_FRIEND,
            CMsgClientRemoveFriend { friendid: steamid }.serialize(),
            0,
        )
    }

    /// `CMsgClientFriendProfileInfo` (job-matched; the 5331 response is delivered to the job).
    pub fn build_friend_profile_info(
        &self,
        steamid: u64,
        job_id: u64,
    ) -> Option<OutboundProtoMessage> {
        if steamid == 0 {
            return None;
        }
        self.build_job_proto_message(
            EMsg::CLIENT_FRIEND_PROFILE_INFO,
            job_id,
            CMsgClientFriendProfileInfo {
                steamid_friend: steamid,
            }
            .serialize(),
            0,
        )
    }

    /// `UserAccount.CreateFriendInviteToken#1` — the shareable `s.team/p/<token>` quick-invite.
    pub fn build_create_friend_invite_token_call(
        &self,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        self.build_authed_service_call(
            "UserAccount.CreateFriendInviteToken#1",
            job_id,
            CUserAccountCreateFriendInviteTokenRequest::default().serialize(),
        )
    }

    pub fn build_get_recent_messages(
        &self,
        friend_id: u64,
        count: u32,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        let self_id = self.steam_id();
        if self_id == 0 || friend_id == 0 {
            return None;
        }
        self.build_authed_service_call(
            "FriendMessages.GetRecentMessages#1",
            job_id,
            crate::pb::cfriendmessages::CFriendMessagesGetRecentMessagesRequest {
                steamid1: self_id,
                steamid2: friend_id,
                count,
                most_recent_conversation: false,
            }
            .serialize(),
        )
    }

    pub fn build_set_persona_state(&self, persona_state: u32) -> Option<OutboundProtoMessage> {
        self.build_outbound_proto_message(
            EMsg::CLIENT_CHANGE_STATUS,
            CMsgClientChangeStatus {
                persona_state,
                player_name: String::new(),
                persona_set_by_user: true,
                need_persona_response: true,
            }
            .serialize(),
            0,
        )
    }

    pub fn build_set_persona_name(
        &self,
        name: impl Into<String>,
        persona_state_keep_current: u32,
    ) -> Option<OutboundProtoMessage> {
        self.build_outbound_proto_message(
            EMsg::CLIENT_CHANGE_STATUS,
            CMsgClientChangeStatus {
                persona_state: persona_state_keep_current,
                player_name: name.into(),
                persona_set_by_user: true,
                need_persona_response: false,
            }
            .serialize(),
            0,
        )
    }

    pub fn build_request_user_persona(&self) -> Option<OutboundProtoMessage> {
        let steam_id = self.steam_id();
        if steam_id == 0 {
            return None;
        }
        self.build_request_friend_personas(&[steam_id], 0xffff)
    }

    pub fn build_request_friend_personas(
        &self,
        sids: &[u64],
        persona_state_requested: u32,
    ) -> Option<OutboundProtoMessage> {
        if sids.is_empty() {
            return None;
        }
        self.build_outbound_proto_message(
            EMsg::CLIENT_REQUEST_FRIEND_DATA,
            crate::pb::cmsg_client_persona::CMsgClientRequestFriendData {
                persona_state_requested,
                friends: sids.iter().copied().filter(|sid| *sid != 0).collect(),
            }
            .serialize(),
            0,
        )
    }

    pub fn build_notify_games_played(&self, app_id: u32) -> Option<OutboundProtoMessage> {
        self.build_notify_games_played_full(
            app_id.into(),
            &GamesPlayedExtras::default(),
            &[],
            0,
        )
    }

    pub fn build_notify_games_played_full(
        &self,
        game_id: u64,
        extras: &GamesPlayedExtras,
        processes: &[GamePlayedProcessInfo],
        client_os_type: u32,
    ) -> Option<OutboundProtoMessage> {
        let mut msg = CMsgClientGamesPlayed {
            games_played: Vec::new(),
            client_os_type,
        };
        if game_id != 0 {
            msg.games_played.push(GamePlayedEntry {
                game_id,
                process_id: extras.process_id,
                owner_id: extras.owner_id,
                launch_source: extras.launch_source,
                game_build_id: extras.game_build_id,
                process_id_list: processes.to_vec(),
            });
        }
        self.build_outbound_proto_message(
            EMsg::CLIENT_GAMES_PLAYED_WITH_DATA_BLOB,
            msg.serialize(),
            0,
        )
    }

    pub fn build_kick_playing_session(&self, only_stop_game: bool) -> Option<OutboundProtoMessage> {
        self.build_outbound_proto_message(
            EMsg::CLIENT_KICK_PLAYING_SESSION,
            CMsgClientKickPlayingSession { only_stop_game }.serialize(),
            0,
        )
    }

    pub fn build_store_user_stats(
        &self,
        app_id: u32,
        steam_id: u64,
        crc_stats: u32,
        stats: &[(u32, u32)],
    ) -> Option<OutboundProtoMessage> {
        if app_id == 0 || steam_id == 0 {
            return None;
        }
        self.build_outbound_proto_message(
            EMsg::CLIENT_STORE_USER_STATS_2,
            CMsgClientStoreUserStats2 {
                game_id: app_id as u64,
                settor_steam_id: steam_id,
                settee_steam_id: steam_id,
                crc_stats,
                stats: stats
                    .iter()
                    .map(|(stat_id, stat_value)| Stat {
                        stat_id: *stat_id,
                        stat_value: *stat_value,
                    })
                    .collect(),
            }
            .serialize(),
            app_id,
        )
    }

    /// Job-tracked `ClientStoreUserStats2` so the caller can await the
    /// `ClientStoreUserStatsResponse` (EResult + stats the server refused) — the achievement
    /// sync-back needs the verdict, the fire-and-forget [`Self::build_store_user_stats`] does not.
    pub fn build_store_user_stats_job(
        &self,
        app_id: u32,
        steam_id: u64,
        crc_stats: u32,
        stats: &[(u32, u32)],
        job_id: u64,
    ) -> Option<OutboundProtoMessage> {
        if app_id == 0 || steam_id == 0 {
            return None;
        }
        self.build_job_proto_message(
            EMsg::CLIENT_STORE_USER_STATS_2,
            job_id,
            CMsgClientStoreUserStats2 {
                game_id: app_id as u64,
                settor_steam_id: steam_id,
                settee_steam_id: steam_id,
                crc_stats,
                stats: stats
                    .iter()
                    .map(|(stat_id, stat_value)| Stat {
                        stat_id: *stat_id,
                        stat_value: *stat_value,
                    })
                    .collect(),
            }
            .serialize(),
            app_id,
        )
    }

    pub fn build_rich_presence_call(
        &self,
        app_id: u32,
        kv: impl IntoIterator<Item = (String, String)>,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        let request = CPlayerSetRichPresenceRequest {
            appid: app_id,
            rich_presence: kv
                .into_iter()
                .filter(|(key, _)| !key.is_empty())
                .map(|(key, value)| CPlayerSetRichPresenceKv { key, value })
                .collect(),
        };
        self.build_authed_service_call("Player.SetRichPresence#1", job_id, request.serialize())
    }

    pub fn build_get_app_ownership_ticket(
        &self,
        app_id: u32,
        job_id: u64,
    ) -> Option<OutboundProtoMessage> {
        if app_id == 0 {
            return None;
        }
        self.build_job_proto_message(
            EMsg::CLIENT_GET_APP_OWNERSHIP_TICKET,
            job_id,
            CMsgClientGetAppOwnershipTicket { app_id }.serialize(),
            0,
        )
    }

    pub fn build_request_encrypted_app_ticket(
        &self,
        app_id: u32,
        job_id: u64,
    ) -> Option<OutboundProtoMessage> {
        if app_id == 0 {
            return None;
        }
        self.build_job_proto_message(
            EMsg::CLIENT_REQUEST_ENCRYPTED_APP_TICKET,
            job_id,
            crate::pb::cmsg_client_request_encrypted_app_ticket::CMsgClientRequestEncryptedAppTicket {
                app_id,
            }
            .serialize(),
            // Match the legacy C++ client: this request is not app-routed.
            0,
        )
    }

    /// Job-tracked `ClientCheckAppBetaPassword`: which password-protected beta branches of
    /// `app_id` the access code unlocks (the response carries each branch's manifest key).
    pub fn build_check_app_beta_password(
        &self,
        app_id: u32,
        password: &str,
        job_id: u64,
    ) -> Option<OutboundProtoMessage> {
        if app_id == 0 || password.is_empty() {
            return None;
        }
        self.build_job_proto_message(
            EMsg::CLIENT_CHECK_APP_BETA_PASSWORD,
            job_id,
            CMsgClientCheckAppBetaPassword {
                app_id,
                betapassword: password.to_string(),
            }
            .serialize(),
            0,
        )
    }

    pub fn build_get_depot_decryption_key(
        &self,
        depot_id: u32,
        app_id: u32,
        job_id: u64,
    ) -> Option<OutboundProtoMessage> {
        if depot_id == 0 || app_id == 0 {
            return None;
        }
        self.build_job_proto_message(
            EMsg::CLIENT_GET_DEPOT_DECRYPTION_KEY,
            job_id,
            CMsgClientGetDepotDecryptionKey { depot_id, app_id }.serialize(),
            0,
        )
    }

    pub fn build_manifest_request_code_call(
        &self,
        app_id: u32,
        depot_id: u32,
        manifest_id: u64,
        branch: &str,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        if app_id == 0 || depot_id == 0 || manifest_id == 0 {
            return None;
        }
        let lower = branch.to_ascii_lowercase();
        let app_branch = if lower.is_empty() || lower == "public" {
            String::new()
        } else {
            branch.to_string()
        };
        self.build_authed_service_call(
            "ContentServerDirectory.GetManifestRequestCode#1",
            job_id,
            CContentServerDirectoryGetManifestRequestCodeRequest {
                app_id,
                depot_id,
                manifest_id,
                app_branch,
                branch_password_hash: String::new(),
            }
            .serialize(),
        )
    }

    pub fn build_get_cdn_servers_call(
        &self,
        cell_id: u32,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        self.build_authed_service_call(
            "ContentServerDirectory.GetServersForSteamPipe#1",
            job_id,
            CContentServerDirectoryGetServersForSteamPipeRequest {
                cell_id,
                ..Default::default()
            }
            .serialize(),
        )
    }

    pub fn build_pics_access_tokens(
        &self,
        packageids: Vec<u32>,
        appids: Vec<u32>,
        job_id: u64,
    ) -> Option<OutboundProtoMessage> {
        self.build_job_proto_message(
            EMsg::CLIENT_PICS_ACCESS_TOKEN_REQUEST,
            job_id,
            CMsgClientPICSAccessTokenRequest { packageids, appids }.serialize(),
            0,
        )
    }

    pub fn build_pics_changes_since(
        &self,
        since_change_number: u32,
        job_id: u64,
    ) -> Option<OutboundProtoMessage> {
        self.build_job_proto_message(
            EMsg::CLIENT_PICS_CHANGES_SINCE_REQUEST,
            job_id,
            CMsgClientPICSChangesSinceRequest {
                since_change_number,
                ..Default::default()
            }
            .serialize(),
            0,
        )
    }

    pub fn build_pics_product_info(
        &self,
        packages: Vec<PicsPackageInfoReq>,
        apps: Vec<PicsAppInfoReq>,
        meta_data_only: bool,
        job_id: u64,
    ) -> Option<OutboundProtoMessage> {
        self.build_job_proto_message(
            EMsg::CLIENT_PICS_PRODUCT_INFO_REQUEST,
            job_id,
            CMsgClientPICSProductInfoRequest {
                packages,
                apps,
                meta_data_only,
                single_response: false,
                ..Default::default()
            }
            .serialize(),
            0,
        )
    }

    pub fn prepare_app_ids(app_id: u32, dlc_app_ids: &[u32]) -> Vec<u32> {
        let mut all_ids = Vec::with_capacity(1 + dlc_app_ids.len());
        if app_id != 0 {
            all_ids.push(app_id);
        }
        for dlc in dlc_app_ids {
            if *dlc == 0 || *dlc == app_id || all_ids.contains(dlc) {
                continue;
            }
            all_ids.push(*dlc);
        }
        all_ids
    }

    pub fn prepare_app_missing_token_ids(&self, all_ids: &[u32]) -> Vec<u32> {
        all_ids
            .iter()
            .copied()
            .filter(|id| {
                self.library
                    .find_app(*id)
                    .is_some_and(|app| app.missing_token && app.access_token == 0)
            })
            .collect()
    }

    pub fn prepare_app_pics_requests(&self, all_ids: &[u32]) -> Vec<PicsAppInfoReq> {
        all_ids
            .iter()
            .map(|id| PicsAppInfoReq {
                appid: *id,
                access_token: self
                    .library
                    .find_app(*id)
                    .map(|app| app.access_token)
                    .unwrap_or_default(),
                only_public_obsolete: false,
            })
            .collect()
    }

    pub fn build_family_group_call(
        &self,
        family_group_id: u64,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        self.build_authed_service_call(
            "FamilyGroups.GetFamilyGroup#1",
            job_id,
            CFamilyGroupsGetFamilyGroupRequest {
                family_groupid: family_group_id,
            }
            .serialize(),
        )
    }

    pub fn build_owned_games_call(
        &self,
        steam_id: u64,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        self.build_authed_service_call(
            "Player.GetOwnedGames#1",
            job_id,
            CPlayerGetOwnedGamesRequest {
                steamid: steam_id,
                include_appinfo: true,
                include_played_free_games: true,
                include_free_sub: true,
                include_extended_appinfo: true,
            }
            .serialize(),
        )
    }

    pub fn build_profile_items_equipped_call(
        &self,
        steam_id: u64,
        language: &str,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        if steam_id == 0 {
            return None;
        }
        self.build_authed_service_call(
            "Player.GetProfileItemsEquipped#1",
            job_id,
            CPlayerGetProfileItemsEquippedRequest {
                steamid: steam_id,
                language: language.to_string(),
            }
            .serialize(),
        )
    }

    /// `Player.GetFavoriteBadge#1` — the badge showcased on a profile (level, border, app).
    pub fn build_favorite_badge_call(
        &self,
        steam_id: u64,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        if steam_id == 0 {
            return None;
        }
        self.build_authed_service_call(
            "Player.GetFavoriteBadge#1",
            job_id,
            CPlayerGetFavoriteBadgeRequest { steamid: steam_id }.serialize(),
        )
    }

    pub fn build_request_free_license(
        &self,
        app_ids: &[u32],
        job_id: u64,
    ) -> Option<OutboundProtoMessage> {
        if app_ids.is_empty() {
            return None;
        }
        self.build_job_proto_message(
            EMsg::CLIENT_REQUEST_FREE_LICENSE,
            job_id,
            CMsgClientRequestFreeLicense {
                appids: app_ids.to_vec(),
            }
            .serialize(),
            0,
        )
    }

    /// `CMsgClientFSGetFriendsSteamLevels` (EMsg 7528; job-matched, the 7529 response is
    /// delivered to the job). Takes *account* ids, not SteamID64s.
    pub fn build_friends_steam_levels(
        &self,
        account_ids: &[u32],
        job_id: u64,
    ) -> Option<OutboundProtoMessage> {
        if account_ids.is_empty() {
            return None;
        }
        self.build_job_proto_message(
            EMsg::CLIENT_FS_GET_FRIENDS_STEAM_LEVELS,
            job_id,
            CMsgClientFSGetFriendsSteamLevels {
                accountids: account_ids.to_vec(),
            }
            .serialize(),
            0,
        )
    }

    pub fn build_inventory_item_def_meta_call(
        &self,
        app_id: u32,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        self.build_authed_service_call(
            "Inventory.GetItemDefMeta#1",
            job_id,
            CInventoryGetItemDefMetaRequest { appid: app_id }.serialize(),
        )
    }

    pub fn build_published_file_subscribed_call(
        &self,
        app_id: u32,
        page: u32,
        num_per_page: u32,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        self.build_authed_service_call(
            "PublishedFile.GetUserFiles#1",
            job_id,
            CPublishedFileGetUserFilesRequest {
                steamid: self.steam_id(),
                appid: app_id,
                page,
                numperpage: num_per_page,
                request_type: "mysubscriptions".to_string(),
                filetype: u32::MAX,
            }
            .serialize(),
        )
    }

    pub fn build_cloud_user_quota_call(&self, job_id: u64) -> Option<OutboundServiceCall> {
        self.build_authed_service_call(
            "Cloud.GetUserQuota#1",
            job_id,
            CCloudGetUserQuotaRequest.serialize(),
        )
    }

    pub fn build_cloud_app_file_changelist_call(
        &self,
        app_id: u32,
        synced_change_number: u64,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        self.build_authed_service_call(
            "Cloud.GetAppFileChangelist#1",
            job_id,
            CCloudGetAppFileChangelistRequest {
                appid: app_id,
                synced_change_number,
            }
            .serialize(),
        )
    }

    pub fn build_cloud_file_download_info_call(
        &self,
        app_id: u32,
        filename: impl Into<String>,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        self.build_authed_service_call(
            "Cloud.ClientFileDownload#1",
            job_id,
            CCloudClientFileDownloadRequest {
                appid: app_id,
                filename: filename.into(),
                realm: 1,
            }
            .serialize(),
        )
    }

    pub fn build_cloud_begin_app_upload_batch_call(
        &self,
        app_id: u32,
        machine_name: impl Into<String>,
        files_to_upload: Vec<String>,
        files_to_delete: Vec<String>,
        client_id: u64,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        self.build_authed_service_call(
            "Cloud.BeginAppUploadBatch#1",
            job_id,
            CCloudBeginAppUploadBatchRequest {
                appid: app_id,
                machine_name: machine_name.into(),
                files_to_upload,
                files_to_delete,
                client_id,
                app_build_id: 0,
            }
            .serialize(),
        )
    }

    #[allow(clippy::too_many_arguments)]
    pub fn build_cloud_begin_file_upload_call(
        &self,
        app_id: u32,
        filename: impl Into<String>,
        file_size: u32,
        raw_file_size: u32,
        file_sha: Vec<u8>,
        time_stamp: u64,
        upload_batch_id: u64,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        self.build_authed_service_call(
            "Cloud.ClientBeginFileUpload#1",
            job_id,
            CCloudClientBeginFileUploadRequest {
                appid: app_id,
                file_size,
                raw_file_size,
                file_sha,
                time_stamp,
                filename: filename.into(),
                upload_batch_id,
            }
            .serialize(),
        )
    }

    pub fn build_cloud_commit_file_upload_call(
        &self,
        transfer_succeeded: bool,
        app_id: u32,
        file_sha: Vec<u8>,
        filename: impl Into<String>,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        self.build_authed_service_call(
            "Cloud.ClientCommitFileUpload#1",
            job_id,
            CCloudClientCommitFileUploadRequest {
                transfer_succeeded,
                appid: app_id,
                file_sha,
                filename: filename.into(),
            }
            .serialize(),
        )
    }

    pub fn build_cloud_complete_app_upload_batch_call(
        &self,
        app_id: u32,
        batch_id: u64,
        batch_eresult: u32,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        self.build_authed_service_call(
            "Cloud.CompleteAppUploadBatchBlocking#1",
            job_id,
            CCloudCompleteAppUploadBatchRequest {
                appid: app_id,
                batch_id,
                batch_eresult,
            }
            .serialize(),
        )
    }

    #[allow(clippy::too_many_arguments)]
    pub fn build_cloud_launch_intent_call(
        &self,
        app_id: u32,
        client_id: u64,
        machine_name: impl Into<String>,
        ignore_pending_operations: bool,
        os_type: i32,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        self.build_authed_service_call(
            "Cloud.SignalAppLaunchIntent#1",
            job_id,
            CCloudAppLaunchIntentRequest {
                appid: app_id,
                client_id,
                machine_name: machine_name.into(),
                ignore_pending_operations,
                os_type,
            }
            .serialize(),
        )
    }

    pub fn build_cloud_exit_sync_done_call(
        &self,
        app_id: u32,
        client_id: u64,
        uploads_completed: bool,
        uploads_required: bool,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        self.build_authed_service_call(
            "Cloud.SignalAppExitSyncDone#1",
            job_id,
            CCloudAppExitSyncDoneNotification {
                appid: app_id,
                client_id,
                uploads_completed,
                uploads_required,
            }
            .serialize(),
        )
    }

    pub fn build_lobby_get_list(
        &self,
        app_id: u32,
        filters: Vec<CMsgClientMMSGetLobbyListFilter>,
        num_lobbies_requested: i32,
        job_id: u64,
    ) -> Option<OutboundProtoMessage> {
        if app_id == 0 {
            return None;
        }
        self.build_job_proto_message(
            EMsg::CLIENT_MMS_GET_LOBBY_LIST,
            job_id,
            CMsgClientMMSGetLobbyList {
                app_id,
                num_lobbies_requested,
                cell_id: 0,
                filters,
            }
            .serialize(),
            app_id,
        )
    }

    pub fn build_lobby_create(
        &self,
        app_id: u32,
        lobby_type: i32,
        max_members: i32,
        job_id: u64,
    ) -> Option<OutboundProtoMessage> {
        if app_id == 0 {
            return None;
        }
        let persona_name = self
            .self_persona()
            .map(|persona| persona.player_name)
            .unwrap_or_default();
        self.build_job_proto_message(
            EMsg::CLIENT_MMS_CREATE_LOBBY,
            job_id,
            CMsgClientMMSCreateLobby {
                app_id,
                max_members,
                lobby_type,
                lobby_flags: 0,
                metadata: Vec::new(),
                persona_name_owner: persona_name,
            }
            .serialize(),
            app_id,
        )
    }

    pub fn build_lobby_join(
        &self,
        app_id: u32,
        lobby_sid: u64,
        job_id: u64,
    ) -> Option<OutboundProtoMessage> {
        if app_id == 0 || lobby_sid == 0 {
            return None;
        }
        let persona_name = self
            .self_persona()
            .map(|persona| persona.player_name)
            .unwrap_or_default();
        self.build_job_proto_message(
            EMsg::CLIENT_MMS_JOIN_LOBBY,
            job_id,
            CMsgClientMMSJoinLobby {
                app_id,
                steam_id_lobby: lobby_sid,
                persona_name,
            }
            .serialize(),
            app_id,
        )
    }

    pub fn build_lobby_leave(&self, app_id: u32, lobby_sid: u64) -> Option<OutboundProtoMessage> {
        if app_id == 0 || lobby_sid == 0 {
            return None;
        }
        self.build_outbound_proto_message(
            EMsg::CLIENT_MMS_LEAVE_LOBBY,
            CMsgClientMMSLeaveLobby {
                app_id,
                steam_id_lobby: lobby_sid,
            }
            .serialize(),
            app_id,
        )
    }

    pub fn build_lobby_send_chat(
        &self,
        app_id: u32,
        lobby_sid: u64,
        data: Vec<u8>,
    ) -> Option<OutboundProtoMessage> {
        if app_id == 0 || lobby_sid == 0 || data.is_empty() {
            return None;
        }
        self.build_outbound_proto_message(
            EMsg::CLIENT_MMS_SEND_LOBBY_CHAT_MSG,
            CMsgClientMMSSendLobbyChatMsg {
                app_id,
                steam_id_lobby: lobby_sid,
                lobby_message: data,
            }
            .serialize(),
            app_id,
        )
    }

    #[allow(clippy::too_many_arguments)]
    pub fn build_lobby_set_data(
        &self,
        app_id: u32,
        lobby_sid: u64,
        steam_id_member: u64,
        metadata: Vec<u8>,
        max_members: i32,
        lobby_type: i32,
        lobby_flags: i32,
        job_id: u64,
    ) -> Option<OutboundProtoMessage> {
        if app_id == 0 || lobby_sid == 0 {
            return None;
        }
        self.build_job_proto_message(
            EMsg::CLIENT_MMS_SET_LOBBY_DATA,
            job_id,
            CMsgClientMMSSetLobbyData {
                app_id,
                steam_id_lobby: lobby_sid,
                steam_id_member,
                max_members,
                lobby_type,
                lobby_flags,
                metadata,
            }
            .serialize(),
            app_id,
        )
    }

    pub fn build_lobby_set_owner(
        &self,
        app_id: u32,
        lobby_sid: u64,
        new_owner_sid: u64,
        job_id: u64,
    ) -> Option<OutboundProtoMessage> {
        if app_id == 0 || lobby_sid == 0 || new_owner_sid == 0 {
            return None;
        }
        self.build_job_proto_message(
            EMsg::CLIENT_MMS_SET_LOBBY_OWNER,
            job_id,
            CMsgClientMMSSetLobbyOwner {
                app_id,
                steam_id_lobby: lobby_sid,
                steam_id_new_owner: new_owner_sid,
            }
            .serialize(),
            app_id,
        )
    }

    pub fn build_lobby_invite_user(
        &self,
        app_id: u32,
        lobby_sid: u64,
        invitee_sid: u64,
    ) -> Option<OutboundProtoMessage> {
        if app_id == 0 || lobby_sid == 0 || invitee_sid == 0 {
            return None;
        }
        self.build_outbound_proto_message(
            EMsg::CLIENT_MMS_INVITE_TO_LOBBY,
            CMsgClientMMSInviteToLobby {
                app_id,
                steam_id_lobby: lobby_sid,
                steam_id_user_invited: invitee_sid,
            }
            .serialize(),
            app_id,
        )
    }

    pub fn route_inbound(
        &self,
        emsg: EMsg,
        header: &CMsgProtoBufHeader,
        body: &[u8],
    ) -> InboundAction {
        match emsg {
            EMsg::SERVICE_METHOD_RESPONSE
            | EMsg::CLIENT_PICS_ACCESS_TOKEN_RESPONSE
            | EMsg::CLIENT_PICS_CHANGES_SINCE_RESPONSE
            | EMsg::CLIENT_GET_APP_OWNERSHIP_TICKET_RESPONSE
            | EMsg::CLIENT_REQUEST_ENCRYPTED_APP_TICKET_RESPONSE
            | EMsg::CLIENT_GET_USER_STATS_RESPONSE
            | EMsg::CLIENT_GET_DEPOT_DECRYPTION_KEY_RESPONSE
            | EMsg::CLIENT_FRIEND_PROFILE_INFO_RESPONSE
            | EMsg::CLIENT_FS_GET_FRIENDS_STEAM_LEVELS_RESPONSE
            // 5573 carries its own EResult in the *body*; the job only reports transport health,
            // so the caller must read the body eresult rather than trusting the header.
            | EMsg::CLIENT_REQUEST_FREE_LICENSE_RESPONSE
            | EMsg::CLIENT_MMS_CREATE_LOBBY_RESPONSE
            | EMsg::CLIENT_MMS_JOIN_LOBBY_RESPONSE
            | EMsg::CLIENT_MMS_LEAVE_LOBBY_RESPONSE
            | EMsg::CLIENT_MMS_GET_LOBBY_LIST_RESPONSE
            | EMsg::CLIENT_MMS_SET_LOBBY_DATA_RESPONSE
            | EMsg::CLIENT_MMS_SET_LOBBY_OWNER_RESPONSE
            | EMsg::CLIENT_MMS_GET_LOBBY_STATUS_RESPONSE => {
                if header.jobid_target == INVALID_JOB_ID {
                    InboundAction::Ignored
                } else {
                    let eresult = if header.eresult == -1 {
                        1
                    } else {
                        header.eresult
                    };
                    InboundAction::DeliverJob(JobResult {
                        eresult,
                        error_message: header.error_message.clone(),
                        body: body.to_vec(),
                        synthetic_failure: false,
                    })
                }
            }
            EMsg::CLIENT_PICS_PRODUCT_INFO_RESPONSE => InboundAction::PicsProductInfo,
            EMsg::MULTI => InboundAction::Multi,
            EMsg::CLIENT_LOGON_RESPONSE => {
                let Some(resp) = CMsgClientLogonResponse::deserialize(body) else {
                    return InboundAction::ParseFailed("ClientLogonResponse");
                };
                if resp.eresult == 1 {
                    self.state
                        .store(ClientState::LoggedOn as u8, Ordering::Relaxed);
                    self.steam_id
                        .store(if resp.client_supplied_steamid != 0 {
                            resp.client_supplied_steamid
                        } else {
                            header.steamid
                        }, Ordering::Relaxed);
                    self.session_id
                        .store(header.client_sessionid, Ordering::Relaxed);
                    self.family_group_id
                        .store(resp.family_group_id, Ordering::Relaxed);
                    self.server_realtime
                        .store(resp.rtime32_server_time, Ordering::Relaxed);
                    InboundAction::LogonOk
                } else {
                    InboundAction::LoggedOff
                }
            }
            EMsg::CLIENT_LOGGED_OFF | EMsg::CLIENT_SERVER_UNAVAILABLE => {
                if self.state() == ClientState::LoggedOn {
                    self.state
                        .store(ClientState::Connected as u8, Ordering::Relaxed);
                }
                self.steam_id.store(0, Ordering::Relaxed);
                self.session_id.store(0, Ordering::Relaxed);
                self.family_group_id.store(0, Ordering::Relaxed);
                self.server_realtime.store(0, Ordering::Relaxed);
                InboundAction::LoggedOff
            }
            EMsg::CLIENT_LICENSE_LIST => {
                let Some(msg) = CMsgClientLicenseList::deserialize(body) else {
                    return InboundAction::ParseFailed("ClientLicenseList");
                };
                let count = msg.licenses.len();
                *self.license_list.lock().expect("license list poisoned") = msg.licenses.clone();
                self.library.ingest_license_list(&msg);
                InboundAction::LicenseList(count)
            }
            EMsg::CLIENT_FRIENDS_LIST => {
                let Some(msg) = CMsgClientFriendsList::deserialize(body) else {
                    return InboundAction::ParseFailed("ClientFriendsList");
                };
                let mut friends = self.friends.lock().expect("friends list poisoned");
                if !msg.bincremental {
                    friends.clear();
                }
                for friend in &msg.friends {
                    if friend.efriendrelationship == 0 {
                        friends.remove(&friend.ulfriendid);
                    } else {
                        friends.insert(friend.ulfriendid, friend.efriendrelationship);
                    }
                }
                InboundAction::FriendsList(friends.len())
            }
            EMsg::CLIENT_PERSONA_STATE => {
                let Some(msg) = CMsgClientPersonaState::deserialize(body) else {
                    return InboundAction::ParseFailed("ClientPersonaState");
                };
                let count = msg.friends.len();
                let self_id = self.steam_id();
                let mut self_persona = self.self_persona.lock().expect("self persona poisoned");
                let mut friend_personas = self
                    .friend_personas
                    .lock()
                    .expect("friend personas poisoned");
                for friend in msg.friends {
                    if friend.friendid == self_id {
                        match self_persona.as_mut() {
                            Some(existing) => {
                                if !friend.player_name.is_empty() {
                                    existing.player_name = friend.player_name;
                                }
                                if friend.has_persona_state {
                                    existing.persona_state = friend.persona_state;
                                }
                                if friend.has_game {
                                    existing.game_played_app_id = friend.game_played_app_id;
                                }
                                if !friend.game_name.is_empty() {
                                    existing.game_name = friend.game_name;
                                }
                                if friend.gameid != 0 {
                                    existing.gameid = friend.gameid;
                                }
                                if !friend.avatar_hash.is_empty() {
                                    existing.avatar_hash = friend.avatar_hash;
                                }
                                if !friend.rich_presence.is_empty() {
                                    existing.rich_presence = friend.rich_presence;
                                }
                            }
                            None => *self_persona = Some(friend),
                        }
                    } else {
                        let slot = friend_personas.entry(friend.friendid).or_default();
                        slot.sid = friend.friendid;
                        if !friend.player_name.is_empty() {
                            slot.player_name = friend.player_name;
                        }
                        if friend.has_persona_state {
                            slot.persona_state = friend.persona_state;
                        }
                        if friend.has_game {
                            slot.game_played_app_id = friend.game_played_app_id;
                        }
                        if !friend.game_name.is_empty() {
                            slot.game_name = friend.game_name;
                        }
                        if friend.gameid != 0 {
                            slot.gameid = friend.gameid;
                        }
                        if !friend.avatar_hash.is_empty() {
                            slot.avatar_hash = friend.avatar_hash;
                        }
                        if !friend.rich_presence.is_empty() {
                            slot.rich_presence = friend.rich_presence;
                        }
                    }
                }
                InboundAction::PersonaState(count)
            }
            EMsg::CLIENT_PLAYING_SESSION_STATE => {
                let Some(msg) = CMsgClientPlayingSessionState::deserialize(body) else {
                    return InboundAction::ParseFailed("ClientPlayingSessionState");
                };
                self.playing_blocked
                    .store(msg.playing_blocked, Ordering::Relaxed);
                InboundAction::PlayingSessionState(msg.playing_blocked)
            }
            EMsg::CLIENT_ACCOUNT_INFO => parse_account_info(body)
                .map(InboundAction::AccountInfo)
                .unwrap_or(InboundAction::ParseFailed("ClientAccountInfo")),
            EMsg::CLIENT_MMS_LOBBY_DATA
            | EMsg::CLIENT_MMS_LOBBY_CHAT_MSG
            | EMsg::CLIENT_MMS_USER_JOINED_LOBBY
            | EMsg::CLIENT_MMS_USER_LEFT_LOBBY => InboundAction::LobbyPush,
            EMsg::SERVICE_METHOD | EMsg::SERVICE_METHOD_SEND_TO_CLIENT => {
                if header
                    .target_job_name
                    .starts_with("FriendMessagesClient.IncomingMessage")
                {
                    if let Some(note) =
                        crate::pb::cfriendmessages::CFriendMessagesIncomingMessageNotification::deserialize(body)
                    {
                        let is_text = note.chat_entry_type
                            == crate::pb::cfriendmessages::CHAT_ENTRY_TYPE_TEXT
                            && !note.message.is_empty();
                        let is_typing = note.chat_entry_type
                            == crate::pb::cfriendmessages::CHAT_ENTRY_TYPE_TYPING
                            && !note.local_echo;
                        if is_text || is_typing {
                            self.push_incoming_message(IncomingFriendMessage {
                                friend_id: note.steamid_friend,
                                from_self: note.local_echo,
                                message: note.message,
                                timestamp: note.rtime32_server_timestamp,
                                ordinal: note.ordinal,
                                chat_entry_type: note.chat_entry_type,
                            });
                        }
                    }
                }
                InboundAction::ClientMessage
            }
            _ => InboundAction::ClientMessage,
        }
    }

    pub fn license_list(&self) -> Vec<License> {
        self.license_list
            .lock()
            .expect("license list poisoned")
            .clone()
    }

    pub fn friends_list(&self) -> Vec<u64> {
        self.friends
            .lock()
            .expect("friends list poisoned")
            .iter()
            .filter_map(|(sid, relationship)| (*relationship == 3).then_some(*sid))
            .collect()
    }

    /// Every known relationship (`EFriendRelationship`: 2 request received, 3 friend, 4 request
    /// sent, 5 ignored, 6 ignored friend, …), not just mutual friends — the roster's invite buckets.
    pub fn friend_relationships(&self) -> Vec<(u64, u32)> {
        self.friends
            .lock()
            .expect("friends list poisoned")
            .iter()
            .map(|(sid, relationship)| (*sid, *relationship))
            .collect()
    }

    pub fn self_persona(&self) -> Option<PersonaStateFriend> {
        self.self_persona
            .lock()
            .expect("self persona poisoned")
            .clone()
    }

    pub fn friend_personas(&self) -> Vec<FriendPersonaSnapshot> {
        self.friend_personas
            .lock()
            .expect("friend personas poisoned")
            .values()
            .filter(|snapshot| !snapshot.player_name.is_empty())
            .cloned()
            .collect()
    }

    pub fn push_incoming_message(&self, message: IncomingFriendMessage) {
        self.incoming_messages
            .lock()
            .expect("incoming messages poisoned")
            .push(message);
    }

    pub fn drain_incoming_messages(&self) -> Vec<IncomingFriendMessage> {
        std::mem::take(
            &mut *self
                .incoming_messages
                .lock()
                .expect("incoming messages poisoned"),
        )
    }
}

fn parse_account_info(body: &[u8]) -> Option<AccountInfoSnapshot> {
    let mut reader = crate::proto_wire::Reader::new(body);
    let mut info = AccountInfoSnapshot::default();
    while !reader.eof() {
        let Some(tag) = reader.next_tag() else {
            return reader.ok().then_some(info);
        };
        match tag.field_number {
            1 => info.persona_name = reader.string()?,
            2 => info.ip_country = reader.string()?,
            15 => info.two_factor_enabled = reader.u32()? != 0,
            17 => info.phone_verified = reader.boolean()?,
            19 => info.phone_identifying = reader.boolean()?,
            20 => info.phone_requires_verification = reader.boolean()?,
            _ => {
                if !reader.skip(tag.wire_type) {
                    return None;
                }
            }
        }
    }
    Some(info)
}

pub fn parse_cmsg_multi(body: &[u8]) -> Option<CMsgMultiBody> {
    let mut reader = crate::proto_wire::Reader::new(body);
    let mut multi = CMsgMultiBody::default();
    while !reader.eof() {
        let Some(tag) = reader.next_tag() else {
            return reader.ok().then_some(multi);
        };
        match tag.field_number {
            1 => multi.size_unzipped = reader.u32()?,
            2 => multi.message_body = reader.bytes()?.to_vec(),
            _ => {
                if !reader.skip(tag.wire_type) {
                    return None;
                }
            }
        }
    }
    Some(multi)
}

pub fn decode_multi_records(body: &[u8]) -> Option<Vec<Vec<u8>>> {
    let multi = parse_cmsg_multi(body)?;
    let records = if multi.size_unzipped > 0 {
        gunzip_or_zlib(&multi.message_body, multi.size_unzipped as usize)?
    } else {
        multi.message_body
    };

    let mut out = Vec::new();
    let mut offset = 0usize;
    while offset + 4 <= records.len() {
        let inner_len = crate::wire_format::read_u32_le(&records[offset..]) as usize;
        offset += 4;
        if inner_len == 0 || offset + inner_len > records.len() {
            return None;
        }
        out.push(records[offset..offset + inner_len].to_vec());
        offset += inner_len;
    }
    (offset == records.len()).then_some(out)
}

fn gunzip_or_zlib(compressed: &[u8], expected_size: usize) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(expected_size);
    if GzDecoder::new(compressed).read_to_end(&mut out).is_ok() && !out.is_empty() {
        return Some(out);
    }
    out.clear();
    if ZlibDecoder::new(compressed).read_to_end(&mut out).is_ok() && !out.is_empty() {
        return Some(out);
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto_wire::{WireType, Writer};
    use flate2::write::GzEncoder;
    use flate2::Compression;
    use std::io::Write;

    #[test]
    fn service_method_call_sets_authed_and_non_authed_emsgs() {
        let core = CMClientCore::default();
        core.steam_id.store(123, Ordering::Relaxed);
        core.session_id.store(7, Ordering::Relaxed);
        let authed = crate::proto_envelope::decode_proto_envelope(&core.build_service_method_call(
            "Player.GetOwnedGames#1",
            true,
            55,
            b"req",
        ))
        .unwrap();
        assert_eq!(authed.emsg, EMsg::SERVICE_METHOD_CALL_FROM_CLIENT);
        assert_eq!(authed.header.steamid, 123);
        assert_eq!(authed.header.client_sessionid, 7);
        assert_eq!(authed.header.jobid_source, 55);
        assert_eq!(authed.body, b"req");

        let non = crate::proto_envelope::decode_proto_envelope(&core.build_service_method_call(
            "Authentication.Begin#1",
            false,
            56,
            b"req",
        ))
        .unwrap();
        assert_eq!(non.emsg, EMsg::SERVICE_METHOD_CALL_FROM_CLIENT_NON_AUTHED);
        // Matches C++: header carries current steamid/session even on non-authed
        // calls (post-logon pre-existing identity is harmless; pre-logon both are 0).
        assert_eq!(non.header.steamid, 123);
        assert_eq!(non.header.client_sessionid, 7);
    }

    #[test]
    fn cmsg_multi_decodes_plain_and_gzip_records() {
        let first = encode_proto_envelope(EMsg::CLIENT_HELLO, &CMsgProtoBufHeader::default(), b"a");
        let second = encode_proto_envelope(
            EMsg::CLIENT_FRIENDS_LIST,
            &CMsgProtoBufHeader::default(),
            b"b",
        );
        let mut records = Vec::new();
        records.extend_from_slice(&(first.len() as u32).to_le_bytes());
        records.extend_from_slice(&first);
        records.extend_from_slice(&(second.len() as u32).to_le_bytes());
        records.extend_from_slice(&second);

        let mut body = Vec::new();
        {
            let mut writer = Writer::new(&mut body);
            writer.bytes_field(2, &records);
        }
        assert_eq!(
            decode_multi_records(&body).unwrap(),
            vec![first.clone(), second.clone()]
        );

        let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(&records).unwrap();
        let zipped = encoder.finish().unwrap();
        let mut zipped_body = Vec::new();
        {
            let mut writer = Writer::new(&mut zipped_body);
            writer.uint32_field(1, records.len() as u32);
            writer.bytes_field(2, &zipped);
        }
        assert_eq!(
            decode_multi_records(&zipped_body).unwrap(),
            vec![first, second]
        );
    }

    #[test]
    fn lifecycle_builders_match_cpp_session_flow() {
        let core = CMClientCore::default();
        let hello = core.build_client_hello();
        assert_eq!(hello.emsg, EMsg::CLIENT_HELLO);

        assert!(core
            .build_logon_with_refresh_token("refresh", "ada", 0)
            .is_none());
        core.set_state(ClientState::Connected);
        let logon = core
            .build_logon_with_refresh_token("refresh", "ada", 123)
            .unwrap();
        assert_eq!(logon.emsg, EMsg::CLIENT_LOGON);
        let decoded = crate::proto_envelope::decode_proto_envelope(&logon.wire).unwrap();
        assert_eq!(decoded.header.steamid, 0x0110_0001_0000_0000);
        assert!(logon
            .body
            .windows("refresh".len())
            .any(|window| window == b"refresh"));
        assert!(core
            .build_logon_with_refresh_token("refresh", "", 123)
            .is_some());

        assert!(core.build_logoff().is_none());
        core.set_state(ClientState::LoggedOn);
        assert_eq!(core.build_logoff().unwrap().emsg, EMsg::CLIENT_LOG_OFF);
    }

    #[test]
    fn qr_logon_uses_confirmed_header_identity_when_no_supplied_id_is_returned() {
        let core = CMClientCore::default();
        let mut body = Vec::new();
        Writer::new(&mut body).int32_field(1, 1);
        let header = CMsgProtoBufHeader {
            steamid: 76561198000000001,
            client_sessionid: 42,
            ..Default::default()
        };
        assert_eq!(core.route_inbound(EMsg::CLIENT_LOGON_RESPONSE, &header, &body), InboundAction::LogonOk);
        assert_eq!(core.steam_id(), 76561198000000001);
        assert_eq!(core.session_id(), 42);
    }

    #[test]
    fn logon_response_updates_session_identity() {
        let core = CMClientCore::default();
        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.int32_field(1, 1);
            w.tag(20, WireType::Fixed64);
            w.raw_bytes(&765u64.to_le_bytes());
            w.fixed32_field(5, 1_700_000_000);
            w.uint64_field(31, 99);
        }
        let header = CMsgProtoBufHeader {
            steamid: 999,
            client_sessionid: 42,
            ..Default::default()
        };
        assert_eq!(
            core.route_inbound(EMsg::CLIENT_LOGON_RESPONSE, &header, &body),
            InboundAction::LogonOk
        );
        assert_eq!(core.state(), ClientState::LoggedOn);
        assert_eq!(core.steam_id(), 765);
        assert_eq!(core.session_id(), 42);
        assert_eq!(core.family_group_id(), 99);
        assert_eq!(core.server_realtime(), 1_700_000_000);

        assert_eq!(
            core.route_inbound(EMsg::CLIENT_LOGGED_OFF, &CMsgProtoBufHeader::default(), &[]),
            InboundAction::LoggedOff
        );
        assert_eq!(core.state(), ClientState::Connected);
        assert_eq!(core.steam_id(), 0);
        assert_eq!(core.session_id(), 0);
        assert_eq!(core.family_group_id(), 0);
        assert_eq!(core.server_realtime(), 0);
    }

    #[test]
    fn routes_license_friends_and_playing_state_pushes() {
        let core = CMClientCore::default();
        let mut lic = Vec::new();
        Writer::new(&mut lic).uint32_field(1, 100);
        let mut license_body = Vec::new();
        Writer::new(&mut license_body).submessage_field(2, &lic);
        assert_eq!(
            core.route_inbound(
                EMsg::CLIENT_LICENSE_LIST,
                &CMsgProtoBufHeader::default(),
                &license_body
            ),
            InboundAction::LicenseList(1)
        );
        assert_eq!(core.license_list()[0].package_id, 100);

        let mut friend = Vec::new();
        {
            let mut w = Writer::new(&mut friend);
            w.fixed64_field(1, 555);
            w.uint32_field(2, 3);
        }
        let mut blocked_friend = Vec::new();
        {
            let mut w = Writer::new(&mut blocked_friend);
            w.fixed64_field(1, 999);
            w.uint32_field(2, 1);
        }
        let mut friends_body = Vec::new();
        {
            let mut w = Writer::new(&mut friends_body);
            w.bool_field(1, false);
            w.submessage_field(2, &friend);
            w.submessage_field(2, &blocked_friend);
        }
        assert_eq!(
            core.route_inbound(
                EMsg::CLIENT_FRIENDS_LIST,
                &CMsgProtoBufHeader::default(),
                &friends_body
            ),
            InboundAction::FriendsList(2)
        );
        assert_eq!(core.friends_list(), [555]);

        let mut playing = Vec::new();
        {
            let mut w = Writer::new(&mut playing);
            w.tag(2, WireType::Varint);
            w.varint(1);
        }
        assert_eq!(
            core.route_inbound(
                EMsg::CLIENT_PLAYING_SESSION_STATE,
                &CMsgProtoBufHeader::default(),
                &playing
            ),
            InboundAction::PlayingSessionState(true)
        );
        assert!(core.is_playing_blocked());
    }

    #[test]
    fn friend_persona_snapshots_exclude_empty_names_like_cpp() {
        let core = logged_on_core();
        let mut named = Vec::new();
        {
            let mut w = Writer::new(&mut named);
            w.fixed64_field(1, 111);
            w.string_field(15, "Ada");
        }
        let mut unnamed = Vec::new();
        Writer::new(&mut unnamed).fixed64_field(1, 222);
        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.submessage_field(2, &named);
            w.submessage_field(2, &unnamed);
        }
        assert_eq!(
            core.route_inbound(
                EMsg::CLIENT_PERSONA_STATE,
                &CMsgProtoBufHeader::default(),
                &body
            ),
            InboundAction::PersonaState(2)
        );
        let personas = core.friend_personas();
        assert_eq!(personas.len(), 1);
        assert_eq!(personas[0].sid, 111);
    }

    #[test]
    fn incremental_friend_removal_and_persona_partial_updates_match_cpp() {
        let core = logged_on_core();
        let mut add = Vec::new();
        {
            let mut friend = Vec::new();
            let mut w = Writer::new(&mut friend);
            w.fixed64_field(1, 333);
            w.uint32_field(2, 3);
            Writer::new(&mut add).submessage_field(2, &friend);
        }
        assert_eq!(
            core.route_inbound(
                EMsg::CLIENT_FRIENDS_LIST,
                &CMsgProtoBufHeader::default(),
                &add
            ),
            InboundAction::FriendsList(1)
        );
        assert_eq!(core.friends_list(), [333]);

        let mut remove = Vec::new();
        {
            let mut friend = Vec::new();
            let mut w = Writer::new(&mut friend);
            w.fixed64_field(1, 333);
            w.uint32_field(2, 0);
            let mut w = Writer::new(&mut remove);
            w.bool_field(1, true);
            w.submessage_field(2, &friend);
        }
        assert_eq!(
            core.route_inbound(
                EMsg::CLIENT_FRIENDS_LIST,
                &CMsgProtoBufHeader::default(),
                &remove
            ),
            InboundAction::FriendsList(0)
        );
        assert!(core.friends_list().is_empty());

        let mut first_friend = Vec::new();
        {
            let mut w = Writer::new(&mut first_friend);
            w.fixed64_field(1, 444);
            w.string_field(15, "Grace");
            w.bytes_field(31, &[9, 9]);
        }
        let mut first_body = Vec::new();
        Writer::new(&mut first_body).submessage_field(2, &first_friend);
        core.route_inbound(
            EMsg::CLIENT_PERSONA_STATE,
            &CMsgProtoBufHeader::default(),
            &first_body,
        );

        let mut partial_friend = Vec::new();
        {
            let mut w = Writer::new(&mut partial_friend);
            w.fixed64_field(1, 444);
            w.uint32_field(2, 2);
        }
        let mut partial_body = Vec::new();
        Writer::new(&mut partial_body).submessage_field(2, &partial_friend);
        core.route_inbound(
            EMsg::CLIENT_PERSONA_STATE,
            &CMsgProtoBufHeader::default(),
            &partial_body,
        );
        let persona = core.friend_personas().pop().unwrap();
        assert_eq!(persona.player_name, "Grace");
        assert_eq!(persona.avatar_hash, [9, 9]);
        assert_eq!(persona.persona_state, 2);
    }

    #[test]
    fn account_info_push_parses_bridge_fields() {
        let core = CMClientCore::default();
        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.string_field(1, "Ada");
            w.string_field(2, "US");
            w.uint32_field(15, 1);
            w.bool_field(17, true);
            w.bool_field(19, true);
            w.bool_field(20, false);
        }
        assert_eq!(
            core.route_inbound(
                EMsg::CLIENT_ACCOUNT_INFO,
                &CMsgProtoBufHeader::default(),
                &body
            ),
            InboundAction::AccountInfo(AccountInfoSnapshot {
                persona_name: "Ada".into(),
                ip_country: "US".into(),
                two_factor_enabled: true,
                phone_verified: true,
                phone_identifying: true,
                phone_requires_verification: false,
            })
        );
    }

    #[test]
    fn outbound_persona_and_friend_requests_match_cpp_emsgs() {
        let core = logged_on_core();
        let persona = core.build_set_persona_name("Ada", 1).unwrap();
        assert_eq!(persona.emsg, EMsg::CLIENT_CHANGE_STATUS);
        assert_eq!(
            persona.body,
            CMsgClientChangeStatus {
                persona_state: 1,
                player_name: "Ada".into(),
                persona_set_by_user: true,
                need_persona_response: false,
            }
            .serialize()
        );

        let request = core
            .build_request_friend_personas(&[0, 123, 456], 0x47)
            .unwrap();
        assert_eq!(request.emsg, EMsg::CLIENT_REQUEST_FRIEND_DATA);
        assert!(request.body.windows(2).any(|w| w == [0x08, 0x47]));

        let self_request = core.build_request_user_persona().unwrap();
        assert_eq!(self_request.emsg, EMsg::CLIENT_REQUEST_FRIEND_DATA);
    }

    #[test]
    fn outbound_games_stats_and_ticket_requests_include_routing_and_jobs() {
        let core = logged_on_core();
        let games = core.build_notify_games_played(480).unwrap();
        assert_eq!(games.emsg, EMsg::CLIENT_GAMES_PLAYED_WITH_DATA_BLOB);
        assert_eq!(
            games.body,
            CMsgClientGamesPlayed {
                games_played: vec![GamePlayedEntry {
                    game_id: 480,
                    ..Default::default()
                }],
                client_os_type: 0
            }
            .serialize()
        );
        assert!(core.build_notify_games_played(0).unwrap().body.len() <= games.body.len());

        let stats = core
            .build_store_user_stats(480, core.steam_id(), 0, &[(1, 2), (3, 0)])
            .unwrap();
        assert_eq!(stats.emsg, EMsg::CLIENT_STORE_USER_STATS_2);
        assert_eq!(stats.routing_appid, 480);

        let ticket = core.build_get_app_ownership_ticket(480, 99).unwrap();
        let decoded = crate::proto_envelope::decode_proto_envelope(&ticket.wire).unwrap();
        assert_eq!(decoded.emsg, EMsg::CLIENT_GET_APP_OWNERSHIP_TICKET);
        assert_eq!(decoded.header.jobid_source, 99);
        assert_eq!(decoded.header.jobid_target, INVALID_JOB_ID);

        let encrypted = core.build_request_encrypted_app_ticket(480, 123).unwrap();
        let decoded = crate::proto_envelope::decode_proto_envelope(&encrypted.wire).unwrap();
        assert_eq!(decoded.emsg, EMsg::CLIENT_REQUEST_ENCRYPTED_APP_TICKET);
        assert_eq!(decoded.header.jobid_source, 123);
        assert_eq!(decoded.header.jobid_target, INVALID_JOB_ID);
        assert_eq!(decoded.header.routing_appid, 0);
    }

    #[test]
    fn outbound_rich_presence_is_authed_service_call() {
        let core = logged_on_core();
        let call = core
            .build_rich_presence_call(
                480,
                [
                    ("status".to_string(), "Playing".to_string()),
                    ("".into(), "skip".into()),
                ],
                77,
            )
            .unwrap();
        assert_eq!(call.method_name, "Player.SetRichPresence#1");
        assert!(call.authed);
        let decoded = crate::proto_envelope::decode_proto_envelope(&call.wire).unwrap();
        assert_eq!(decoded.emsg, EMsg::SERVICE_METHOD_CALL_FROM_CLIENT);
        assert_eq!(decoded.header.jobid_source, 77);
        assert_eq!(decoded.header.steamid, core.steam_id());
    }

    #[test]
    fn downloader_service_builders_match_cpp_rules() {
        let core = logged_on_core();
        let depot_key = core.build_get_depot_decryption_key(11, 22, 33).unwrap();
        let decoded = crate::proto_envelope::decode_proto_envelope(&depot_key.wire).unwrap();
        assert_eq!(decoded.emsg, EMsg::CLIENT_GET_DEPOT_DECRYPTION_KEY);
        assert_eq!(decoded.header.jobid_source, 33);

        let public = core
            .build_manifest_request_code_call(480, 100, 555, "public", 44)
            .unwrap();
        assert_eq!(
            public.method_name,
            "ContentServerDirectory.GetManifestRequestCode#1"
        );
        assert!(!public.request_body.windows(6).any(|w| w == b"public"));

        let beta = core
            .build_manifest_request_code_call(480, 100, 555, "Beta", 45)
            .unwrap();
        assert!(beta.request_body.windows(4).any(|w| w == b"Beta"));

        let servers = core.build_get_cdn_servers_call(7, 46).unwrap();
        assert_eq!(
            servers.method_name,
            "ContentServerDirectory.GetServersForSteamPipe#1"
        );
    }

    #[test]
    fn pics_builders_set_job_ids_and_emsgs() {
        let core = logged_on_core();
        let access = core
            .build_pics_access_tokens(vec![100], vec![480], 1)
            .unwrap();
        assert_eq!(access.emsg, EMsg::CLIENT_PICS_ACCESS_TOKEN_REQUEST);
        assert_eq!(
            crate::proto_envelope::decode_proto_envelope(&access.wire)
                .unwrap()
                .header
                .jobid_source,
            1
        );

        let changes = core.build_pics_changes_since(99, 2).unwrap();
        assert_eq!(changes.emsg, EMsg::CLIENT_PICS_CHANGES_SINCE_REQUEST);
        assert!(changes.body.windows(2).any(|w| w == [0x08, 99]));

        let product = core
            .build_pics_product_info(
                vec![PicsPackageInfoReq {
                    packageid: 100,
                    access_token: 7,
                }],
                vec![PicsAppInfoReq {
                    appid: 480,
                    access_token: 8,
                    only_public_obsolete: false,
                }],
                true,
                3,
            )
            .unwrap();
        assert_eq!(product.emsg, EMsg::CLIENT_PICS_PRODUCT_INFO_REQUEST);
        assert!(product.body.windows(2).any(|w| w == [0x18, 1]));
    }

    #[test]
    fn profile_surface_builders_use_the_expected_methods() {
        let core = logged_on_core();

        let equipped = core
            .build_profile_items_equipped_call(76561197960287930, "english", 40)
            .unwrap();
        assert_eq!(equipped.method_name, "Player.GetProfileItemsEquipped#1");
        // steamid is field 1 as a *varint* uint64, not the fixed64 used by clientserver msgs.
        assert_eq!(equipped.request_body[0], 0x08);
        assert!(equipped
            .request_body
            .windows("english".len())
            .any(|w| w == b"english"));

        let badge = core.build_favorite_badge_call(76561197960287930, 41).unwrap();
        assert_eq!(badge.method_name, "Player.GetFavoriteBadge#1");
        assert_eq!(badge.request_body[0], 0x08);

        let levels = core.build_friends_steam_levels(&[1, 2, 3], 42).unwrap();
        assert_eq!(levels.emsg, EMsg::CLIENT_FS_GET_FRIENDS_STEAM_LEVELS);
        assert_eq!(levels.body, [0x08, 1, 0x08, 2, 0x08, 3]);

        // Guard rails: no addressable id means no request at all.
        assert!(core.build_profile_items_equipped_call(0, "english", 43).is_none());
        assert!(core.build_favorite_badge_call(0, 44).is_none());
        assert!(core.build_friends_steam_levels(&[], 45).is_none());
    }

    #[test]
    fn free_license_builder_targets_emsg_5572_field_two() {
        let core = logged_on_core();
        let free = core.build_request_free_license(&[440], 50).unwrap();
        assert_eq!(free.emsg, EMsg::CLIENT_REQUEST_FREE_LICENSE);
        // appids is field 2 -> tag 0x10; 440 = varint [0xb8, 0x03].
        assert_eq!(free.body, [0x10, 0xb8, 0x03]);
        assert!(core.build_request_free_license(&[], 51).is_none());
    }

    #[test]
    fn free_license_response_is_delivered_to_its_job() {
        let core = logged_on_core();
        let header = CMsgProtoBufHeader {
            jobid_target: 50,
            ..Default::default()
        };
        assert!(matches!(
            core.route_inbound(EMsg::CLIENT_REQUEST_FREE_LICENSE_RESPONSE, &header, &[]),
            InboundAction::DeliverJob(_)
        ));
    }

    #[test]
    fn account_inventory_workshop_and_family_service_builders() {
        let core = logged_on_core();
        assert_eq!(
            core.build_family_group_call(123, 10).unwrap().method_name,
            "FamilyGroups.GetFamilyGroup#1"
        );

        let owned = core.build_owned_games_call(765, 11).unwrap();
        assert_eq!(owned.method_name, "Player.GetOwnedGames#1");
        assert!(owned.request_body.windows(2).any(|w| w == [0x10, 1]));
        assert!(owned.request_body.windows(2).any(|w| w == [0x18, 1]));

        assert_eq!(
            core.build_inventory_item_def_meta_call(480, 12)
                .unwrap()
                .method_name,
            "Inventory.GetItemDefMeta#1"
        );

        let workshop = core
            .build_published_file_subscribed_call(480, 2, 50, 13)
            .unwrap();
        assert_eq!(workshop.method_name, "PublishedFile.GetUserFiles#1");
        assert!(workshop
            .request_body
            .windows("mysubscriptions".len())
            .any(|w| w == b"mysubscriptions"));
        assert!(workshop
            .request_body
            .windows(6)
            .any(|w| w == [0x70, 0xff, 0xff, 0xff, 0xff, 0x0f]));
    }

    #[test]
    fn cloud_service_builders_use_cpp_method_names() {
        let core = logged_on_core();
        assert_eq!(
            core.build_cloud_user_quota_call(20).unwrap().method_name,
            "Cloud.GetUserQuota#1"
        );
        assert_eq!(
            core.build_cloud_app_file_changelist_call(480, 7, 21)
                .unwrap()
                .method_name,
            "Cloud.GetAppFileChangelist#1"
        );
        assert_eq!(
            core.build_cloud_file_download_info_call(480, "save.dat", 22)
                .unwrap()
                .method_name,
            "Cloud.ClientFileDownload#1"
        );
        assert_eq!(
            core.build_cloud_begin_app_upload_batch_call(
                480,
                "machine",
                vec!["save.dat".into()],
                vec![],
                99,
                23
            )
            .unwrap()
            .method_name,
            "Cloud.BeginAppUploadBatch#1"
        );
        assert_eq!(
            core.build_cloud_begin_file_upload_call(
                480,
                "save.dat",
                10,
                20,
                vec![1; 20],
                123,
                555,
                24
            )
            .unwrap()
            .method_name,
            "Cloud.ClientBeginFileUpload#1"
        );
        assert_eq!(
            core.build_cloud_commit_file_upload_call(true, 480, vec![1; 20], "save.dat", 25)
                .unwrap()
                .method_name,
            "Cloud.ClientCommitFileUpload#1"
        );
        assert_eq!(
            core.build_cloud_complete_app_upload_batch_call(480, 555, 1, 26)
                .unwrap()
                .method_name,
            "Cloud.CompleteAppUploadBatchBlocking#1"
        );
        assert_eq!(
            core.build_cloud_launch_intent_call(480, 99, "machine", false, 16, 27)
                .unwrap()
                .method_name,
            "Cloud.SignalAppLaunchIntent#1"
        );
        assert_eq!(
            core.build_cloud_exit_sync_done_call(480, 99, true, false, 28)
                .unwrap()
                .method_name,
            "Cloud.SignalAppExitSyncDone#1"
        );
    }

    #[test]
    fn prepare_app_helpers_dedupe_dlc_and_pick_tokens() {
        let core = logged_on_core();
        let ids = CMClientCore::prepare_app_ids(480, &[0, 480, 481, 481, 482]);
        assert_eq!(ids, [480, 481, 482]);

        core.library().ingest_app_pics_response(
            &crate::pb::cmsg_client_pics::CMsgClientPICSProductInfoResponse {
                apps: vec![crate::pb::cmsg_client_pics::PicsAppInfoResp {
                    appid: 481,
                    missing_token: true,
                    ..Default::default()
                }],
                ..Default::default()
            },
        );
        core.library().ingest_app_access_tokens(
            &crate::pb::cmsg_client_pics::CMsgClientPICSAccessTokenResponse {
                app_access_tokens: vec![crate::pb::cmsg_client_pics::PicsAppToken {
                    appid: 482,
                    access_token: 99,
                }],
                ..Default::default()
            },
        );

        assert_eq!(core.prepare_app_missing_token_ids(&ids), [481]);
        let reqs = core.prepare_app_pics_requests(&ids);
        assert_eq!(reqs[0].appid, 480);
        assert_eq!(reqs[1].access_token, 0);
        assert_eq!(reqs[2].access_token, 99);
    }

    #[test]
    fn outbound_builders_drop_when_not_logged_on() {
        let core = CMClientCore::default();
        assert!(core.build_set_persona_state(1).is_none());
        assert!(core.build_notify_games_played(480).is_none());
        assert!(core.build_get_app_ownership_ticket(480, 1).is_none());
        assert!(core
            .build_rich_presence_call(480, [("status".into(), "Playing".into())], 1)
            .is_none());
    }

    fn logged_on_core() -> CMClientCore {
        let core = CMClientCore::default();
        core.state
            .store(ClientState::LoggedOn as u8, Ordering::Relaxed);
        core.steam_id.store(7656119, Ordering::Relaxed);
        core.session_id.store(42, Ordering::Relaxed);
        core
    }
}
