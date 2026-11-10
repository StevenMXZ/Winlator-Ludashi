use crate::cm_client::{AccountInfoSnapshot, CMClientCore, FriendPersonaSnapshot};
use crate::emsg::EMsg;
use crate::pb::cmsg_client_license_list::License;
use crate::pb::cmsg_client_mms_lobby_data::CMsgClientMMSLobbyData;
use crate::pb::cmsg_client_mms_lobby_ops::{
    CMsgClientMMSLobbyChatMsg, CMsgClientMMSUserJoinedOrLeftLobby,
};
use crate::pb::cmsg_client_persona::PersonaStateFriend;
use std::env;
use std::ffi::{CStr, CString};
use std::fs;
use std::io::Write;
use std::os::raw::c_char;
use std::path::{Path, PathBuf};
use std::ptr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, SystemTime};

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct LudashiCmRichPresenceKV {
    pub key: *const c_char,
    pub value: *const c_char,
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct LudashiCmPersonaEvent {
    pub sid: u64,
    pub persona_state: u32,
    pub game_played_app: u32,
    pub name: *const c_char,
    pub avatar_hash: *const u8,
    pub avatar_hash_len: usize,
    pub rp_pairs: *const LudashiCmRichPresenceKV,
    pub rp_count: usize,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct LudashiCmLicenseEntry {
    pub package_id: u32,
    pub owner_id: u32,
    pub time_created: u32,
    pub license_type: u32,
    pub flags: u32,
    pub change_number: i32,
    pub minute_limit: i32,
    pub minutes_used: i32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct LudashiCmAccountInfo {
    pub persona_name: *const c_char,
    pub persona_name_len: usize,
    pub ip_country: *const c_char,
    pub ip_country_len: usize,
    pub two_factor_enabled: bool,
    pub phone_verified: bool,
    pub phone_identifying: bool,
    pub phone_requires_verification: bool,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct LudashiCmLobbyEntry {
    pub steam_id: u64,
    pub max_members: i32,
    pub num_members: i32,
    pub lobby_type: i32,
    pub lobby_flags: i32,
    pub ping_ms: i32,
    pub weight: i64,
    pub distance: f32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct LudashiCmLobbyMember {
    pub steam_id: u64,
    pub persona_name: *const c_char,
    pub metadata_bytes: *const u8,
    pub metadata_len: usize,
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct LudashiCmLobbyData {
    pub steam_id_lobby: u64,
    pub steam_id_owner: u64,
    pub app_id: u32,
    pub max_members: i32,
    pub num_members: i32,
    pub lobby_type: i32,
    pub lobby_flags: i32,
    pub metadata_bytes: *const u8,
    pub metadata_len: usize,
    pub members: *const LudashiCmLobbyMember,
    pub member_count: usize,
}

pub type LudashiCmPersonaObserverFn = Option<extern "C" fn(*const LudashiCmPersonaEvent)>;
pub type LudashiCmLogonStateObserverFn = Option<extern "C" fn(bool)>;
pub type LudashiCmFriendsListObserverFn = Option<extern "C" fn(*const u64, usize)>;
pub type LudashiCmLicenseListObserverFn = Option<extern "C" fn(*const LudashiCmLicenseEntry, usize)>;
pub type LudashiCmAccountInfoObserverFn = Option<extern "C" fn(*const LudashiCmAccountInfo)>;
pub type LudashiCmServerRealTimeObserverFn = Option<extern "C" fn(u32)>;
pub type LudashiCmLobbyListCb = Option<extern "C" fn(u64, i32, *const LudashiCmLobbyEntry, usize)>;
pub type LudashiCmLobbyDataObserverFn = Option<extern "C" fn(*const LudashiCmLobbyData)>;
pub type LudashiCmLobbyCreatedCb = Option<extern "C" fn(u64, i32, u64)>;
pub type LudashiCmLobbyJoinedCb = Option<extern "C" fn(u64, i32, u64)>;
pub type LudashiCmLobbySetDataCb = Option<extern "C" fn(u64, i32)>;
pub type LudashiCmLobbySetOwnerCb = Option<extern "C" fn(u64, i32)>;
pub type LudashiCmLobbyChatMsgObserverFn = Option<extern "C" fn(u64, u64, *const u8, usize)>;
pub type LudashiCmLobbyMembershipObserverFn = Option<extern "C" fn(i32, u64, u64, *const c_char)>;

#[derive(Default)]
pub struct CmBridgeObservers {
    persona: Mutex<LudashiCmPersonaObserverFn>,
    logon_state: Mutex<LudashiCmLogonStateObserverFn>,
    friends_list: Mutex<LudashiCmFriendsListObserverFn>,
    license_list: Mutex<LudashiCmLicenseListObserverFn>,
    account_info: Mutex<LudashiCmAccountInfoObserverFn>,
    server_realtime: Mutex<LudashiCmServerRealTimeObserverFn>,
    lobby_data: Mutex<LudashiCmLobbyDataObserverFn>,
    lobby_chat_msg: Mutex<LudashiCmLobbyChatMsgObserverFn>,
    lobby_membership: Mutex<LudashiCmLobbyMembershipObserverFn>,
}

impl CmBridgeObservers {
    pub fn register_persona(&self, callback: LudashiCmPersonaObserverFn) {
        *self.persona.lock().expect("persona observer poisoned") = callback;
    }

    pub fn dispatch_persona(&self, event: &LudashiCmPersonaEvent) {
        if let Some(callback) = *self.persona.lock().expect("persona observer poisoned") {
            callback(event);
        }
    }

    pub fn register_logon_state(&self, callback: LudashiCmLogonStateObserverFn) {
        *self.logon_state.lock().expect("logon observer poisoned") = callback;
    }

    pub fn dispatch_logon_state(&self, logged_on: bool) {
        if let Some(callback) = *self.logon_state.lock().expect("logon observer poisoned") {
            callback(logged_on);
        }
    }

    pub fn register_friends_list(&self, callback: LudashiCmFriendsListObserverFn) {
        *self.friends_list.lock().expect("friends observer poisoned") = callback;
    }

    pub fn dispatch_friends_list(&self, friends: &[u64]) {
        if let Some(callback) = *self.friends_list.lock().expect("friends observer poisoned") {
            callback(friends.as_ptr(), friends.len());
        }
    }

    pub fn register_license_list(&self, callback: LudashiCmLicenseListObserverFn) {
        *self.license_list.lock().expect("license observer poisoned") = callback;
    }

    pub fn dispatch_license_list(&self, licenses: &[LudashiCmLicenseEntry]) {
        if let Some(callback) = *self.license_list.lock().expect("license observer poisoned") {
            callback(licenses.as_ptr(), licenses.len());
        }
    }

    pub fn register_account_info(&self, callback: LudashiCmAccountInfoObserverFn) {
        *self.account_info.lock().expect("account observer poisoned") = callback;
    }

    pub fn dispatch_account_info(&self, info: &LudashiCmAccountInfo) {
        if let Some(callback) = *self.account_info.lock().expect("account observer poisoned") {
            callback(info);
        }
    }

    pub fn register_server_realtime(&self, callback: LudashiCmServerRealTimeObserverFn) {
        *self
            .server_realtime
            .lock()
            .expect("server observer poisoned") = callback;
    }

    pub fn dispatch_server_realtime(&self, server_realtime: u32) {
        if server_realtime == 0 {
            return;
        }
        if let Some(callback) = *self
            .server_realtime
            .lock()
            .expect("server observer poisoned")
        {
            callback(server_realtime);
        }
    }

    pub fn register_lobby_data(&self, callback: LudashiCmLobbyDataObserverFn) {
        *self
            .lobby_data
            .lock()
            .expect("lobby data observer poisoned") = callback;
    }

    pub fn dispatch_lobby_data(&self, data: &LudashiCmLobbyData) {
        if let Some(callback) = *self
            .lobby_data
            .lock()
            .expect("lobby data observer poisoned")
        {
            callback(data);
        }
    }

    pub fn register_lobby_chat_msg(&self, callback: LudashiCmLobbyChatMsgObserverFn) {
        *self
            .lobby_chat_msg
            .lock()
            .expect("lobby chat observer poisoned") = callback;
    }

    pub fn dispatch_lobby_chat_msg(&self, lobby_sid: u64, sender_sid: u64, bytes: &[u8]) {
        if let Some(callback) = *self
            .lobby_chat_msg
            .lock()
            .expect("lobby chat observer poisoned")
        {
            callback(lobby_sid, sender_sid, bytes.as_ptr(), bytes.len());
        }
    }

    pub fn register_lobby_membership(&self, callback: LudashiCmLobbyMembershipObserverFn) {
        *self
            .lobby_membership
            .lock()
            .expect("lobby membership observer poisoned") = callback;
    }

    pub fn dispatch_lobby_membership(
        &self,
        joined: bool,
        lobby_sid: u64,
        user_sid: u64,
        persona_name: &CStr,
    ) {
        if let Some(callback) = *self
            .lobby_membership
            .lock()
            .expect("lobby membership observer poisoned")
        {
            callback(
                if joined { 1 } else { 0 },
                lobby_sid,
                user_sid,
                persona_name.as_ptr(),
            );
        }
    }
}

#[derive(Default)]
pub struct CmBridge {
    active: Mutex<Option<Arc<CMClientCore>>>,
    observers: CmBridgeObservers,
}

impl CmBridge {
    pub fn set_active(&self, client: Arc<CMClientCore>) {
        *self.active.lock().expect("active client poisoned") = Some(client);
    }

    pub fn clear_active(&self) {
        *self.active.lock().expect("active client poisoned") = None;
    }

    pub fn active(&self) -> Option<Arc<CMClientCore>> {
        self.active.lock().expect("active client poisoned").clone()
    }

    pub fn observers(&self) -> &CmBridgeObservers {
        &self.observers
    }

    pub fn inject_ownership_ticket(&self, app_id: u32, ticket: &[u8]) -> bool {
        if app_id == 0 || ticket.is_empty() {
            return false;
        }
        let Some(client) = self.active() else {
            return false;
        };
        client.tickets().store(app_id, 1, ticket.to_vec());
        true
    }

    pub fn cached_ownership_ticket(&self, app_id: u32) -> Option<Vec<u8>> {
        if app_id == 0 {
            return None;
        }
        self.active()
            .and_then(|client| client.tickets().get(app_id))
            .filter(|ticket| ticket.eresult == 1 && !ticket.ticket.is_empty())
            .map(|ticket| ticket.ticket)
    }

    pub fn dispatch_client_snapshots(&self, client: &CMClientCore) {
        let friends = client.friends_list();
        self.observers.dispatch_friends_list(&friends);

        let licenses = client
            .license_list()
            .iter()
            .map(LudashiCmLicenseEntry::from)
            .collect::<Vec<_>>();
        self.observers.dispatch_license_list(&licenses);

        if let Some(self_persona) = client.self_persona() {
            self.dispatch_persona_friend(&self_persona);
        }

        self.observers
            .dispatch_server_realtime(client.server_realtime());
    }

    pub fn dispatch_persona_friend(&self, friend: &PersonaStateFriend) {
        let name = CString::new(friend.player_name.as_str()).unwrap_or_default();
        let key_values = friend
            .rich_presence
            .iter()
            .map(|(key, value)| {
                (
                    CString::new(key.as_str()).unwrap_or_default(),
                    CString::new(value.as_str()).unwrap_or_default(),
                )
            })
            .collect::<Vec<_>>();
        let pairs = key_values
            .iter()
            .map(|(key, value)| LudashiCmRichPresenceKV {
                key: key.as_ptr(),
                value: value.as_ptr(),
            })
            .collect::<Vec<_>>();
        let event = LudashiCmPersonaEvent {
            sid: friend.friendid,
            persona_state: friend.persona_state,
            game_played_app: friend.game_played_app_id,
            name: name.as_ptr(),
            avatar_hash: friend.avatar_hash.as_ptr(),
            avatar_hash_len: friend.avatar_hash.len(),
            rp_pairs: pairs.as_ptr(),
            rp_count: pairs.len(),
        };
        self.observers.dispatch_persona(&event);
    }

    pub fn dispatch_persona_snapshot(&self, snapshot: &FriendPersonaSnapshot) {
        let name = CString::new(snapshot.player_name.as_str()).unwrap_or_default();
        let key_values = snapshot
            .rich_presence
            .iter()
            .map(|(key, value)| {
                (
                    CString::new(key.as_str()).unwrap_or_default(),
                    CString::new(value.as_str()).unwrap_or_default(),
                )
            })
            .collect::<Vec<_>>();
        let pairs = key_values
            .iter()
            .map(|(key, value)| LudashiCmRichPresenceKV {
                key: key.as_ptr(),
                value: value.as_ptr(),
            })
            .collect::<Vec<_>>();
        let event = LudashiCmPersonaEvent {
            sid: snapshot.sid,
            persona_state: snapshot.persona_state,
            game_played_app: snapshot.game_played_app_id,
            name: if snapshot.player_name.is_empty() {
                ptr::null()
            } else {
                name.as_ptr()
            },
            avatar_hash: snapshot.avatar_hash.as_ptr(),
            avatar_hash_len: snapshot.avatar_hash.len(),
            rp_pairs: if pairs.is_empty() {
                ptr::null()
            } else {
                pairs.as_ptr()
            },
            rp_count: pairs.len(),
        };
        self.observers.dispatch_persona(&event);
    }

    pub fn dispatch_account_info_snapshot(&self, snapshot: &AccountInfoSnapshot) {
        let persona_name = CString::new(snapshot.persona_name.as_str()).unwrap_or_default();
        let ip_country = CString::new(snapshot.ip_country.as_str()).unwrap_or_default();
        let info = LudashiCmAccountInfo {
            persona_name: if snapshot.persona_name.is_empty() {
                ptr::null()
            } else {
                persona_name.as_ptr()
            },
            persona_name_len: snapshot.persona_name.len(),
            ip_country: if snapshot.ip_country.is_empty() {
                ptr::null()
            } else {
                ip_country.as_ptr()
            },
            ip_country_len: snapshot.ip_country.len(),
            two_factor_enabled: snapshot.two_factor_enabled,
            phone_verified: snapshot.phone_verified,
            phone_identifying: snapshot.phone_identifying,
            phone_requires_verification: snapshot.phone_requires_verification,
        };
        self.observers.dispatch_account_info(&info);
    }

    pub fn dispatch_lobby_push(&self, emsg: EMsg, body: &[u8]) -> bool {
        match emsg {
            EMsg::CLIENT_MMS_LOBBY_DATA => {
                let Some(msg) = CMsgClientMMSLobbyData::deserialize(body) else {
                    return false;
                };
                self.dispatch_lobby_data_message(&msg);
                true
            }
            EMsg::CLIENT_MMS_LOBBY_CHAT_MSG => {
                let Some(msg) = CMsgClientMMSLobbyChatMsg::deserialize(body) else {
                    return false;
                };
                self.observers.dispatch_lobby_chat_msg(
                    msg.steam_id_lobby,
                    msg.steam_id_sender,
                    &msg.lobby_message,
                );
                true
            }
            EMsg::CLIENT_MMS_USER_JOINED_LOBBY | EMsg::CLIENT_MMS_USER_LEFT_LOBBY => {
                let Some(msg) = CMsgClientMMSUserJoinedOrLeftLobby::deserialize(body) else {
                    return false;
                };
                let persona_name = CString::new(msg.persona_name.as_str()).unwrap_or_default();
                self.observers.dispatch_lobby_membership(
                    emsg == EMsg::CLIENT_MMS_USER_JOINED_LOBBY,
                    msg.steam_id_lobby,
                    msg.steam_id_user,
                    &persona_name,
                );
                true
            }
            _ => false,
        }
    }

    pub fn dispatch_lobby_data_message(&self, msg: &CMsgClientMMSLobbyData) {
        let member_names = msg
            .members
            .iter()
            .map(|member| CString::new(member.persona_name.as_str()).unwrap_or_default())
            .collect::<Vec<_>>();
        let members = msg
            .members
            .iter()
            .zip(member_names.iter())
            .map(|(member, name)| LudashiCmLobbyMember {
                steam_id: member.steam_id,
                persona_name: name.as_ptr(),
                metadata_bytes: member.metadata.as_ptr(),
                metadata_len: member.metadata.len(),
            })
            .collect::<Vec<_>>();
        let data = LudashiCmLobbyData {
            steam_id_lobby: msg.steam_id_lobby,
            steam_id_owner: msg.steam_id_owner,
            app_id: msg.app_id,
            max_members: msg.max_members,
            num_members: msg.num_members,
            lobby_type: msg.lobby_type,
            lobby_flags: msg.lobby_flags,
            metadata_bytes: msg.metadata.as_ptr(),
            metadata_len: msg.metadata.len(),
            members: members.as_ptr(),
            member_count: members.len(),
        };
        self.observers.dispatch_lobby_data(&data);
    }
}

impl From<&License> for LudashiCmLicenseEntry {
    fn from(license: &License) -> Self {
        Self {
            package_id: license.package_id,
            owner_id: license.owner_id,
            time_created: license.time_created,
            license_type: license.license_type,
            flags: license.flags,
            change_number: license.change_number,
            minute_limit: license.minute_limit,
            minutes_used: license.minutes_used,
        }
    }
}

pub fn global_bridge() -> &'static CmBridge {
    static BRIDGE: OnceLock<CmBridge> = OnceLock::new();
    BRIDGE.get_or_init(CmBridge::default)
}

pub fn set_active_core(client: Arc<CMClientCore>) {
    global_bridge().set_active(client);
}

pub fn clear_active_core() {
    global_bridge().clear_active();
}

fn state_sync_poller_running() -> &'static AtomicBool {
    static RUNNING: OnceLock<AtomicBool> = OnceLock::new();
    RUNNING.get_or_init(|| AtomicBool::new(false))
}

#[no_mangle]
pub extern "C" fn ludashi_cm_set_persona_state(persona_state: i32) -> bool {
    if persona_state < 0 {
        return false;
    }
    let Some(client) = global_bridge().active() else {
        return false;
    };
    client.enqueue_proto_message(client.build_set_persona_state(persona_state as u32))
}

#[no_mangle]
pub unsafe extern "C" fn ludashi_cm_set_persona_name(name: *const c_char, persona_state: i32) -> bool {
    if name.is_null() || global_bridge().active().is_none() {
        return false;
    }
    let name = unsafe { CStr::from_ptr(name) }
        .to_string_lossy()
        .into_owned();
    if name.is_empty() {
        return false;
    }
    let persona_state = if persona_state < 0 {
        1
    } else {
        persona_state as u32
    };
    let Some(client) = global_bridge().active() else {
        return false;
    };
    client.enqueue_proto_message(client.build_set_persona_name(name, persona_state))
}

#[no_mangle]
pub extern "C" fn ludashi_cm_request_user_info(steam_id: u64, flags: i32) -> bool {
    if steam_id == 0 {
        return false;
    }
    let Some(client) = global_bridge().active() else {
        return false;
    };
    let flags = if flags <= 0 { 0x47 } else { flags as u32 };
    client.enqueue_proto_message(client.build_request_friend_personas(&[steam_id], flags))
}

#[no_mangle]
pub unsafe extern "C" fn ludashi_cm_request_user_info_bulk(
    sids: *const u64,
    count: usize,
    _flags: i32,
) -> bool {
    if sids.is_null() || count == 0 || global_bridge().active().is_none() {
        return false;
    }
    let slice = unsafe { std::slice::from_raw_parts(sids, count) };
    let sids = slice
        .iter()
        .copied()
        .filter(|sid| *sid != 0)
        .collect::<Vec<_>>();
    if sids.is_empty() {
        return false;
    }
    let Some(client) = global_bridge().active() else {
        return false;
    };
    let flags = if _flags <= 0 { 0x47 } else { _flags as u32 };
    client.enqueue_proto_message(client.build_request_friend_personas(&sids, flags))
}

#[no_mangle]
pub unsafe extern "C" fn ludashi_cm_get_cached_app_ownership_ticket(
    app_id: u32,
    out_buf: *mut u8,
    max_len: usize,
    out_len: *mut usize,
) -> bool {
    if out_len.is_null() {
        return false;
    }
    let Some(ticket) = global_bridge().cached_ownership_ticket(app_id) else {
        unsafe {
            *out_len = 0;
        }
        return false;
    };
    unsafe {
        *out_len = ticket.len();
    }
    if out_buf.is_null() || max_len < ticket.len() {
        return false;
    }
    unsafe {
        ptr::copy_nonoverlapping(ticket.as_ptr(), out_buf, ticket.len());
    }
    true
}

#[no_mangle]
pub unsafe extern "C" fn ludashi_cm_bridge_inject_test_ownership_ticket(
    app_id: u32,
    bytes: *const u8,
    len: usize,
) -> bool {
    if bytes.is_null() && len != 0 {
        return false;
    }
    let ticket = if len == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(bytes, len) }
    };
    global_bridge().inject_ownership_ticket(app_id, ticket)
}

#[no_mangle]
pub extern "C" fn ludashi_cm_notify_games_played(app_id: u32) -> bool {
    let Some(client) = global_bridge().active() else {
        return false;
    };
    client.enqueue_proto_message(client.build_notify_games_played(app_id))
}

#[no_mangle]
pub unsafe extern "C" fn ludashi_cm_set_rich_presence(
    app_id: u32,
    keys: *const *const c_char,
    values: *const *const c_char,
    count: usize,
) -> bool {
    let Some(client) = global_bridge().active() else {
        return false;
    };
    if count == 0 {
        return client.enqueue_service_call(client.build_rich_presence_call(app_id, Vec::new(), 0));
    }
    if keys.is_null() {
        return false;
    }
    let key_slice = unsafe { std::slice::from_raw_parts(keys, count) };
    let value_slice = if values.is_null() {
        Vec::new()
    } else {
        unsafe { std::slice::from_raw_parts(values, count) }.to_vec()
    };
    let kv = key_slice
        .iter()
        .enumerate()
        .filter_map(|(idx, key)| {
            if key.is_null() {
                return None;
            }
            let key = unsafe { CStr::from_ptr(*key) }
                .to_string_lossy()
                .into_owned();
            if key.is_empty() {
                return None;
            }
            let value = value_slice
                .get(idx)
                .copied()
                .filter(|ptr| !ptr.is_null())
                .map(|ptr| {
                    unsafe { CStr::from_ptr(ptr) }
                        .to_string_lossy()
                        .into_owned()
                })
                .unwrap_or_default();
            Some((key, value))
        })
        .collect::<Vec<_>>();
    client.enqueue_service_call(client.build_rich_presence_call(app_id, kv, 0))
}

#[no_mangle]
pub unsafe extern "C" fn ludashi_cm_store_user_stats(
    app_id: u32,
    _crc_stats: u32,
    stat_ids: *const u32,
    stat_values: *const u32,
    count: usize,
) -> bool {
    if app_id == 0 {
        return false;
    }
    let Some(client) = global_bridge().active() else {
        return false;
    };
    if client.steam_id() == 0 {
        return false;
    }
    if count != 0 && (stat_ids.is_null() || stat_values.is_null()) {
        return false;
    }
    let stats = if count == 0 {
        Vec::new()
    } else {
        let ids = unsafe { std::slice::from_raw_parts(stat_ids, count) };
        let values = unsafe { std::slice::from_raw_parts(stat_values, count) };
        ids.iter()
            .copied()
            .zip(values.iter().copied())
            .collect::<Vec<_>>()
    };
    client.enqueue_proto_message(client.build_store_user_stats(
        app_id,
        client.steam_id(),
        _crc_stats,
        &stats,
    ))
}

#[no_mangle]
pub extern "C" fn ludashi_cm_bridge_register_persona_observer(callback: LudashiCmPersonaObserverFn) {
    global_bridge().observers.register_persona(callback);
}

#[no_mangle]
pub unsafe extern "C" fn ludashi_cm_bridge_dispatch_persona(event: *const LudashiCmPersonaEvent) {
    if let Some(event) = unsafe { event.as_ref() } {
        global_bridge().observers.dispatch_persona(event);
    }
}

#[no_mangle]
pub extern "C" fn ludashi_cm_bridge_register_logon_state_observer(callback: LudashiCmLogonStateObserverFn) {
    global_bridge().observers.register_logon_state(callback);
}

#[no_mangle]
pub extern "C" fn ludashi_cm_bridge_dispatch_logon_state(logged_on: bool) {
    global_bridge().observers.dispatch_logon_state(logged_on);
}

#[no_mangle]
pub extern "C" fn ludashi_cm_bridge_inject_test_logon_state(logged_on: bool) {
    ludashi_cm_bridge_dispatch_logon_state(logged_on);
}

#[no_mangle]
pub extern "C" fn ludashi_cm_bridge_register_friends_list_observer(callback: LudashiCmFriendsListObserverFn) {
    global_bridge().observers.register_friends_list(callback);
}

#[no_mangle]
pub unsafe extern "C" fn ludashi_cm_bridge_dispatch_friends_list(sids: *const u64, count: usize) {
    if count == 0 {
        global_bridge().observers.dispatch_friends_list(&[]);
        return;
    }
    if sids.is_null() {
        return;
    }
    let friends = unsafe { std::slice::from_raw_parts(sids, count) };
    global_bridge().observers.dispatch_friends_list(friends);
}

#[no_mangle]
pub unsafe extern "C" fn ludashi_cm_bridge_inject_test_friends_list(sids: *const u64, count: usize) {
    unsafe { ludashi_cm_bridge_dispatch_friends_list(sids, count) };
}

#[no_mangle]
pub extern "C" fn ludashi_cm_bridge_register_license_list_observer(callback: LudashiCmLicenseListObserverFn) {
    global_bridge().observers.register_license_list(callback);
}

#[no_mangle]
pub unsafe extern "C" fn ludashi_cm_bridge_dispatch_license_list(
    licenses: *const LudashiCmLicenseEntry,
    count: usize,
) {
    if count == 0 {
        global_bridge().observers.dispatch_license_list(&[]);
        return;
    }
    if licenses.is_null() {
        return;
    }
    let licenses = unsafe { std::slice::from_raw_parts(licenses, count) };
    global_bridge().observers.dispatch_license_list(licenses);
}

#[no_mangle]
pub unsafe extern "C" fn ludashi_cm_bridge_inject_test_license_list(
    licenses: *const LudashiCmLicenseEntry,
    count: usize,
) {
    unsafe { ludashi_cm_bridge_dispatch_license_list(licenses, count) };
}

#[no_mangle]
pub extern "C" fn ludashi_cm_bridge_register_account_info_observer(callback: LudashiCmAccountInfoObserverFn) {
    global_bridge().observers.register_account_info(callback);
}

#[no_mangle]
pub unsafe extern "C" fn ludashi_cm_bridge_dispatch_account_info(info: *const LudashiCmAccountInfo) {
    if let Some(info) = unsafe { info.as_ref() } {
        global_bridge().observers.dispatch_account_info(info);
    }
}

#[no_mangle]
pub unsafe extern "C" fn ludashi_cm_bridge_inject_test_account_info(info: *const LudashiCmAccountInfo) {
    unsafe { ludashi_cm_bridge_dispatch_account_info(info) };
}

#[no_mangle]
pub extern "C" fn ludashi_cm_bridge_register_server_realtime_observer(
    callback: LudashiCmServerRealTimeObserverFn,
) {
    global_bridge().observers.register_server_realtime(callback);
}

#[no_mangle]
pub extern "C" fn ludashi_cm_bridge_dispatch_server_realtime(server_realtime: u32) {
    global_bridge()
        .observers
        .dispatch_server_realtime(server_realtime);
}

#[no_mangle]
pub extern "C" fn ludashi_cm_bridge_register_lobby_data_observer(callback: LudashiCmLobbyDataObserverFn) {
    global_bridge().observers.register_lobby_data(callback);
}

#[no_mangle]
pub extern "C" fn ludashi_cm_bridge_register_lobby_chat_msg_observer(
    callback: LudashiCmLobbyChatMsgObserverFn,
) {
    global_bridge().observers.register_lobby_chat_msg(callback);
}

#[no_mangle]
pub extern "C" fn ludashi_cm_bridge_register_lobby_membership_observer(
    callback: LudashiCmLobbyMembershipObserverFn,
) {
    global_bridge()
        .observers
        .register_lobby_membership(callback);
}

#[no_mangle]
pub extern "C" fn ludashi_cm_bridge_start_state_sync_poller() {
    state_sync_poller_running().store(true, Ordering::Release);
}

#[no_mangle]
pub extern "C" fn ludashi_cm_bridge_stop_state_sync_poller() {
    state_sync_poller_running().store(false, Ordering::Release);
}

#[no_mangle]
pub unsafe extern "C" fn ludashi_cm_lobby_get_list(
    hcall: u64,
    app_id: u32,
    num_lobbies_requested: i32,
    filter_keys: *const *const c_char,
    filter_values: *const *const c_char,
    filter_comparisons: *const i32,
    filter_types: *const i32,
    filter_count: usize,
    callback: LudashiCmLobbyListCb,
) -> bool {
    let Some(callback) = callback else {
        return false;
    };
    if app_id == 0 {
        return false;
    }
    if let Some(client) = global_bridge().active() {
        let mut filters = Vec::new();
        if !filter_keys.is_null() && filter_count != 0 {
            let keys = unsafe { std::slice::from_raw_parts(filter_keys, filter_count) };
            let values = if filter_values.is_null() {
                Vec::new()
            } else {
                unsafe { std::slice::from_raw_parts(filter_values, filter_count) }.to_vec()
            };
            let comparisons = if filter_comparisons.is_null() {
                Vec::new()
            } else {
                unsafe { std::slice::from_raw_parts(filter_comparisons, filter_count) }.to_vec()
            };
            let types = if filter_types.is_null() {
                Vec::new()
            } else {
                unsafe { std::slice::from_raw_parts(filter_types, filter_count) }.to_vec()
            };
            filters = keys
                .iter()
                .enumerate()
                .filter_map(|(idx, key)| {
                    if key.is_null() {
                        return None;
                    }
                    let key = unsafe { CStr::from_ptr(*key) }
                        .to_string_lossy()
                        .into_owned();
                    if key.is_empty() {
                        return None;
                    }
                    let value = values
                        .get(idx)
                        .copied()
                        .filter(|ptr| !ptr.is_null())
                        .map(|ptr| unsafe { CStr::from_ptr(ptr) }.to_string_lossy().into_owned())
                        .unwrap_or_default();
                    Some(crate::pb::cmsg_client_mms_get_lobby_list::CMsgClientMMSGetLobbyListFilter {
                        key,
                        value,
                        comparision: comparisons.get(idx).copied().unwrap_or_default(),
                        filter_type: types.get(idx).copied().unwrap_or_default(),
                    })
                })
                .collect();
        }
        return client.enqueue_proto_message(client.build_lobby_get_list(
            app_id,
            filters,
            num_lobbies_requested,
            hcall,
        ));
    }
    try_lobby_list_from_file(hcall, app_id, callback)
}

#[no_mangle]
pub extern "C" fn ludashi_cm_lobby_create(
    hcall: u64,
    app_id: u32,
    _lobby_type: i32,
    _max_members: i32,
    callback: LudashiCmLobbyCreatedCb,
) -> bool {
    let Some(client) = global_bridge().active() else {
        return false;
    };
    if app_id == 0 {
        return false;
    }
    let ok = client.enqueue_proto_message(client.build_lobby_create(
        app_id,
        _lobby_type,
        _max_members,
        hcall,
    ));
    if !ok {
        if let Some(callback) = callback {
            callback(hcall, -1, 0);
        }
    }
    ok
}

#[no_mangle]
pub extern "C" fn ludashi_cm_lobby_join(
    hcall: u64,
    _app_id: u32,
    lobby_sid: u64,
    callback: LudashiCmLobbyJoinedCb,
) -> bool {
    let Some(client) = global_bridge().active() else {
        return false;
    };
    if lobby_sid == 0 {
        return false;
    }
    let ok = client.enqueue_proto_message(client.build_lobby_join(_app_id, lobby_sid, hcall));
    if !ok {
        if let Some(callback) = callback {
            callback(hcall, -1, lobby_sid);
        }
    }
    ok
}

#[no_mangle]
pub extern "C" fn ludashi_cm_lobby_leave(app_id: u32, lobby_sid: u64) -> bool {
    let Some(client) = global_bridge().active() else {
        return false;
    };
    client.enqueue_proto_message(client.build_lobby_leave(app_id, lobby_sid))
}

#[no_mangle]
pub unsafe extern "C" fn ludashi_cm_lobby_send_chat(
    _app_id: u32,
    lobby_sid: u64,
    data: *const u8,
    len: usize,
) -> bool {
    if lobby_sid == 0 || data.is_null() || len == 0 {
        return false;
    }
    let Some(client) = global_bridge().active() else {
        return false;
    };
    let data = unsafe { std::slice::from_raw_parts(data, len) }.to_vec();
    client.enqueue_proto_message(client.build_lobby_send_chat(_app_id, lobby_sid, data))
}

#[no_mangle]
pub unsafe extern "C" fn ludashi_cm_lobby_set_data(
    hcall: u64,
    _app_id: u32,
    lobby_sid: u64,
    _steam_id_member: u64,
    metadata: *const u8,
    metadata_len: usize,
    _max_members: i32,
    _lobby_type: i32,
    _lobby_flags: i32,
    callback: LudashiCmLobbySetDataCb,
) -> bool {
    if lobby_sid == 0 || (metadata.is_null() && metadata_len != 0) {
        return false;
    }
    let Some(client) = global_bridge().active() else {
        return false;
    };
    let metadata = if metadata_len == 0 {
        Vec::new()
    } else {
        unsafe { std::slice::from_raw_parts(metadata, metadata_len) }.to_vec()
    };
    let ok = client.enqueue_proto_message(client.build_lobby_set_data(
        _app_id,
        lobby_sid,
        _steam_id_member,
        metadata,
        _max_members,
        _lobby_type,
        _lobby_flags,
        hcall,
    ));
    if !ok {
        if let Some(callback) = callback {
            callback(hcall, -1);
        }
    }
    ok
}

#[no_mangle]
pub extern "C" fn ludashi_cm_lobby_set_owner(
    hcall: u64,
    _app_id: u32,
    lobby_sid: u64,
    new_owner_sid: u64,
    callback: LudashiCmLobbySetOwnerCb,
) -> bool {
    let Some(client) = global_bridge().active() else {
        return false;
    };
    if lobby_sid == 0 || new_owner_sid == 0 {
        return false;
    }
    let ok = client.enqueue_proto_message(client.build_lobby_set_owner(
        _app_id,
        lobby_sid,
        new_owner_sid,
        hcall,
    ));
    if !ok {
        if let Some(callback) = callback {
            callback(hcall, -1);
        }
    }
    ok
}

#[no_mangle]
pub extern "C" fn ludashi_cm_lobby_invite_user(app_id: u32, lobby_sid: u64, invitee_sid: u64) -> bool {
    let Some(client) = global_bridge().active() else {
        return false;
    };
    client.enqueue_proto_message(client.build_lobby_invite_user(app_id, lobby_sid, invitee_sid))
}

pub fn state_dir() -> PathBuf {
    env::var_os("LUDASHI_STATE_DIR")
        .filter(|dir| !dir.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("/tmp"))
}

pub fn lobby_state_path(dir: &Path, app_id: u32) -> PathBuf {
    dir.join(format!("ludashi_lobby_{app_id}.txt"))
}

pub fn lobby_request_path(dir: &Path, app_id: u32) -> PathBuf {
    dir.join(format!("ludashi_lobby_req_{app_id}.txt"))
}

pub fn write_lobby_list_to_file(
    dir: &Path,
    app_id: u32,
    eresult: i32,
    entries: &[LudashiCmLobbyEntry],
) -> std::io::Result<()> {
    fs::create_dir_all(dir)?;
    let final_path = lobby_state_path(dir, app_id);
    let tmp_path = final_path.with_extension("txt.tmp");
    {
        let mut file = fs::File::create(&tmp_path)?;
        writeln!(file, "app_id {app_id}")?;
        writeln!(
            file,
            "fetched {}",
            SystemTime::now()
                .duration_since(SystemTime::UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs()
        )?;
        writeln!(file, "eresult {eresult}")?;
        for entry in entries {
            writeln!(file, "lobby {} {}", entry.steam_id, entry.max_members)?;
        }
    }
    fs::rename(tmp_path, final_path)
}

pub fn parse_lobby_state_file(path: &Path) -> std::io::Result<(i32, Vec<LudashiCmLobbyEntry>)> {
    let text = fs::read_to_string(path)?;
    let mut eresult = 0;
    let mut lobbies = Vec::new();
    for line in text.lines() {
        let mut fields = line.split_whitespace();
        match fields.next() {
            Some("eresult") => {
                if let Some(value) = fields.next().and_then(|value| value.parse::<i32>().ok()) {
                    eresult = value;
                }
            }
            Some("lobby") => {
                let steam_id = fields
                    .next()
                    .and_then(|value| value.parse::<u64>().ok())
                    .unwrap_or_default();
                let max_members = fields
                    .next()
                    .and_then(|value| value.parse::<i32>().ok())
                    .unwrap_or_default();
                if steam_id != 0 {
                    lobbies.push(LudashiCmLobbyEntry {
                        steam_id,
                        max_members,
                        ..Default::default()
                    });
                }
            }
            _ => {}
        }
    }
    Ok((eresult, lobbies))
}

pub fn write_lobby_request_file(dir: &Path, app_id: u32) -> std::io::Result<()> {
    fs::create_dir_all(dir)?;
    let final_path = lobby_request_path(dir, app_id);
    let tmp_path = final_path.with_extension("txt.tmp");
    {
        let mut file = fs::File::create(&tmp_path)?;
        writeln!(file, "app_id {app_id}")?;
        writeln!(
            file,
            "requested {}",
            SystemTime::now()
                .duration_since(SystemTime::UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs()
        )?;
    }
    fs::rename(tmp_path, final_path)
}

fn try_lobby_list_from_file(
    hcall: u64,
    app_id: u32,
    callback: extern "C" fn(u64, i32, *const LudashiCmLobbyEntry, usize),
) -> bool {
    let dir = state_dir();
    let path = lobby_state_path(&dir, app_id);

    for attempt in 0..=30 {
        if let Ok((eresult, lobbies)) = parse_lobby_state_file(&path) {
            callback(hcall, eresult, lobbies.as_ptr(), lobbies.len());
            return true;
        }
        if attempt == 0 {
            let _ = write_lobby_request_file(&dir, app_id);
        }
        if attempt < 30 {
            thread::sleep(Duration::from_millis(100));
        }
    }

    callback(hcall, 1, ptr::null(), 0);
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};

    static LOGON_HIT: AtomicBool = AtomicBool::new(false);
    static FRIEND_COUNT: AtomicUsize = AtomicUsize::new(0);
    static LICENSE_PACKAGE: AtomicUsize = AtomicUsize::new(0);
    static PERSONA_ID: AtomicU64 = AtomicU64::new(0);
    static LOBBY_COUNT: AtomicUsize = AtomicUsize::new(0);
    static ACCOUNT_FLAGS: AtomicUsize = AtomicUsize::new(0);
    static SERVER_REALTIME: AtomicUsize = AtomicUsize::new(0);
    static LOBBY_DATA_MEMBERS: AtomicUsize = AtomicUsize::new(0);
    static LOBBY_CHAT_BYTES: AtomicUsize = AtomicUsize::new(0);
    static LOBBY_MEMBERSHIP_JOINED: AtomicUsize = AtomicUsize::new(0);

    extern "C" fn logon_cb(logged_on: bool) {
        LOGON_HIT.store(logged_on, Ordering::SeqCst);
    }

    extern "C" fn friends_cb(_sids: *const u64, count: usize) {
        FRIEND_COUNT.store(count, Ordering::SeqCst);
    }

    extern "C" fn license_cb(licenses: *const LudashiCmLicenseEntry, count: usize) {
        if count != 0 {
            let first = unsafe { *licenses };
            LICENSE_PACKAGE.store(first.package_id as usize, Ordering::SeqCst);
        }
    }

    extern "C" fn persona_cb(event: *const LudashiCmPersonaEvent) {
        let event = unsafe { event.as_ref() }.unwrap();
        PERSONA_ID.store(event.sid, Ordering::SeqCst);
    }

    extern "C" fn account_cb(info: *const LudashiCmAccountInfo) {
        let info = unsafe { info.as_ref() }.unwrap();
        let mut flags = 0usize;
        if !info.persona_name.is_null() && info.persona_name_len == 3 {
            flags |= 1;
        }
        if !info.ip_country.is_null() && info.ip_country_len == 2 {
            flags |= 2;
        }
        if info.two_factor_enabled {
            flags |= 4;
        }
        if info.phone_verified {
            flags |= 8;
        }
        ACCOUNT_FLAGS.store(flags, Ordering::SeqCst);
    }

    extern "C" fn server_realtime_cb(server_realtime: u32) {
        SERVER_REALTIME.store(server_realtime as usize, Ordering::SeqCst);
    }

    extern "C" fn lobby_cb(
        _hcall: u64,
        eresult: i32,
        lobbies: *const LudashiCmLobbyEntry,
        count: usize,
    ) {
        assert_eq!(eresult, 1);
        assert!(!lobbies.is_null());
        LOBBY_COUNT.store(count, Ordering::SeqCst);
    }

    extern "C" fn lobby_data_cb(data: *const LudashiCmLobbyData) {
        let data = unsafe { data.as_ref() }.unwrap();
        LOBBY_DATA_MEMBERS.store(data.member_count, Ordering::SeqCst);
    }

    extern "C" fn lobby_chat_cb(_lobby_sid: u64, _sender_sid: u64, _data: *const u8, len: usize) {
        LOBBY_CHAT_BYTES.store(len, Ordering::SeqCst);
    }

    extern "C" fn lobby_membership_cb(
        joined: i32,
        _lobby_sid: u64,
        _user_sid: u64,
        _persona_name: *const c_char,
    ) {
        LOBBY_MEMBERSHIP_JOINED.store(joined as usize, Ordering::SeqCst);
    }

    fn serialize_lobby_chat_for_test(lobby_sid: u64, sender_sid: u64, message: &[u8]) -> Vec<u8> {
        let mut body = Vec::new();
        let mut writer = crate::proto_wire::Writer::new(&mut body);
        writer.fixed64_field(2, lobby_sid);
        writer.fixed64_field(3, sender_sid);
        writer.bytes_field(4, message);
        body
    }

    fn serialize_lobby_membership_for_test(
        lobby_sid: u64,
        user_sid: u64,
        persona_name: &str,
    ) -> Vec<u8> {
        let mut body = Vec::new();
        let mut writer = crate::proto_wire::Writer::new(&mut body);
        writer.fixed64_field(2, lobby_sid);
        writer.fixed64_field(3, user_sid);
        writer.string_field(4, persona_name);
        body
    }

    #[test]
    fn observer_dispatches_match_c_bridge_contract() {
        let bridge = CmBridge::default();
        bridge.observers().register_logon_state(Some(logon_cb));
        bridge.observers().register_friends_list(Some(friends_cb));
        bridge.observers().register_license_list(Some(license_cb));
        bridge.observers().register_persona(Some(persona_cb));
        bridge.observers().register_account_info(Some(account_cb));
        bridge
            .observers()
            .register_server_realtime(Some(server_realtime_cb));
        bridge.observers().register_lobby_data(Some(lobby_data_cb));
        bridge
            .observers()
            .register_lobby_chat_msg(Some(lobby_chat_cb));
        bridge
            .observers()
            .register_lobby_membership(Some(lobby_membership_cb));

        bridge.observers().dispatch_logon_state(true);
        bridge.observers().dispatch_friends_list(&[1, 2, 3]);
        bridge.observers().dispatch_server_realtime(1_700_000_000);
        bridge
            .observers()
            .dispatch_license_list(&[LudashiCmLicenseEntry {
                package_id: 480,
                ..Default::default()
            }]);
        bridge.dispatch_persona_friend(&PersonaStateFriend {
            friendid: 765,
            player_name: "Ada".into(),
            rich_presence: vec![("status".into(), "Playing".into())],
            ..Default::default()
        });
        bridge.dispatch_account_info_snapshot(&AccountInfoSnapshot {
            persona_name: "Ada".into(),
            ip_country: "US".into(),
            two_factor_enabled: true,
            phone_verified: true,
            ..Default::default()
        });
        bridge.dispatch_lobby_data_message(&CMsgClientMMSLobbyData {
            steam_id_lobby: 1,
            steam_id_owner: 2,
            members: vec![crate::pb::cmsg_client_mms_lobby_data::MMSLobbyDataMember {
                steam_id: 2,
                persona_name: "Ada".into(),
                metadata: b"m".to_vec(),
            }],
            ..Default::default()
        });
        assert!(bridge.dispatch_lobby_push(
            EMsg::CLIENT_MMS_LOBBY_CHAT_MSG,
            &serialize_lobby_chat_for_test(1, 2, b"hello")
        ));
        assert!(bridge.dispatch_lobby_push(
            EMsg::CLIENT_MMS_USER_JOINED_LOBBY,
            &serialize_lobby_membership_for_test(1, 2, "Ada")
        ));

        assert!(LOGON_HIT.load(Ordering::SeqCst));
        assert_eq!(FRIEND_COUNT.load(Ordering::SeqCst), 3);
        assert_eq!(LICENSE_PACKAGE.load(Ordering::SeqCst), 480);
        assert_eq!(PERSONA_ID.load(Ordering::SeqCst), 765);
        assert_eq!(ACCOUNT_FLAGS.load(Ordering::SeqCst), 1 | 2 | 4 | 8);
        assert_eq!(SERVER_REALTIME.load(Ordering::SeqCst), 1_700_000_000);
        assert_eq!(LOBBY_DATA_MEMBERS.load(Ordering::SeqCst), 1);
        assert_eq!(LOBBY_CHAT_BYTES.load(Ordering::SeqCst), 5);
        assert_eq!(LOBBY_MEMBERSHIP_JOINED.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn state_sync_poller_exports_track_running_state() {
        ludashi_cm_bridge_start_state_sync_poller();
        assert!(state_sync_poller_running().load(Ordering::Acquire));
        ludashi_cm_bridge_stop_state_sync_poller();
        assert!(!state_sync_poller_running().load(Ordering::Acquire));
    }

    #[test]
    fn active_core_backs_ticket_cache_bridge() {
        let bridge = CmBridge::default();
        let core = Arc::new(CMClientCore::default());
        bridge.set_active(core);
        assert!(!bridge.inject_ownership_ticket(0, &[1]));
        assert!(!bridge.inject_ownership_ticket(440, &[]));
        assert!(bridge.inject_ownership_ticket(440, &[1, 2, 3]));
        assert_eq!(bridge.cached_ownership_ticket(440), Some(vec![1, 2, 3]));
        bridge.clear_active();
        assert_eq!(bridge.cached_ownership_ticket(440), None);
    }

    #[test]
    fn bridge_commands_enqueue_outbound_cm_messages() {
        let bridge = global_bridge();
        let core = Arc::new(CMClientCore::default());
        core.set_state(crate::cm_client::ClientState::LoggedOn);
        bridge.set_active(Arc::clone(&core));

        assert!(ludashi_cm_set_persona_state(1));
        assert!(ludashi_cm_notify_games_played(480));

        let name = CString::new("Ada").unwrap();
        assert!(unsafe { ludashi_cm_set_persona_name(name.as_ptr(), 1) });
        assert!(unsafe {
            ludashi_cm_lobby_get_list(
                10,
                480,
                50,
                ptr::null(),
                ptr::null(),
                ptr::null(),
                ptr::null(),
                0,
                Some(lobby_cb),
            )
        });
        assert!(ludashi_cm_lobby_create(11, 480, 2, 4, None));
        assert!(ludashi_cm_lobby_leave(480, 123));

        let wires = core.take_outbound_wires();
        assert_eq!(wires.len(), 6);
        bridge.clear_active();
    }

    #[test]
    fn lobby_state_files_roundtrip_cpp_fallback_format() {
        let dir = env::temp_dir().join(format!(
            "ludashisteam-rust-bridge-{}",
            SystemTime::now()
                .duration_since(SystemTime::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let entries = [LudashiCmLobbyEntry {
            steam_id: 123,
            max_members: 8,
            ..Default::default()
        }];

        write_lobby_list_to_file(&dir, 480, 1, &entries).unwrap();
        let (eresult, parsed) = parse_lobby_state_file(&lobby_state_path(&dir, 480)).unwrap();
        assert_eq!(eresult, 1);
        assert_eq!(parsed[0].steam_id, 123);
        assert_eq!(parsed[0].max_members, 8);

        write_lobby_request_file(&dir, 480).unwrap();
        assert!(lobby_request_path(&dir, 480).exists());
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn lobby_get_list_callback_reads_existing_state_file() {
        let dir = env::temp_dir().join(format!(
            "ludashisteam-rust-bridge-cb-{}",
            SystemTime::now()
                .duration_since(SystemTime::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        write_lobby_list_to_file(
            &dir,
            481,
            1,
            &[LudashiCmLobbyEntry {
                steam_id: 456,
                max_members: 4,
                ..Default::default()
            }],
        )
        .unwrap();

        let old = env::var_os("LUDASHI_STATE_DIR");
        env::set_var("LUDASHI_STATE_DIR", &dir);
        LOBBY_COUNT.store(0, Ordering::SeqCst);
        assert!(unsafe {
            ludashi_cm_lobby_get_list(
                99,
                481,
                0,
                ptr::null(),
                ptr::null(),
                ptr::null(),
                ptr::null(),
                0,
                Some(lobby_cb),
            )
        });
        assert_eq!(LOBBY_COUNT.load(Ordering::SeqCst), 1);
        if let Some(old) = old {
            env::set_var("LUDASHI_STATE_DIR", old);
        } else {
            env::remove_var("LUDASHI_STATE_DIR");
        }
        let _ = fs::remove_dir_all(dir);
    }
}
