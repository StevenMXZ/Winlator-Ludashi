use crate::auth_session::{
    apply_account_name_fallback, auth_result_from_poll, build_credentials_begin_request,
    build_guard_code_request, build_password_rsa_request, build_poll_request,
    build_qr_begin_request, choose_guard_confirmation,
    pending_credentials_from_begin_response, pending_qr_from_begin_response, sleep_slices,
    take_qr_remote_interaction, AuthSessionResult, CredentialsAuthConfig, QrAuthConfig,
};
use crate::cm_bridge;
use crate::cm_client::{CMClientCore, ClientState, OutboundProtoMessage, OutboundServiceCall};
use crate::cm_runtime::CMClientRuntime;
use crate::emsg::EMsg;
use crate::encrypted_channel::{ChannelDisconnectReason, EncryptedChannel};
use crate::version;
use crate::wine_bridge::{WineBridge, WineBridgeConfig};
use crate::ws_connection::WsConnection;
use jni::objects::{
    GlobalRef, JByteArray, JClass, JIntArray, JLongArray, JObject, JString, JValue,
};
use jni::sys::{
    jboolean, jbyteArray, jint, jlong, jlongArray, jstring, JNI_FALSE, JNI_TRUE, JNI_VERSION_1_6,
};
use jni::{JNIEnv, JavaVM};
use serde_json::json;
#[cfg(target_os = "android")]
use std::ffi::CString;
use std::ptr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use std::sync::OnceLock;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

#[cfg(target_os = "android")]
#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(prio: i32, tag: *const i8, text: *const i8) -> i32;
}

fn android_log(tag: &str, message: &str) {
    #[cfg(target_os = "android")]
    {
        let Ok(tag) = CString::new(tag) else {
            return;
        };
        let sanitized = message.replace('\0', " ");
        let Ok(text) = CString::new(sanitized) else {
            return;
        };
        unsafe {
            let _ = __android_log_write(4, tag.as_ptr().cast(), text.as_ptr().cast());
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (tag, message);
    }
}

/// Steam Guard code re-prompts before the credentials sign-in gives up.
const GUARD_CODE_MAX_ATTEMPTS: u32 = 5;

fn qr_log(message: &str) {
    android_log("LUDASHI_STEAM_QR", message);
}

struct LudashiConnectionHandle {
    channel: EncryptedChannel,
    observer: Mutex<Option<GlobalRef>>,
}

struct LudashiSteamSessionHandle {
    core: Arc<CMClientCore>,
    ca_bundle_path: String,
    auto_populate_library: bool,
    wine_bridge: WineBridge,
    runtime: Mutex<Option<Arc<CMClientRuntime>>>,
    login_cancel: Mutex<Option<Arc<AtomicBool>>>,
    download_cancel: Arc<AtomicBool>,
    state_observer: Arc<Mutex<Option<GlobalRef>>>,
    library_observer: Arc<Mutex<Option<GlobalRef>>>,
    library_observer_installed: Mutex<bool>,
    /// Steam cell id sent with ContentServerDirectory.GetServersForSteamPipe (0 = let Steam pick
    /// by the connection's location) and the datacenter code whose CDN hosts are preferred
    /// (empty = directory order). Set from Kotlin (`nativeSetCdnPreference`).
    cdn_preference: Mutex<(u32, String)>,
    /// Announce Online at logon (see `CMClientRuntime::set_auto_persona_online`). Applied to the
    /// runtime when it is created and live-updated by `nativeSetAutoPersonaOnline`.
    auto_persona_online: AtomicBool,
}

impl LudashiConnectionHandle {
    fn new() -> Self {
        Self {
            channel: EncryptedChannel::new(Box::new(WsConnection::new())),
            observer: Mutex::new(None),
        }
    }
}

impl LudashiSteamSessionHandle {
    fn new() -> Self {
        let core = Arc::new(CMClientCore::default());
        cm_bridge::set_active_core(Arc::clone(&core));
        Self {
            core,
            ca_bundle_path: String::new(),
            auto_populate_library: true,
            wine_bridge: WineBridge::default(),
            runtime: Mutex::new(None),
            login_cancel: Mutex::new(None),
            download_cancel: Arc::new(AtomicBool::new(false)),
            state_observer: Arc::new(Mutex::new(None)),
            library_observer: Arc::new(Mutex::new(None)),
            library_observer_installed: Mutex::new(false),
            cdn_preference: Mutex::new((0, String::new())),
            auto_persona_online: AtomicBool::new(true),
        }
    }

    fn cdn_preference(&self) -> (u32, String) {
        self.cdn_preference
            .lock()
            .map(|guard| guard.clone())
            .unwrap_or((0, String::new()))
    }

    fn enqueue_proto(&self, message: Option<OutboundProtoMessage>) -> bool {
        let Some(message) = message else {
            return false;
        };
        self.enqueue_wire(message.wire)
    }

    fn enqueue_wire(&self, wire: Vec<u8>) -> bool {
        if wire.is_empty() {
            return false;
        }
        if !self.core.enqueue_wire(wire) {
            return false;
        }
        if let Some(runtime) = self
            .runtime
            .lock()
            .expect("session runtime poisoned")
            .as_ref()
        {
            runtime.flush_outbound();
        }
        true
    }

    fn runtime(&self) -> Arc<CMClientRuntime> {
        let mut slot = self.runtime.lock().expect("session runtime poisoned");
        if let Some(runtime) = slot.as_ref() {
            return Arc::clone(runtime);
        }
        let runtime = CMClientRuntime::new(Arc::clone(&self.core), Box::new(WsConnection::new()));
        if !self.ca_bundle_path.is_empty() {
            runtime.set_ca_bundle_path(&self.ca_bundle_path);
        }
        runtime.set_auto_persona_online(self.auto_persona_online.load(Ordering::Relaxed));
        let state_observer = Arc::clone(&self.state_observer);
        runtime.set_on_state(move |state| {
            dispatch_state_observer(&state_observer, state);
        });
        let client_message_observer = Arc::clone(&self.state_observer);
        runtime.set_on_client_message(move |emsg, header, body| {
            dispatch_client_message_observer(&client_message_observer, emsg, header.eresult, body);
        });
        self.install_library_observer();
        *slot = Some(Arc::clone(&runtime));
        runtime
    }

    fn install_library_observer(&self) {
        let mut installed = self
            .library_observer_installed
            .lock()
            .expect("library observer install flag poisoned");
        if *installed {
            return;
        }
        let observer = Arc::clone(&self.library_observer);
        self.core.library().set_observer(move || {
            dispatch_library_observer(&observer);
        });
        *installed = true;
    }

    fn connected_runtime(&self) -> Option<Arc<CMClientRuntime>> {
        let runtime = self
            .runtime
            .lock()
            .expect("session runtime poisoned")
            .as_ref()
            .cloned()?;
        matches!(
            self.core.state(),
            ClientState::Connected | ClientState::LoggedOn
        )
        .then_some(runtime)
    }

    fn begin_login_cancel(&self) -> Arc<AtomicBool> {
        let cancel = Arc::new(AtomicBool::new(false));
        let mut slot = self.login_cancel.lock().expect("session login poisoned");
        if let Some(previous) = slot.replace(Arc::clone(&cancel)) {
            previous.store(true, Ordering::Relaxed);
        }
        cancel
    }

    fn cancel_login(&self) {
        if let Some(cancel) = self
            .login_cancel
            .lock()
            .expect("session login poisoned")
            .take()
        {
            cancel.store(true, Ordering::Relaxed);
        }
    }
}

fn to_handle(handle: Box<LudashiConnectionHandle>) -> jlong {
    Box::into_raw(handle) as isize as jlong
}

fn to_session_handle(handle: Box<LudashiSteamSessionHandle>) -> jlong {
    Box::into_raw(handle) as isize as jlong
}

unsafe fn from_handle_mut(handle: jlong) -> Option<&'static mut LudashiConnectionHandle> {
    if handle == 0 {
        return None;
    }
    unsafe { (handle as *mut LudashiConnectionHandle).as_mut() }
}

unsafe fn from_session_handle_mut(handle: jlong) -> Option<&'static mut LudashiSteamSessionHandle> {
    if handle == 0 {
        return None;
    }
    unsafe { (handle as *mut LudashiSteamSessionHandle).as_mut() }
}

unsafe fn drop_handle(handle: jlong) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut LudashiConnectionHandle));
        }
    }
}

unsafe fn drop_session_handle(handle: jlong) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut LudashiSteamSessionHandle));
        }
        cm_bridge::clear_active_core();
    }
}

static JVM: OnceLock<JavaVM> = OnceLock::new();
static AUTH_RESULT_CLASS: OnceLock<GlobalRef> = OnceLock::new();

fn ensure_auth_result_class(env: &mut JNIEnv) -> Option<&'static GlobalRef> {
    if let Some(class) = AUTH_RESULT_CLASS.get() {
        return Some(class);
    }
    let Ok(class) = env.find_class("com/winlator/cmod/feature/stores/steam/ludashisteam/LudashiAuthResult")
    else {
        clear_pending_exception(env);
        return None;
    };
    let Ok(class) = env.new_global_ref(class) else {
        clear_pending_exception(env);
        return None;
    };
    let _ = AUTH_RESULT_CLASS.set(class);
    AUTH_RESULT_CLASS.get()
}

fn auth_result_jclass<'a>(class: &'a GlobalRef) -> JClass<'a> {
    unsafe { JClass::from_raw(class.as_obj().as_raw() as _) }
}

fn new_string_or_null(env: &mut JNIEnv, value: &str) -> jstring {
    env.new_string(value)
        .map(|s| s.into_raw())
        .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    let _ = JVM.set(vm);
    JNI_VERSION_1_6
}

fn dispatch_connection_connected(observer: &GlobalRef) {
    let Some(vm) = JVM.get() else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
        return;
    };
    let _ = env.call_method(observer.as_obj(), "onConnected", "()V", &[]);
    clear_pending_exception(&mut env);
}

fn dispatch_connection_disconnected(
    observer: &GlobalRef,
    reason: ChannelDisconnectReason,
    detail: &str,
) {
    let Some(vm) = JVM.get() else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
        return;
    };
    let Ok(detail) = env.new_string(detail) else {
        clear_pending_exception(&mut env);
        return;
    };
    let detail_obj = JObject::from(detail);
    let _ = env.call_method(
        observer.as_obj(),
        "onDisconnected",
        "(ILjava/lang/String;)V",
        &[JValue::Int(reason as jint), JValue::Object(&detail_obj)],
    );
    clear_pending_exception(&mut env);
}

fn dispatch_state_observer(observer: &Arc<Mutex<Option<GlobalRef>>>, state: ClientState) {
    let cloned = observer
        .lock()
        .expect("session state observer poisoned")
        .as_ref()
        .cloned();
    let Some(observer) = cloned else {
        return;
    };
    let Some(vm) = JVM.get() else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
        return;
    };
    let _ = env.call_method(
        observer.as_obj(),
        "onStateChanged",
        "(I)V",
        &[JValue::Int(state as jint)],
    );
    clear_pending_exception(&mut env);
}

fn dispatch_client_message_observer(
    observer: &Arc<Mutex<Option<GlobalRef>>>,
    emsg: EMsg,
    eresult: i32,
    body: &[u8],
) {
    let cloned = observer
        .lock()
        .expect("session state observer poisoned")
        .as_ref()
        .cloned();
    let Some(observer) = cloned else {
        return;
    };
    let Some(vm) = JVM.get() else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
        return;
    };
    let Ok(array) = env.byte_array_from_slice(body) else {
        clear_pending_exception(&mut env);
        return;
    };
    let array_obj = JObject::from(array);
    let _ = env.call_method(
        observer.as_obj(),
        "onClientMessage",
        "(II[B)V",
        &[
            JValue::Int(emsg.0 as jint),
            JValue::Int(eresult),
            JValue::Object(&array_obj),
        ],
    );
    clear_pending_exception(&mut env);
}

fn dispatch_library_observer(observer: &Arc<Mutex<Option<GlobalRef>>>) {
    let cloned = observer
        .lock()
        .expect("session library observer poisoned")
        .as_ref()
        .cloned();
    let Some(observer) = cloned else {
        return;
    };
    let Some(vm) = JVM.get() else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
        return;
    };
    let _ = env.call_method(observer.as_obj(), "onLibraryChanged", "()V", &[]);
    clear_pending_exception(&mut env);
}

fn dispatch_connection_message(observer: &GlobalRef, bytes: &[u8]) {
    let Some(vm) = JVM.get() else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
        return;
    };
    let Ok(array) = env.byte_array_from_slice(bytes) else {
        clear_pending_exception(&mut env);
        return;
    };
    let array_obj = JObject::from(array);
    let _ = env.call_method(
        observer.as_obj(),
        "onMessage",
        "([B)V",
        &[JValue::Object(&array_obj)],
    );
    clear_pending_exception(&mut env);
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamClient_nativeVersion(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    new_string_or_null(&mut env, version::version().string)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiConnection_nativeCreate(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    to_handle(Box::new(LudashiConnectionHandle::new()))
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiConnection_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    unsafe { drop_handle(handle) };
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiConnection_nativeSetCaBundlePath(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    path: JString,
) {
    let Some(handle) = (unsafe { from_handle_mut(handle) }) else {
        return;
    };
    let Ok(path) = env.get_string(&path) else {
        return;
    };
    handle.channel.set_ca_bundle_path(&path.to_string_lossy());
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiConnection_nativeSetObserver(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    observer: JObject,
) {
    let Some(handle) = (unsafe { from_handle_mut(handle) }) else {
        return;
    };
    let observer = if observer.is_null() {
        None
    } else {
        env.new_global_ref(observer).ok()
    };
    *handle
        .observer
        .lock()
        .expect("connection observer poisoned") = observer.clone();

    let Some(observer) = observer else {
        handle.channel.set_on_connected(|| {});
        handle.channel.set_on_disconnected(|_, _| {});
        handle.channel.set_on_message(|_| {});
        return;
    };

    let connected_observer = observer.clone();
    handle.channel.set_on_connected(move || {
        dispatch_connection_connected(&connected_observer);
    });
    let disconnected_observer = observer.clone();
    handle.channel.set_on_disconnected(move |reason, detail| {
        dispatch_connection_disconnected(&disconnected_observer, reason, detail);
    });
    handle.channel.set_on_message(move |bytes| {
        dispatch_connection_message(&observer, bytes);
    });
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiConnection_nativeConnect(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    url: JString,
) -> jboolean {
    let Some(handle) = (unsafe { from_handle_mut(handle) }) else {
        return JNI_FALSE;
    };
    let Ok(url) = env.get_string(&url) else {
        return JNI_FALSE;
    };
    if handle.channel.connect(&url.to_string_lossy()) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiConnection_nativeDisconnect(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(handle) = unsafe { from_handle_mut(handle) } {
        handle.channel.disconnect();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiConnection_nativeSend(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
) -> jboolean {
    let Some(handle) = (unsafe { from_handle_mut(handle) }) else {
        return JNI_FALSE;
    };
    let Ok(bytes) = env.convert_byte_array(data) else {
        return JNI_FALSE;
    };
    if handle.channel.send(&bytes) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

pub fn empty_byte_array(env: JNIEnv) -> jbyteArray {
    env.new_byte_array(0)
        .map(|arr| arr.into_raw())
        .unwrap_or(ptr::null_mut())
}

fn byte_array_or_null(env: &JNIEnv, bytes: &[u8]) -> jbyteArray {
    env.byte_array_from_slice(bytes)
        .map(|arr| arr.into_raw())
        .unwrap_or(ptr::null_mut())
}

fn jstring_to_string(env: &mut JNIEnv, value: &JString) -> Option<String> {
    env.get_string(value)
        .ok()
        .map(|s| s.to_string_lossy().into_owned())
}

fn int_array_to_u32_vec(env: &JNIEnv, array: &JIntArray) -> Vec<u32> {
    let len = env.get_array_length(array).unwrap_or(0);
    if len <= 0 {
        return Vec::new();
    }
    let mut values = vec![0i32; len as usize];
    if env.get_int_array_region(array, 0, &mut values).is_err() {
        return Vec::new();
    }
    values.into_iter().map(|value| value as u32).collect()
}

fn long_array_to_u64_vec(env: &JNIEnv, array: &JLongArray) -> Vec<u64> {
    let len = env.get_array_length(array).unwrap_or(0);
    if len <= 0 {
        return Vec::new();
    }
    let mut values = vec![0i64; len as usize];
    if env.get_long_array_region(array, 0, &mut values).is_err() {
        return Vec::new();
    }
    values.into_iter().map(|value| value as u64).collect()
}

// One value per line (0 for blank/garbage), so callers that pair these lists
// with another by index never have a line silently dropped and shifted.
fn parse_u32_lines(value: &str) -> Vec<u32> {
    value
        .lines()
        .map(|line| parse_u32_token(line).unwrap_or(0))
        .collect()
}

fn parse_u64_lines(value: &str) -> Vec<u64> {
    value
        .lines()
        .map(|line| parse_u64_token(line).unwrap_or(0))
        .collect()
}

// Kotlin renders u64/u32 ids+tokens from signed Long/Int, so high-bit values
// arrive negative (e.g. "-2956503589389641226"). Parse signed-or-unsigned and
// wrap (two's complement) like the C++ stoull this replaced; a strict
// parse::<uN>() rejects the minus and would lose the token.
fn parse_u32_token(line: &str) -> Option<u32> {
    let token = line.trim();
    if token.is_empty() {
        return None;
    }
    token
        .parse::<u32>()
        .ok()
        .or_else(|| token.parse::<i32>().ok().map(|v| v as u32))
}

fn parse_u64_token(line: &str) -> Option<u64> {
    let token = line.trim();
    if token.is_empty() {
        return None;
    }
    token
        .parse::<u64>()
        .ok()
        .or_else(|| token.parse::<i64>().ok().map(|v| v as u64))
}

fn split_nonempty_lines(value: &str) -> Vec<String> {
    value
        .lines()
        .filter(|line| !line.is_empty())
        .map(ToOwned::to_owned)
        .collect()
}

fn kvnode_to_json_value(node: &crate::vdf::KVNode) -> serde_json::Value {
    if node.is_object() {
        let mut object = serde_json::Map::new();
        for child in &node.children {
            object.insert(child.name.clone(), kvnode_to_json_value(child));
        }
        serde_json::Value::Object(object)
    } else {
        json!(node.as_string(""))
    }
}

fn decode_hex(value: &str) -> Option<Vec<u8>> {
    let compact = value.trim();
    if !compact.len().is_multiple_of(2) {
        return None;
    }
    let mut out = Vec::with_capacity(compact.len() / 2);
    let bytes = compact.as_bytes();
    for pair in bytes.chunks_exact(2) {
        let hi = (pair[0] as char).to_digit(16)?;
        let lo = (pair[1] as char).to_digit(16)?;
        out.push(((hi << 4) | lo) as u8);
    }
    Some(out)
}

fn clear_pending_exception(env: &mut JNIEnv) {
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
    }
}

fn call_auth_result_failure(env: &mut JNIEnv, callback: &JObject, code: jint, message: &str) {
    if callback.is_null() {
        return;
    }
    let Some(auth_result_class) = ensure_auth_result_class(env) else {
        return;
    };
    let Ok(error) = env.new_string(message) else {
        clear_pending_exception(env);
        return;
    };
    let Ok(empty) = env.new_string("") else {
        clear_pending_exception(env);
        return;
    };
    let error_obj = JObject::from(error);
    let empty_obj = JObject::from(empty);
    let auth_result_class = auth_result_jclass(auth_result_class);
    let Ok(result) = env.new_object(
        auth_result_class,
        "(ZILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JZLjava/lang/String;)V",
        &[
            JValue::Bool(JNI_FALSE),
            JValue::Int(code),
            JValue::Object(&error_obj),
            JValue::Object(&empty_obj),
            JValue::Object(&empty_obj),
            JValue::Object(&empty_obj),
            JValue::Object(&empty_obj),
            JValue::Long(0),
            JValue::Bool(JNI_FALSE),
            JValue::Object(&empty_obj),
        ],
    ) else {
        clear_pending_exception(env);
        return;
    };
    let _ = env.call_method(
        callback,
        "onAuthResult",
        "(Lcom/winlator/cmod/feature/stores/steam/ludashisteam/LudashiAuthResult;)V",
        &[JValue::Object(&result)],
    );
    clear_pending_exception(env);
}

fn dispatch_auth_result(callback: &GlobalRef, result: AuthSessionResult) {
    let Some(vm) = JVM.get() else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
        return;
    };
    call_auth_result(&mut env, callback.as_obj(), &result);
}

fn call_auth_result(env: &mut JNIEnv, callback: &JObject, result: &AuthSessionResult) {
    if callback.is_null() {
        return;
    }
    let Some(auth_result_class) = AUTH_RESULT_CLASS.get() else {
        qr_log("auth result class missing; dropping auth callback");
        return;
    };
    let Ok(error_message) = env.new_string(&result.error_message) else {
        clear_pending_exception(env);
        return;
    };
    let Ok(account_name) = env.new_string(&result.account_name) else {
        clear_pending_exception(env);
        return;
    };
    let Ok(refresh_token) = env.new_string(&result.refresh_token) else {
        clear_pending_exception(env);
        return;
    };
    let Ok(access_token) = env.new_string(&result.access_token) else {
        clear_pending_exception(env);
        return;
    };
    let Ok(new_guard_data) = env.new_string(&result.new_guard_data) else {
        clear_pending_exception(env);
        return;
    };
    let Ok(agreement_session_url) = env.new_string(&result.agreement_session_url) else {
        clear_pending_exception(env);
        return;
    };
    let error_message = JObject::from(error_message);
    let account_name = JObject::from(account_name);
    let refresh_token = JObject::from(refresh_token);
    let access_token = JObject::from(access_token);
    let new_guard_data = JObject::from(new_guard_data);
    let agreement_session_url = JObject::from(agreement_session_url);
    let auth_result_class = auth_result_jclass(auth_result_class);
    let Ok(auth_result) = env.new_object(
        auth_result_class,
        "(ZILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JZLjava/lang/String;)V",
        &[
            JValue::Bool(if result.success { JNI_TRUE } else { JNI_FALSE }),
            JValue::Int(result.eresult),
            JValue::Object(&error_message),
            JValue::Object(&account_name),
            JValue::Object(&refresh_token),
            JValue::Object(&access_token),
            JValue::Object(&new_guard_data),
            JValue::Long(result.steamid as jlong),
            JValue::Bool(if result.had_remote_interaction {
                JNI_TRUE
            } else {
                JNI_FALSE
            }),
            JValue::Object(&agreement_session_url),
        ],
    ) else {
        clear_pending_exception(env);
        return;
    };
    let _ = env.call_method(
        callback,
        "onAuthResult",
        "(Lcom/winlator/cmod/feature/stores/steam/ludashisteam/LudashiAuthResult;)V",
        &[JValue::Object(&auth_result)],
    );
    clear_pending_exception(env);
}

fn dispatch_qr_challenge(callback: &GlobalRef, url: &str) {
    let Some(vm) = JVM.get() else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
        return;
    };
    let Ok(url) = env.new_string(url) else {
        clear_pending_exception(&mut env);
        return;
    };
    let url = JObject::from(url);
    let _ = env.call_method(
        callback.as_obj(),
        "onQrChallengeUrl",
        "(Ljava/lang/String;)V",
        &[JValue::Object(&url)],
    );
    clear_pending_exception(&mut env);
}

fn authenticator_accept_device_confirmation(authenticator: &GlobalRef) -> bool {
    let Some(vm) = JVM.get() else {
        return false;
    };
    let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
        return false;
    };
    let Ok(future) = env.call_method(
        authenticator.as_obj(),
        "acceptDeviceConfirmation",
        "()Ljava/util/concurrent/CompletableFuture;",
        &[],
    ) else {
        clear_pending_exception(&mut env);
        return false;
    };
    let Ok(future) = future.l() else {
        clear_pending_exception(&mut env);
        return false;
    };
    if future.is_null() {
        return false;
    }
    let Ok(result) = env.call_method(&future, "get", "()Ljava/lang/Object;", &[]) else {
        clear_pending_exception(&mut env);
        return false;
    };
    let Ok(result) = result.l() else {
        clear_pending_exception(&mut env);
        return false;
    };
    if result.is_null() {
        return false;
    }
    let Ok(value) = env.call_method(&result, "booleanValue", "()Z", &[]) else {
        clear_pending_exception(&mut env);
        return false;
    };
    value.z().unwrap_or(false)
}

fn future_string_from_method(
    env: &mut JNIEnv,
    target: &JObject,
    method: &str,
    signature: &str,
    args: &[JValue],
) -> Option<String> {
    let future = env
        .call_method(target, method, signature, args)
        .ok()?
        .l()
        .ok()?;
    if future.is_null() {
        return None;
    }
    let result = env
        .call_method(&future, "get", "()Ljava/lang/Object;", &[])
        .ok()?
        .l()
        .ok()?;
    if result.is_null() {
        return None;
    }
    let result = JString::from(result);
    jstring_to_string(env, &result)
}

fn authenticator_get_device_code(
    authenticator: &GlobalRef,
    previous_code_was_incorrect: bool,
) -> Option<String> {
    let vm = JVM.get()?;
    let mut env = vm.attach_current_thread_as_daemon().ok()?;
    let value = future_string_from_method(
        &mut env,
        authenticator.as_obj(),
        "getDeviceCode",
        "(Z)Ljava/util/concurrent/CompletableFuture;",
        &[JValue::Bool(if previous_code_was_incorrect {
            JNI_TRUE
        } else {
            JNI_FALSE
        })],
    );
    clear_pending_exception(&mut env);
    value
}

fn authenticator_get_email_code(
    authenticator: &GlobalRef,
    email: &str,
    previous_code_was_incorrect: bool,
) -> Option<String> {
    let vm = JVM.get()?;
    let mut env = vm.attach_current_thread_as_daemon().ok()?;
    let email = env.new_string(email).ok()?;
    let email_obj = JObject::from(email);
    let value = future_string_from_method(
        &mut env,
        authenticator.as_obj(),
        "getEmailCode",
        "(Ljava/lang/String;Z)Ljava/util/concurrent/CompletableFuture;",
        &[
            JValue::Object(&email_obj),
            JValue::Bool(if previous_code_was_incorrect {
                JNI_TRUE
            } else {
                JNI_FALSE
            }),
        ],
    );
    clear_pending_exception(&mut env);
    value
}

fn request_authed_service_body<F>(
    runtime: &Arc<CMClientRuntime>,
    timeout: Duration,
    build: F,
) -> Option<Vec<u8>>
where
    F: FnOnce(&CMClientCore, u64) -> Option<OutboundServiceCall>,
{
    if runtime.core().state() != ClientState::LoggedOn {
        return None;
    }
    let job_id = runtime.next_job_id();
    let call = build(runtime.core(), job_id)?;
    let (tx, rx) = mpsc::channel();
    runtime.track_job(
        job_id,
        move |job| {
            let body = (!job.synthetic_failure && job.eresult == 1).then_some(job.body);
            let _ = tx.send(body);
        },
        Some(timeout),
    );
    if !runtime.core().enqueue_wire(call.wire) {
        return None;
    }
    runtime.flush_outbound();
    rx.recv_timeout(timeout).ok().flatten()
}

fn request_authed_service_success<F>(
    runtime: &Arc<CMClientRuntime>,
    timeout: Duration,
    build: F,
) -> bool
where
    F: FnOnce(&CMClientCore, u64) -> Option<OutboundServiceCall>,
{
    if runtime.core().state() != ClientState::LoggedOn {
        return false;
    }
    let job_id = runtime.next_job_id();
    let Some(call) = build(runtime.core(), job_id) else {
        return false;
    };
    let (tx, rx) = mpsc::channel();
    runtime.track_job(
        job_id,
        move |job| {
            let _ = tx.send(!job.synthetic_failure && job.eresult == 1);
        },
        Some(timeout),
    );
    if !runtime.core().enqueue_wire(call.wire) {
        return false;
    }
    runtime.flush_outbound();
    rx.recv_timeout(timeout).unwrap_or(false)
}

fn request_service_method_job(
    runtime: &Arc<CMClientRuntime>,
    method: &str,
    authed: bool,
    body: Vec<u8>,
    timeout: Duration,
    cancel: Option<&AtomicBool>,
) -> Option<crate::job_manager::JobResult> {
    let job_id = runtime.next_job_id();
    let (tx, rx) = mpsc::channel();
    runtime.track_job(
        job_id,
        move |job| {
            let _ = tx.send(job);
        },
        Some(timeout),
    );
    let wire = runtime
        .core()
        .build_service_method_call(method, authed, job_id, &body);
    if !runtime.core().enqueue_wire(wire) {
        return None;
    }
    runtime.flush_outbound();
    recv_with_cancel(&rx, timeout, cancel)
}

fn recv_with_cancel<T>(
    rx: &mpsc::Receiver<T>,
    timeout: Duration,
    cancel: Option<&AtomicBool>,
) -> Option<T> {
    let tick = Duration::from_millis(100);
    let mut remaining = timeout;
    loop {
        if cancel.is_some_and(|c| c.load(Ordering::Relaxed)) {
            return None;
        }
        let wait = remaining.min(tick);
        match rx.recv_timeout(wait) {
            Ok(value) => return Some(value),
            Err(mpsc::RecvTimeoutError::Timeout) => {
                if remaining <= wait {
                    return None;
                }
                remaining -= wait;
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => return None,
        }
    }
}

fn auth_result_from_job(job: &crate::job_manager::JobResult, fallback_label: &str) -> AuthSessionResult {
    let message = if !job.error_message.is_empty() {
        format!("{fallback_label}: {}", job.error_message)
    } else if job.synthetic_failure {
        format!("{fallback_label} timed out")
    } else {
        format!("{fallback_label} failed (eresult={})", job.eresult)
    };
    AuthSessionResult {
        eresult: job.eresult,
        error_message: message,
        ..Default::default()
    }
}

fn request_user_stats_response(
    runtime: &Arc<CMClientRuntime>,
    app_id: u32,
    timeout: Duration,
) -> Option<crate::pb::cmsg_client_get_user_stats::CMsgClientGetUserStatsResponse> {
    if app_id == 0 || runtime.core().state() != ClientState::LoggedOn {
        return None;
    }
    let job_id = runtime.next_job_id();
    let message = runtime.core().build_job_proto_message(
        EMsg::CLIENT_GET_USER_STATS,
        job_id,
        crate::pb::cmsg_client_get_user_stats::CMsgClientGetUserStats {
            game_id: app_id as u64,
            steam_id_for_user: runtime.core().steam_id(),
        }
        .serialize(),
        0,
    )?;
    let (tx, rx) = mpsc::channel();
    runtime.track_job(
        job_id,
        move |job| {
            let response = if job.synthetic_failure {
                None
            } else {
                crate::pb::cmsg_client_get_user_stats::CMsgClientGetUserStatsResponse::deserialize(
                    &job.body,
                )
            };
            let _ = tx.send(response);
        },
        Some(timeout),
    );
    if !runtime.core().enqueue_wire(message.wire) {
        return None;
    }
    runtime.flush_outbound();
    rx.recv_timeout(timeout).ok().flatten()
}

fn request_proto_body<F>(
    runtime: &Arc<CMClientRuntime>,
    timeout: Duration,
    build: F,
) -> Option<Vec<u8>>
where
    F: FnOnce(&CMClientCore, u64) -> Option<OutboundProtoMessage>,
{
    if runtime.core().state() != ClientState::LoggedOn {
        return None;
    }
    let job_id = runtime.next_job_id();
    let message = build(runtime.core(), job_id)?;
    let (tx, rx) = mpsc::channel();
    runtime.track_job(
        job_id,
        move |job| {
            let body = (!job.synthetic_failure && job.eresult > 0).then_some(job.body);
            let _ = tx.send(body);
        },
        Some(timeout),
    );
    if !runtime.core().enqueue_wire(message.wire) {
        return None;
    }
    runtime.flush_outbound();
    rx.recv_timeout(timeout).ok().flatten()
}

fn request_pics_product_info(
    runtime: &Arc<CMClientRuntime>,
    packages: Vec<crate::pb::cmsg_client_pics::PicsPackageInfoReq>,
    apps: Vec<crate::pb::cmsg_client_pics::PicsAppInfoReq>,
    meta_data_only: bool,
    timeout: Duration,
) -> Option<crate::pb::cmsg_client_pics::CMsgClientPICSProductInfoResponse> {
    let body = request_proto_body(runtime, timeout, |core, job_id| {
        core.build_pics_product_info(packages, apps, meta_data_only, job_id)
    })?;
    let mut response =
        crate::pb::cmsg_client_pics::CMsgClientPICSProductInfoResponse::deserialize(&body)?;
    hydrate_http_delivered_appinfo(&mut response, timeout);
    Some(response)
}

/// Large appinfo the CM delivered over HTTP arrives as an empty `buffer` + a
/// `sha`/`http_host`; fetch it like the official client so the app isn't dropped.
fn hydrate_http_delivered_appinfo(
    response: &mut crate::pb::cmsg_client_pics::CMsgClientPICSProductInfoResponse,
    timeout: Duration,
) {
    if response.http_host.is_empty() {
        return;
    }
    let targets: Vec<(usize, u32, Vec<u8>)> = response
        .apps
        .iter()
        .enumerate()
        .filter(|(_, app)| !app.missing_token && app.buffer.is_empty() && !app.sha.is_empty())
        .map(|(index, app)| (index, app.appid, app.sha.clone()))
        .collect();
    if targets.is_empty() {
        return;
    }
    let host = response.http_host.clone();
    let cdn = crate::cdn_client::CdnClient::new("");
    let total = targets.len();
    let fetched = fetch_appinfo_buffers_parallel(&cdn, &host, targets, timeout);
    let mut hydrated = 0usize;
    for (index, buffer) in fetched {
        if let (Some(buffer), Some(app)) = (buffer, response.apps.get_mut(index)) {
            app.buffer = buffer;
            hydrated += 1;
        }
    }
    android_log(
        "LUDASHI_STEAM_PICS",
        &format!("hydrated {hydrated}/{total} HTTP-delivered appinfo buffer(s) from {host}"),
    );
}

/// Concurrent appinfo fetch (bounded pool); returns `(index, buffer?)`.
fn fetch_appinfo_buffers_parallel(
    cdn: &crate::cdn_client::CdnClient,
    host: &str,
    targets: Vec<(usize, u32, Vec<u8>)>,
    timeout: Duration,
) -> Vec<(usize, Option<Vec<u8>>)> {
    let worker_count = targets.len().clamp(1, 8);
    let queue = Mutex::new(targets.into_iter());
    let results = Mutex::new(Vec::new());
    thread::scope(|scope| {
        for _ in 0..worker_count {
            scope.spawn(|| {
                let mut conn = cdn.open_connection();
                loop {
                    let job = queue.lock().expect("appinfo fetch queue poisoned").next();
                    let Some((index, app_id, sha)) = job else {
                        break;
                    };
                    let buffer =
                        cdn.fetch_appinfo_with_connection(&mut conn, host, app_id, &sha, timeout);
                    results
                        .lock()
                        .expect("appinfo fetch results poisoned")
                        .push((index, buffer));
                }
            });
        }
    });
    results.into_inner().expect("appinfo fetch results poisoned")
}

fn request_app_ownership_ticket(
    runtime: &Arc<CMClientRuntime>,
    app_id: u32,
    timeout: Duration,
) -> Option<crate::pb::cmsg_client_get_app_ownership_ticket::CMsgClientGetAppOwnershipTicketResponse>
{
    let body = request_proto_body(runtime, timeout, |core, job_id| {
        core.build_get_app_ownership_ticket(app_id, job_id)
    })?;
    let response =
        crate::pb::cmsg_client_get_app_ownership_ticket::CMsgClientGetAppOwnershipTicketResponse::deserialize(&body)?;
    if response.eresult == 1 && !response.ticket.is_empty() {
        runtime
            .core()
            .tickets()
            .store(app_id, response.eresult, response.ticket.clone());
    }
    Some(response)
}

/// CDN pool for SteamPipe. `cell_id` 0 lets Steam pick by the connection's location; a non-zero
/// cell (the logon response's cell id) asks for that region's pool. `prefer_dc` reorders the pool
/// so `cache<N>-<dc>.steamcontent.com` hosts come first (see `prefer_cdn_servers_for_dc`).
fn request_cdn_servers(
    runtime: &Arc<CMClientRuntime>,
    cell_id: u32,
    prefer_dc: &str,
    timeout: Duration,
) -> Option<Vec<crate::pb::ccontentserverdirectory::CContentServerDirectoryServerInfo>> {
    let body = request_authed_service_body(runtime, timeout, |core, job_id| {
        core.build_get_cdn_servers_call(cell_id, job_id)
    })?;
    let servers =
        crate::pb::ccontentserverdirectory::CContentServerDirectoryGetServersForSteamPipeResponse::deserialize(&body)
            .map(|response| response.servers)?;
    let servers = crate::depot_downloader::prefer_cdn_servers_for_dc(servers, prefer_dc);
    let preview = servers
        .iter()
        .take(4)
        .map(|s| s.host.as_str())
        .collect::<Vec<_>>()
        .join(",");
    android_log(
        "LUDASHI_STEAM_DL",
        &format!(
            "cdn pool: {} server(s) cell={cell_id} prefer_dc='{prefer_dc}' first=[{preview}]",
            servers.len()
        ),
    );
    let requested_max_servers =
        crate::pb::ccontentserverdirectory::CContentServerDirectoryGetServersForSteamPipeRequest::default()
            .max_servers;
    let mut host_keys: Vec<&str> = Vec::new();
    let mut vhost_keys: Vec<&str> = Vec::new();
    for server in &servers {
        let host = server.host.as_str();
        if !host_keys.contains(&host) {
            host_keys.push(host);
        }
        // The fetch scheduler's per-host cap keys on vhost-or-host, so count that separately: if the
        // two numbers differ, entries share a physical host and the window ceiling is optimistic.
        let vhost = if server.vhost.is_empty() {
            host
        } else {
            server.vhost.as_str()
        };
        if !vhost_keys.contains(&vhost) {
            vhost_keys.push(vhost);
        }
    }
    let distinct_host = host_keys.len();
    let distinct_vhost = vhost_keys.len();
    let dropped_china = servers.iter().filter(|s| s.steam_china_only).count();
    let dropped_no_host = servers
        .iter()
        .filter(|s| !s.steam_china_only && s.host.is_empty())
        .count();
    android_log(
        "LUDASHI_STEAM_DL",
        &format!(
            "cdn pool detail: requested(cell={cell_id} max_servers={requested_max_servers}) \
entries={} distinct_host={distinct_host} distinct_vhost_key={distinct_vhost} usable={} \
dropped_china={dropped_china} dropped_no_host={dropped_no_host}",
            servers.len(),
            servers.len() - dropped_china - dropped_no_host,
        ),
    );
    Some(servers)
}

const ERESULT_OK: i32 = 1;
const MAX_RESOLVE_ATTEMPTS: u32 = 4;

enum DepotKeyOutcome {
    Granted(Vec<u8>),
    AccessDenied,
    Transient,
}

enum ManifestCodeOutcome {
    Code(u64),
    AccessDenied,
    Transient,
}

/// One CM proto request returning the header eresult and body; None only when no response arrived.
fn request_proto_response<F>(
    runtime: &Arc<CMClientRuntime>,
    timeout: Duration,
    build: F,
) -> Option<(i32, Vec<u8>)>
where
    F: FnOnce(&CMClientCore, u64) -> Option<OutboundProtoMessage>,
{
    if runtime.core().state() != ClientState::LoggedOn {
        return None;
    }
    let job_id = runtime.next_job_id();
    let message = build(runtime.core(), job_id)?;
    let (tx, rx) = mpsc::channel();
    runtime.track_job(
        job_id,
        move |job| {
            let result = (!job.synthetic_failure).then(|| (job.eresult, job.body));
            let _ = tx.send(result);
        },
        Some(timeout),
    );
    if !runtime.core().enqueue_wire(message.wire) {
        return None;
    }
    runtime.flush_outbound();
    rx.recv_timeout(timeout).ok().flatten()
}

/// One authed service-method request returning the header eresult and body; None only when no response arrived.
fn request_authed_service_response<F>(
    runtime: &Arc<CMClientRuntime>,
    timeout: Duration,
    build: F,
) -> Option<(i32, Vec<u8>)>
where
    F: FnOnce(&CMClientCore, u64) -> Option<OutboundServiceCall>,
{
    if runtime.core().state() != ClientState::LoggedOn {
        return None;
    }
    let job_id = runtime.next_job_id();
    let call = build(runtime.core(), job_id)?;
    let (tx, rx) = mpsc::channel();
    runtime.track_job(
        job_id,
        move |job| {
            let result = (!job.synthetic_failure).then(|| (job.eresult, job.body));
            let _ = tx.send(result);
        },
        Some(timeout),
    );
    if !runtime.core().enqueue_wire(call.wire) {
        return None;
    }
    runtime.flush_outbound();
    rx.recv_timeout(timeout).ok().flatten()
}

fn request_manifest_request_code(
    runtime: &Arc<CMClientRuntime>,
    app_id: u32,
    depot_id: u32,
    manifest_id: u64,
    branch: &str,
    timeout: Duration,
) -> ManifestCodeOutcome {
    let Some((eresult, body)) = request_authed_service_response(runtime, timeout, |core, job_id| {
        core.build_manifest_request_code_call(app_id, depot_id, manifest_id, branch, job_id)
    }) else {
        return ManifestCodeOutcome::Transient;
    };
    if eresult == ERESULT_OK {
        if let Some(response) =
            crate::pb::ccontentserverdirectory::CContentServerDirectoryGetManifestRequestCodeResponse::deserialize(&body)
        {
            return ManifestCodeOutcome::Code(response.manifest_request_code);
        }
        return ManifestCodeOutcome::Transient;
    }
    // A response arrived but the code was refused (branch/depot not available to this
    // account); skip the depot rather than abort the whole download.
    android_log(
        "LUDASHI_STEAM_DOWNLOAD",
        &format!("manifest code refused depot={depot_id} eresult={eresult}"),
    );
    ManifestCodeOutcome::AccessDenied
}

fn request_depot_key(
    runtime: &Arc<CMClientRuntime>,
    app_id: u32,
    depot_id: u32,
    timeout: Duration,
) -> DepotKeyOutcome {
    let Some((header_eresult, body)) = request_proto_response(runtime, timeout, |core, job_id| {
        core.build_get_depot_decryption_key(depot_id, app_id, job_id)
    }) else {
        return DepotKeyOutcome::Transient;
    };
    if let Some(response) =
        crate::pb::cmsg_client_get_depot_decryption_key::CMsgClientGetDepotDecryptionKeyResponse::deserialize(&body)
    {
        if response.eresult == ERESULT_OK as u32 && response.depot_encryption_key.len() == 32 {
            return DepotKeyOutcome::Granted(response.depot_encryption_key);
        }
        android_log(
            "LUDASHI_STEAM_DOWNLOAD",
            &format!(
                "depot key refused depot={depot_id} header_eresult={header_eresult} body_eresult={}",
                response.eresult
            ),
        );
    } else {
        android_log(
            "LUDASHI_STEAM_DOWNLOAD",
            &format!("depot key refused depot={depot_id} header_eresult={header_eresult} (unparsed)"),
        );
    }
    // A response arrived but no usable key: Steam will not grant this depot to this
    // account. Skip it (DepotDownloader does the same) rather than abort the download.
    DepotKeyOutcome::AccessDenied
}

enum KeyResolution {
    Granted(Vec<u8>),
    AccessDenied,
    Cancelled,
    Unavailable,
}

fn resolve_depot_key_with_retry(
    runtime: &Arc<CMClientRuntime>,
    app_id: u32,
    depot_id: u32,
    timeout: Duration,
    cancel: &AtomicBool,
) -> KeyResolution {
    for attempt in 0..MAX_RESOLVE_ATTEMPTS {
        if cancel.load(Ordering::Relaxed) {
            return KeyResolution::Cancelled;
        }
        if attempt > 0 {
            thread::sleep(Duration::from_millis(
                crate::depot_downloader::retry_backoff_millis(attempt),
            ));
        }
        match request_depot_key(runtime, app_id, depot_id, timeout) {
            DepotKeyOutcome::Granted(key) => return KeyResolution::Granted(key),
            DepotKeyOutcome::AccessDenied => return KeyResolution::AccessDenied,
            DepotKeyOutcome::Transient => continue,
        }
    }
    KeyResolution::Unavailable
}

enum CodeResolution {
    Code(u64),
    AccessDenied,
    Cancelled,
    Unavailable,
}

fn resolve_manifest_code_with_retry(
    runtime: &Arc<CMClientRuntime>,
    app_id: u32,
    depot_id: u32,
    manifest_id: u64,
    branch: &str,
    timeout: Duration,
    cancel: &AtomicBool,
) -> CodeResolution {
    for attempt in 0..MAX_RESOLVE_ATTEMPTS {
        if cancel.load(Ordering::Relaxed) {
            return CodeResolution::Cancelled;
        }
        if attempt > 0 {
            thread::sleep(Duration::from_millis(
                crate::depot_downloader::retry_backoff_millis(attempt),
            ));
        }
        match request_manifest_request_code(runtime, app_id, depot_id, manifest_id, branch, timeout)
        {
            ManifestCodeOutcome::Code(code) => return CodeResolution::Code(code),
            ManifestCodeOutcome::AccessDenied => return CodeResolution::AccessDenied,
            ManifestCodeOutcome::Transient => continue,
        }
    }
    CodeResolution::Unavailable
}

/// Records depots Steam denied a key for so the Kotlin completeness gate can exclude them.
fn write_denied_depots_marker(config_dir: &std::path::Path, denied: &[u32]) {
    let path = config_dir.join("denied.depots");
    if denied.is_empty() {
        let _ = std::fs::remove_file(&path);
        return;
    }
    let _ = std::fs::create_dir_all(config_dir);
    let body = denied
        .iter()
        .map(|id| id.to_string())
        .collect::<Vec<_>>()
        .join("\n");
    let _ = std::fs::write(&path, body);
}

fn request_item_def_digest(
    runtime: &Arc<CMClientRuntime>,
    app_id: u32,
    timeout: Duration,
) -> Option<String> {
    if app_id == 0 || runtime.core().state() != ClientState::LoggedOn {
        return None;
    }
    let job_id = runtime.next_job_id();
    let call = runtime
        .core()
        .build_inventory_item_def_meta_call(app_id, job_id)?;
    let (tx, rx) = mpsc::channel();
    runtime.track_job(
        job_id,
        move |job| {
            let digest = if job.synthetic_failure || job.eresult != 1 {
                None
            } else {
                crate::pb::cinventory::CInventoryGetItemDefMetaResponse::deserialize(&job.body)
                    .and_then(|response| (!response.digest.is_empty()).then_some(response.digest))
            };
            let _ = tx.send(digest);
        },
        Some(timeout),
    );
    if !runtime.core().enqueue_wire(call.wire) {
        return None;
    }
    runtime.flush_outbound();
    rx.recv_timeout(timeout).ok().flatten()
}

fn request_subscribed_workshop_page(
    runtime: &Arc<CMClientRuntime>,
    app_id: u32,
    page: u32,
    page_size: u32,
    timeout: Duration,
) -> Option<crate::pb::cpublishedfile::CPublishedFileGetUserFilesResponse> {
    if app_id == 0 || page == 0 || page_size == 0 || runtime.core().state() != ClientState::LoggedOn
    {
        return None;
    }
    let job_id = runtime.next_job_id();
    let call = runtime
        .core()
        .build_published_file_subscribed_call(app_id, page, page_size, job_id)?;
    let (tx, rx) = mpsc::channel();
    runtime.track_job(
        job_id,
        move |job| {
            let response = if job.synthetic_failure || job.eresult != 1 {
                None
            } else {
                crate::pb::cpublishedfile::CPublishedFileGetUserFilesResponse::deserialize(
                    &job.body,
                )
            };
            let _ = tx.send(response);
        },
        Some(timeout),
    );
    if !runtime.core().enqueue_wire(call.wire) {
        return None;
    }
    runtime.flush_outbound();
    rx.recv_timeout(timeout).ok().flatten()
}

struct QrPollState {
    runtime: Arc<CMClientRuntime>,
    qr_callback: GlobalRef,
    result_callback: GlobalRef,
    cancel: Arc<AtomicBool>,
    client_id: u64,
    request_id: Vec<u8>,
    poll_interval_seconds: f32,
    last_challenge_url: Arc<Mutex<String>>,
}

fn start_qr_poll_loop(state: QrPollState) {
    thread::spawn(move || {
        let QrPollState {
            runtime,
            qr_callback,
            result_callback,
            cancel,
            mut client_id,
            request_id,
            poll_interval_seconds,
            last_challenge_url,
        } = state;
        let poll_interval = Duration::from_secs_f32(poll_interval_seconds.max(0.25));
        let timeout = Duration::from_secs(30);
        let mut reported_remote_interaction = false;
        qr_log(&format!(
            "poll loop start client_id={} request_id_len={} interval_s={:.2}",
            client_id,
            request_id.len(),
            poll_interval_seconds
        ));
        loop {
            for slice in sleep_slices(poll_interval, Duration::from_millis(100)) {
                if cancel.load(Ordering::Relaxed) {
                    return;
                }
                thread::sleep(slice);
            }
            if cancel.load(Ordering::Relaxed) {
                return;
            }

            let request = build_poll_request(client_id, request_id.clone()).serialize();
            let poll_job = match request_service_method_job(
                &runtime,
                "Authentication.PollAuthSessionStatus#1",
                false,
                request,
                timeout,
                Some(cancel.as_ref()),
            ) {
                Some(job) => job,
                None => {
                    if cancel.load(Ordering::Relaxed) {
                        qr_log("poll loop cancelled while waiting for status");
                        return;
                    }
                    qr_log("PollAuthSessionStatus timed out");
                    dispatch_auth_result(
                        &result_callback,
                        AuthSessionResult {
                            error_message: "PollAuthSessionStatus timed out".to_string(),
                            ..Default::default()
                        },
                    );
                    return;
                }
            };
            if poll_job.synthetic_failure || poll_job.eresult != 1 {
                qr_log(&format!(
                    "PollAuthSessionStatus failed synthetic={} eresult={} body_len={}",
                    poll_job.synthetic_failure,
                    poll_job.eresult,
                    poll_job.body.len()
                ));
                dispatch_auth_result(
                    &result_callback,
                    auth_result_from_job(&poll_job, "PollAuthSessionStatus"),
                );
                return;
            }
            let Some(resp) = crate::pb::cauthentication::PollAuthSessionStatusResponse::deserialize(
                &poll_job.body,
            ) else {
                qr_log(&format!(
                    "Poll response parse failed body_len={}",
                    poll_job.body.len()
                ));
                dispatch_auth_result(
                    &result_callback,
                    AuthSessionResult {
                        error_message: "Poll response parse failed".to_string(),
                        ..Default::default()
                    },
                );
                return;
            };
            qr_log(&format!(
                "poll response new_client_id={} remote={} refresh_len={} access_len={} account_len={} agreement_len={} challenge_len={}",
                resp.new_client_id,
                resp.had_remote_interaction,
                resp.refresh_token.len(),
                resp.access_token.len(),
                resp.account_name.len(),
                resp.agreement_session_url.len(),
                resp.new_challenge_url.len()
            ));
            if resp.new_client_id != 0 {
                client_id = resp.new_client_id;
            }
            if let Some(challenge) = {
                let mut last = last_challenge_url
                    .lock()
                    .expect("qr challenge url poisoned");
                crate::auth_session::take_qr_challenge_update(&mut last, &resp)
            } {
                dispatch_qr_challenge(&qr_callback, &challenge);
            }
            let remote_interaction =
                take_qr_remote_interaction(&mut reported_remote_interaction, &resp);
            let account_name = resp.account_name.clone();
            let agreement_session_url = resp.agreement_session_url.clone();
            if let Some(result) = auth_result_from_poll(resp, 0) {
                qr_log(&format!(
                    "dispatching final QR auth result success={} refresh_len={} account_len={} remote={}",
                    result.success,
                    result.refresh_token.len(),
                    result.account_name.len(),
                    result.had_remote_interaction
                ));
                dispatch_auth_result(&result_callback, result);
                return;
            }
            if remote_interaction {
                qr_log("dispatching intermediate remote-interaction QR auth update");
                dispatch_auth_result(
                    &result_callback,
                    AuthSessionResult {
                        account_name,
                        agreement_session_url,
                        had_remote_interaction: true,
                        ..Default::default()
                    },
                );
            }
        }
    });
}

fn call_prepare_result(env: &mut JNIEnv, callback: &JObject, ok: bool, error: &str) {
    if callback.is_null() {
        return;
    }
    let Ok(error) = env.new_string(error) else {
        clear_pending_exception(env);
        return;
    };
    let error_obj = JObject::from(error);
    let _ = env.call_method(
        callback,
        "onPrepareResult",
        "(ZLjava/lang/String;)V",
        &[
            JValue::Bool(if ok { JNI_TRUE } else { JNI_FALSE }),
            JValue::Object(&error_obj),
        ],
    );
    clear_pending_exception(env);
}

fn call_download_complete_result(
    env: &mut JNIEnv,
    listener: &JObject,
    success: bool,
    error: &str,
    bytes_written: u64,
    depots_completed: u32,
    depots_skipped: u32,
) {
    if listener.is_null() {
        return;
    }
    let Ok(error) = env.new_string(error) else {
        clear_pending_exception(env);
        return;
    };
    let error_obj = JObject::from(error);
    let _ = env.call_method(
        listener,
        "onComplete",
        "(ZLjava/lang/String;JII)V",
        &[
            JValue::Bool(if success { JNI_TRUE } else { JNI_FALSE }),
            JValue::Object(&error_obj),
            JValue::Long(bytes_written as jlong),
            JValue::Int(depots_completed as jint),
            JValue::Int(depots_skipped as jint),
        ],
    );
    clear_pending_exception(env);
}

fn call_download_complete(env: &mut JNIEnv, listener: &JObject, error: &str) {
    call_download_complete_result(env, listener, false, error, 0, 0, 0);
}

fn dispatch_download_complete(
    listener: GlobalRef,
    result: crate::depot_downloader::DepotDownloadResult,
) {
    let Some(vm) = JVM.get() else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
        return;
    };
    let listener_obj = listener.as_obj();
    call_download_complete_result(
        &mut env,
        listener_obj,
        result.success,
        &result.error,
        result.bytes_written,
        result.depots_completed,
        result.depots_skipped,
    );
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativePickCmUrl(
    mut env: JNIEnv,
    _class: JClass,
    ca_bundle_path: JString,
) -> jstring {
    let ca_bundle_path = jstring_to_string(&mut env, &ca_bundle_path).unwrap_or_default();
    let directory = crate::steam_directory::SteamDirectoryClient::new(ca_bundle_path);
    let result = directory.fetch(0, crate::steam_directory::DEFAULT_TIMEOUT);
    let directory_url = if result.ok() {
        result
            .servers
            .into_iter()
            .find_map(|server| {
                let url = server.websocket_url();
                (!url.is_empty()).then_some(url)
            })
            .unwrap_or_default()
    } else {
        String::new()
    };
    let url = if !directory_url.is_empty() {
        directory_url
    } else {
        crate::cm_server_list::hardcoded_fallback_servers()
            .into_iter()
            .find_map(|server| {
                let url = server.websocket_url();
                (!url.is_empty()).then_some(url)
            })
            .unwrap_or_default()
    };
    new_string_or_null(&mut env, &url)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeCreate(
    mut env: JNIEnv,
    _class: JClass,
) -> jlong {
    let _ = ensure_auth_result_class(&mut env);
    to_session_handle(Box::new(LudashiSteamSessionHandle::new()))
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    unsafe { drop_session_handle(handle) };
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeSetCaBundlePath(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    path: JString,
) {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return;
    };
    if let Ok(path) = env.get_string(&path) {
        handle.ca_bundle_path = path.to_string_lossy().into_owned();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeSetAutoPopulateLibrary(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    enabled: jboolean,
) {
    if let Some(handle) = unsafe { from_session_handle_mut(handle) } {
        handle.auto_populate_library = enabled != JNI_FALSE;
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeSetAutoPersonaOnline(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    enabled: jboolean,
) {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return;
    };
    let enabled = enabled != JNI_FALSE;
    handle.auto_persona_online.store(enabled, Ordering::Relaxed);
    if let Some(runtime) = handle
        .runtime
        .lock()
        .expect("session runtime poisoned")
        .as_ref()
    {
        runtime.set_auto_persona_online(enabled);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeConnect(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    url: JString,
) -> jboolean {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return JNI_FALSE;
    };
    let Some(url) = jstring_to_string(&mut env, &url) else {
        return JNI_FALSE;
    };
    if url.is_empty() {
        return JNI_FALSE;
    }
    let runtime = handle.runtime();
    if !runtime.connect(&url) {
        return JNI_FALSE;
    }
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeDisconnect(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(handle) = unsafe { from_session_handle_mut(handle) } {
        if let Some(runtime) = handle
            .runtime
            .lock()
            .expect("session runtime poisoned")
            .take()
        {
            runtime.disconnect();
        } else {
            handle.core.set_state(ClientState::Disconnected);
            dispatch_state_observer(&handle.state_observer, ClientState::Disconnected);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeLogOffAndDisconnect(
    env: JNIEnv,
    class: JClass,
    handle: jlong,
    flush_ms: jint,
) {
    if let Some(handle_ref) = unsafe { from_session_handle_mut(handle) } {
        if handle_ref.enqueue_proto(handle_ref.core.build_logoff()) {
            let flush = if flush_ms <= 0 {
                Duration::from_millis(500)
            } else {
                Duration::from_millis(flush_ms as u64)
            };
            thread::sleep(flush);
        }
    }
    Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeDisconnect(
        env, class, handle,
    );
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeIsPlayingBlocked(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return JNI_FALSE;
    };
    if handle.core.is_playing_blocked() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeMarkPlayingBlocked(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(handle) = unsafe { from_session_handle_mut(handle) } {
        handle.core.mark_playing_blocked();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeState(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ClientState::Disconnected as jint;
    };
    handle.core.state() as jint
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeSteamId(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return 0;
    };
    handle.core.steam_id() as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeFamilyGroupId(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return 0;
    };
    handle.core.family_group_id() as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetLibrarySnapshot(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return new_string_or_null(&mut env, "{}");
    };
    new_string_or_null(&mut env, &handle.core.library().snapshot_json())
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetLibraryCache(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return new_string_or_null(&mut env, "{}");
    };
    new_string_or_null(&mut env, &handle.core.library().cache_json())
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeRestoreLibraryCache(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    cache: JString,
) -> jboolean {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else { return JNI_FALSE };
    let Some(cache) = jstring_to_string(&mut env, &cache) else { return JNI_FALSE };
    if handle.core.library().restore_cache_json(&cache).is_ok() { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeStartWineBridge(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    steam3_port: jint,
    client_service_port: jint,
) -> jboolean {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return JNI_FALSE;
    };
    let mut config = WineBridgeConfig::default();
    if steam3_port > 0 {
        config.steam3_port = steam3_port as u16;
    }
    if client_service_port > 0 {
        config.client_svc_port = client_service_port as u16;
    }
    if handle.wine_bridge.start(config) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeStopWineBridge(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(handle) = unsafe { from_session_handle_mut(handle) } {
        handle.wine_bridge.stop();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeWineBridgeLastError(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return new_string_or_null(&mut env, "");
    };
    new_string_or_null(&mut env, &handle.wine_bridge.last_error())
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetAppOwnershipTicket(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
) -> jbyteArray {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(ticket) = handle.core.tickets().get(app_id as u32) else {
        return ptr::null_mut();
    };
    if ticket.eresult != 1 || ticket.ticket.is_empty() {
        return ptr::null_mut();
    }
    byte_array_or_null(&env, &ticket.ticket)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetLicenseList(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let licenses = handle.core.license_list();
    let value = json!(licenses
        .iter()
        .map(|license| json!({
            "packageId": license.package_id,
            "changeNumber": license.change_number,
            "timeCreated": license.time_created,
            "timeNextProcess": license.time_next_process,
            "minuteLimit": license.minute_limit,
            "minutesUsed": license.minutes_used,
            "paymentMethod": license.payment_method,
            "flags": license.flags,
            "purchaseCountryCode": license.purchase_country_code,
            "licenseType": license.license_type,
            "territoryCode": license.territory_code,
            "accessToken": license.access_token as i64,
            "ownerId": license.owner_id,
            "masterPackageId": license.master_package_id,
        }))
        .collect::<Vec<_>>())
    .to_string();
    new_string_or_null(&mut env, &value)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetFriendsList(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlongArray {
    let friends = match unsafe { from_session_handle_mut(handle) } {
        Some(handle) => handle
            .core
            .friends_list()
            .into_iter()
            .map(|sid| sid as jlong)
            .collect::<Vec<_>>(),
        None => Vec::new(),
    };
    let Ok(array) = env.new_long_array(friends.len() as i32) else {
        return ptr::null_mut();
    };
    if !friends.is_empty() && env.set_long_array_region(&array, 0, &friends).is_err() {
        return ptr::null_mut();
    }
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetFriendPersonas(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return new_string_or_null(&mut env, "[]");
    };
    let value = json!(handle
        .core
        .friend_personas()
        .iter()
        .map(|persona| json!({
            "sid": persona.sid as i64,
            "name": persona.player_name,
            "state": persona.persona_state,
            "app": persona.game_played_app_id,
            "avatarHash": crate::cdn_client::hex_encode(&persona.avatar_hash),
            "gameName": persona.game_name,
            "gameId": persona.gameid as i64,
            "connect": persona
                .rich_presence
                .iter()
                .find(|(k, _)| k == "connect")
                .map(|(_, v)| v.as_str())
                .unwrap_or(""),
            "rp": persona
                .rich_presence
                .iter()
                .map(|(k, v)| (k.clone(), serde_json::Value::String(v.clone())))
                .collect::<serde_json::Map<String, serde_json::Value>>(),
        }))
        .collect::<Vec<_>>())
    .to_string();
    new_string_or_null(&mut env, &value)
}

/// Every known relationship as `[{"sid","rel"}]` (EFriendRelationship values) — the roster's
/// friend / incoming-invite / outgoing-invite buckets. Cheap: a cached map, no network.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetFriendRelationships(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return new_string_or_null(&mut env, "[]");
    };
    let value = json!(handle
        .core
        .friend_relationships()
        .iter()
        .map(|(sid, rel)| json!({ "sid": *sid as i64, "rel": rel }))
        .collect::<Vec<_>>())
    .to_string();
    new_string_or_null(&mut env, &value)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetSelfPersona(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(persona) = handle.core.self_persona() else {
        return ptr::null_mut();
    };
    let value = json!({
        "personaState": persona.persona_state,
        "gameAppId": persona.game_played_app_id,
        "playerName": persona.player_name,
        "avatarHash": crate::cdn_client::hex_encode(&persona.avatar_hash),
        "gameName": persona.game_name,
        "gameId": persona.gameid as i64,
    })
    .to_string();
    new_string_or_null(&mut env, &value)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeSendFriendMessage(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    steam_id: jlong,
    message: JString,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let Some(message) = jstring_to_string(&mut env, &message) else {
        return ptr::null_mut();
    };
    if message.is_empty() {
        return ptr::null_mut();
    }
    let contains_bbcode = message.contains("[img]");
    let Some(body) =
        request_authed_service_body(&runtime, Duration::from_secs(8), |core, job_id| {
            core.build_send_friend_message(steam_id as u64, &message, contains_bbcode, job_id)
        })
    else {
        return ptr::null_mut();
    };
    let Some(response) =
        crate::pb::cfriendmessages::CFriendMessagesSendMessageResponse::deserialize(&body)
    else {
        return ptr::null_mut();
    };
    let value = json!({
        "serverTimestamp": response.server_timestamp,
        "ordinal": response.ordinal,
    })
    .to_string();
    new_string_or_null(&mut env, &value)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetRecentMessages(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    steam_id: jlong,
    count: jint,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return new_string_or_null(&mut env, "[]");
    };
    let Some(runtime) = handle.connected_runtime() else {
        return new_string_or_null(&mut env, "[]");
    };
    let self_accountid = (handle.core.steam_id() & 0xFFFF_FFFF) as u32;
    let count = count.clamp(1, 200) as u32;
    let Some(body) =
        request_authed_service_body(&runtime, Duration::from_secs(8), |core, job_id| {
            core.build_get_recent_messages(steam_id as u64, count, job_id)
        })
    else {
        return new_string_or_null(&mut env, "[]");
    };
    let Some(response) =
        crate::pb::cfriendmessages::CFriendMessagesGetRecentMessagesResponse::deserialize(&body)
    else {
        return new_string_or_null(&mut env, "[]");
    };
    let value = json!(response
        .messages
        .iter()
        .map(|m| json!({
            "fromSelf": m.accountid == self_accountid,
            "message": m.message,
            "timestamp": m.timestamp,
            "ordinal": m.ordinal,
        }))
        .collect::<Vec<_>>())
    .to_string();
    new_string_or_null(&mut env, &value)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeDrainFriendMessages(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return new_string_or_null(&mut env, "[]");
    };
    let value = json!(handle
        .core
        .drain_incoming_messages()
        .iter()
        .map(|m| json!({
            "friendId": m.friend_id as i64,
            "fromSelf": m.from_self,
            "message": m.message,
            "timestamp": m.timestamp,
            "ordinal": m.ordinal,
            "type": m.chat_entry_type,
        }))
        .collect::<Vec<_>>())
    .to_string();
    new_string_or_null(&mut env, &value)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeSendChatImage(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    steam_id: jlong,
    refresh_token: JString,
    image: JByteArray,
    file_name: JString,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let self_steamid = handle.core.steam_id();
    let ca_bundle_path = handle.ca_bundle_path.clone();
    let Some(refresh_token) = jstring_to_string(&mut env, &refresh_token) else {
        return ptr::null_mut();
    };
    let file_name = jstring_to_string(&mut env, &file_name).unwrap_or_else(|| "image.png".into());
    let Ok(bytes) = env.convert_byte_array(image) else {
        return ptr::null_mut();
    };
    if bytes.is_empty() || self_steamid == 0 || refresh_token.is_empty() {
        return ptr::null_mut();
    }

    // Mint a short-lived web access token for the steamLoginSecure cookie.
    let request = crate::pb::cauthentication::AccessTokenGenerateForAppRequest {
        refresh_token,
        steamid: self_steamid,
        renewal_type: crate::pb::cauthentication::EAuthTokenRenewalType::None,
    }
    .serialize();
    let Some(token_body) =
        request_authed_service_body(&runtime, Duration::from_secs(15), move |core, job_id| {
            core.build_authed_service_call(
                "Authentication.GenerateAccessTokenForApp#1",
                job_id,
                request,
            )
        })
    else {
        return ptr::null_mut();
    };
    let access_token = crate::pb::cauthentication::AccessTokenGenerateForAppResponse::deserialize(
        &token_body,
    )
    .map(|r| r.access_token)
    .unwrap_or_default();
    if access_token.is_empty() {
        android_log("LUDASHI_STEAM_IMG", "no web access token");
        return ptr::null_mut();
    }

    let url = match crate::chat_image::upload(
        &ca_bundle_path,
        self_steamid,
        steam_id as u64,
        &access_token,
        &bytes,
        &file_name,
    ) {
        Ok(url) => url,
        Err(err) => {
            android_log("LUDASHI_STEAM_IMG", &format!("upload failed: {err}"));
            return ptr::null_mut();
        }
    };
    new_string_or_null(&mut env, &url)
}

/// Blocking typing notification (`FriendMessages.SendMessage`, chat_entry_type 2).
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeSendFriendTyping(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    steam_id: jlong,
) -> jboolean {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return JNI_FALSE;
    };
    let Some(runtime) = handle.connected_runtime() else {
        return JNI_FALSE;
    };
    let ok = request_authed_service_success(&runtime, Duration::from_secs(10), |core, job_id| {
        core.build_send_friend_message_typed(
            steam_id as u64,
            crate::pb::cfriendmessages::CHAT_ENTRY_TYPE_TYPING,
            "",
            false,
            job_id,
        )
    });
    if ok {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Fire-and-forget `CMsgClientAddFriend` — by SteamID64 (`steam_id` != 0) or by account name /
/// e-mail. The verdict arrives as `CMsgClientAddFriendResponse` (EMsg 792) on the firehose.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeAddFriend(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    steam_id: jlong,
    account_name: JString,
) -> jboolean {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return JNI_FALSE;
    };
    let name = jstring_to_string(&mut env, &account_name).unwrap_or_default();
    if handle.enqueue_proto(handle.core.build_add_friend(steam_id as u64, &name)) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Fire-and-forget `CMsgClientRemoveFriend` (remove / decline / cancel an invite).
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeRemoveFriend(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    steam_id: jlong,
) -> jboolean {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return JNI_FALSE;
    };
    if handle.enqueue_proto(handle.core.build_remove_friend(steam_id as u64)) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Blocking friend profile lookup (`CMsgClientFriendProfileInfo`); JSON or null.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetFriendProfileInfo(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    steam_id: jlong,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let Some(body) = request_proto_body(&runtime, Duration::from_secs(15), |core, job_id| {
        core.build_friend_profile_info(steam_id as u64, job_id)
    }) else {
        return ptr::null_mut();
    };
    let Some(resp) =
        crate::pb::cmsg_client_friends_ops::CMsgClientFriendProfileInfoResponse::deserialize(&body)
    else {
        return ptr::null_mut();
    };
    let value = json!({
        "eresult": resp.eresult,
        "steamId": resp.steamid_friend as i64,
        "timeCreated": resp.time_created,
        "realName": resp.real_name,
        "cityName": resp.city_name,
        "stateName": resp.state_name,
        "countryName": resp.country_name,
        "headline": resp.headline,
        "summary": resp.summary,
    })
    .to_string();
    new_string_or_null(&mut env, &value)
}

/// Blocking `UserAccount.CreateFriendInviteToken#1`; the raw invite token or null.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeCreateFriendInviteToken(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let Some(body) =
        request_authed_service_body(&runtime, Duration::from_secs(15), |core, job_id| {
            core.build_create_friend_invite_token_call(job_id)
        })
    else {
        return ptr::null_mut();
    };
    let Some(resp) =
        crate::pb::cuseraccount::CUserAccountCreateFriendInviteTokenResponse::deserialize(&body)
    else {
        return ptr::null_mut();
    };
    if resp.invite_token.is_empty() {
        return ptr::null_mut();
    }
    new_string_or_null(&mut env, &resp.invite_token)
}

/// Blocking web-audience access token mint (`Authentication.GenerateAccessTokenForApp`, no
/// renewal) for the community `steamLoginSecure` cookie (chat image upload, user search). The
/// refresh token only travels to Steam; it is never logged.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGenerateWebAccessToken(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    refresh_token: JString,
    steam_id: jlong,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let Some(refresh_token) = jstring_to_string(&mut env, &refresh_token) else {
        return ptr::null_mut();
    };
    let steam_id = if steam_id != 0 {
        steam_id as u64
    } else {
        handle.core.steam_id()
    };
    if refresh_token.is_empty() || steam_id == 0 {
        return ptr::null_mut();
    }
    let request = crate::pb::cauthentication::AccessTokenGenerateForAppRequest {
        refresh_token,
        steamid: steam_id,
        renewal_type: crate::pb::cauthentication::EAuthTokenRenewalType::None,
    }
    .serialize();
    let Some(token_body) =
        request_authed_service_body(&runtime, Duration::from_secs(15), move |core, job_id| {
            core.build_authed_service_call(
                "Authentication.GenerateAccessTokenForApp#1",
                job_id,
                request,
            )
        })
    else {
        return ptr::null_mut();
    };
    let access_token = crate::pb::cauthentication::AccessTokenGenerateForAppResponse::deserialize(
        &token_body,
    )
    .map(|r| r.access_token)
    .unwrap_or_default();
    if access_token.is_empty() {
        return ptr::null_mut();
    }
    new_string_or_null(&mut env, &access_token)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeSetPersonaState(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    persona_state: jint,
) {
    if let Some(handle) = unsafe { from_session_handle_mut(handle) } {
        handle.enqueue_proto(handle.core.build_set_persona_state(persona_state as u32));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeSetPersonaName(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    name: JString,
    persona_state: jint,
) {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return;
    };
    let Ok(name) = env.get_string(&name) else {
        return;
    };
    let name = name.to_string_lossy().into_owned();
    handle.enqueue_proto(
        handle
            .core
            .build_set_persona_name(name, persona_state as u32),
    );
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeRequestUserPersona(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(handle) = unsafe { from_session_handle_mut(handle) } {
        handle.enqueue_proto(handle.core.build_request_user_persona());
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeRequestFriendPersonas(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    steam_ids: JLongArray,
    flags: jint,
) {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return;
    };
    let len = env.get_array_length(&steam_ids).unwrap_or(0);
    if len <= 0 {
        return;
    }
    let mut ids = vec![0i64; len as usize];
    if env.get_long_array_region(&steam_ids, 0, &mut ids).is_err() {
        return;
    }
    let ids = ids.into_iter().map(|id| id as u64).collect::<Vec<_>>();
    handle.enqueue_proto(
        handle
            .core
            .build_request_friend_personas(&ids, flags as u32),
    );
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeNotifyGamesPlayed(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    games_json: JString,
    client_os_type: jint,
) {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return;
    };
    let Ok(games_json) = env.get_string(&games_json) else {
        return;
    };
    let raw = games_json.to_string_lossy().into_owned();
    let parsed: Option<serde_json::Value> = serde_json::from_str(&raw).ok();
    let entry = parsed
        .as_ref()
        .and_then(|value| value.as_array().and_then(|arr| arr.first()))
        .cloned()
        .unwrap_or(serde_json::Value::Null);
    let pick_u64 = |entry: &serde_json::Value, keys: &[&str]| -> u64 {
        for key in keys {
            if let Some(value) = entry.get(*key) {
                if let Some(v) = value.as_u64() {
                    return v;
                }
                if let Some(s) = value.as_str() {
                    if let Ok(v) = s.parse::<u64>() {
                        return v;
                    }
                }
            }
        }
        0
    };
    let pick_u32 = |entry: &serde_json::Value, keys: &[&str]| -> u32 {
        pick_u64(entry, keys) as u32
    };
    let game_id = pick_u64(&entry, &["gameId", "game_id", "appid", "app_id"]);
    let extras = crate::cm_client::GamesPlayedExtras {
        process_id: pick_u32(&entry, &["processId", "process_id"]),
        owner_id: pick_u32(&entry, &["ownerId", "owner_id"]),
        launch_source: pick_u32(&entry, &["launchSource", "launch_source"]),
        game_build_id: pick_u32(&entry, &["gameBuildId", "game_build_id"]),
    };
    let processes = entry
        .get("processes")
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .map(|p| crate::pb::cmsg_client_games_played::GamePlayedProcessInfo {
                    process_id: pick_u32(p, &["pid", "processId", "process_id"]),
                    process_id_parent: pick_u32(
                        p,
                        &["ppid", "processIdParent", "process_id_parent"],
                    ),
                    parent_is_steam: p
                        .get("isSteam")
                        .or_else(|| p.get("parentIsSteam"))
                        .or_else(|| p.get("parent_is_steam"))
                        .and_then(|v| v.as_bool())
                        .unwrap_or(false),
                })
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    let os_type = if client_os_type < 0 {
        0
    } else {
        client_os_type as u32
    };
    handle.enqueue_proto(handle.core.build_notify_games_played_full(
        game_id, &extras, &processes, os_type,
    ));
}

/// Blocking `Player.SetRichPresence#1` for [app_id] with the key/values in [kv_json] (a JSON
/// object of strings; an empty object clears the presence). True when Steam acknowledged.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeSetRichPresence(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
    kv_json: JString,
) -> jboolean {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return JNI_FALSE;
    };
    let Some(runtime) = handle.connected_runtime() else {
        return JNI_FALSE;
    };
    let raw = jstring_to_string(&mut env, &kv_json).unwrap_or_default();
    let kv: Vec<(String, String)> = serde_json::from_str::<serde_json::Value>(&raw)
        .ok()
        .and_then(|v| v.as_object().cloned())
        .map(|obj| {
            obj.into_iter()
                .map(|(k, v)| {
                    let value = match v {
                        serde_json::Value::String(s) => s,
                        serde_json::Value::Null => String::new(),
                        other => other.to_string(),
                    };
                    (k, value)
                })
                .collect()
        })
        .unwrap_or_default();
    let app_id = if app_id < 0 { 0 } else { app_id as u32 };
    let ok = request_authed_service_success(&runtime, Duration::from_secs(10), |core, job_id| {
        core.build_rich_presence_call(app_id, kv, job_id)
    });
    if ok {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeKickPlayingSession(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    only_stop_game: jboolean,
) {
    if let Some(handle) = unsafe { from_session_handle_mut(handle) } {
        handle.enqueue_proto(
            handle
                .core
                .build_kick_playing_session(only_stop_game != JNI_FALSE),
        );
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeStoreUserStats(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
    steam_id: jlong,
    crc_stats: jint,
    stat_ids: JIntArray,
    stat_values: JIntArray,
) {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return;
    };
    let len = env
        .get_array_length(&stat_ids)
        .unwrap_or(0)
        .min(env.get_array_length(&stat_values).unwrap_or(0));
    if len < 0 {
        return;
    }
    let mut ids = vec![0i32; len as usize];
    let mut values = vec![0i32; len as usize];
    if env.get_int_array_region(&stat_ids, 0, &mut ids).is_err()
        || env
            .get_int_array_region(&stat_values, 0, &mut values)
            .is_err()
    {
        return;
    }
    let stats = ids
        .into_iter()
        .zip(values)
        .map(|(id, value)| (id as u32, value as u32))
        .collect::<Vec<_>>();
    handle.enqueue_proto(handle.core.build_store_user_stats(
        app_id as u32,
        steam_id as u64,
        crc_stats as u32,
        &stats,
    ));
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeSetStateObserver(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    observer: JObject,
) {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return;
    };
    let mut slot = handle
        .state_observer
        .lock()
        .expect("session state observer poisoned");
    *slot = if observer.is_null() {
        None
    } else {
        env.new_global_ref(observer).ok()
    };
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeSetLibraryObserver(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    observer: JObject,
) {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return;
    };
    let mut slot = handle
        .library_observer
        .lock()
        .expect("session library observer poisoned");
    *slot = if observer.is_null() {
        None
    } else {
        env.new_global_ref(observer).ok()
    };
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeRenewRefreshToken(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    current_token: JString,
    steam_id64: jlong,
    timeout_ms: jint,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    if handle.core.state() != ClientState::LoggedOn {
        return ptr::null_mut();
    }
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let Some(current_token) = jstring_to_string(&mut env, &current_token) else {
        return ptr::null_mut();
    };
    if current_token.is_empty() || steam_id64 == 0 {
        return ptr::null_mut();
    }

    let request = crate::pb::cauthentication::AccessTokenGenerateForAppRequest {
        refresh_token: current_token,
        steamid: steam_id64 as u64,
        renewal_type: crate::pb::cauthentication::EAuthTokenRenewalType::Allow,
    }
    .serialize();
    let job_id = runtime.next_job_id();
    let (tx, rx) = mpsc::channel();
    runtime.track_job(
        job_id,
        move |job| {
            let token = if !job.synthetic_failure && job.eresult == 1 {
                crate::pb::cauthentication::AccessTokenGenerateForAppResponse::deserialize(
                    &job.body,
                )
                .map(|resp| resp.refresh_token)
                .unwrap_or_default()
            } else {
                String::new()
            };
            let _ = tx.send(token);
        },
        Some(Duration::from_millis(timeout_ms.max(1) as u64)),
    );
    let wire = runtime.core().build_service_method_call(
        "Authentication.GenerateAccessTokenForApp#1",
        true,
        job_id,
        &request,
    );
    if !runtime.core().enqueue_wire(wire) {
        return ptr::null_mut();
    }
    runtime.flush_outbound();

    let wait = Duration::from_millis(timeout_ms.max(0) as u64);
    let Ok(token) = rx.recv_timeout(wait) else {
        return ptr::null_mut();
    };
    if token.is_empty() {
        return ptr::null_mut();
    }
    new_string_or_null(&mut env, &token)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeStartLoginWithCredentials(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    username: JString,
    password: JString,
    persistent_session: jboolean,
    authenticator: JObject,
    callback: JObject,
) {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        call_auth_result_failure(&mut env, &callback, 2, "session closed");
        return;
    };
    let Some(runtime) = handle.connected_runtime() else {
        call_auth_result_failure(&mut env, &callback, 2, "not connected");
        return;
    };
    let Some(username) = jstring_to_string(&mut env, &username) else {
        call_auth_result_failure(&mut env, &callback, 2, "missing username");
        return;
    };
    let Some(password) = jstring_to_string(&mut env, &password) else {
        call_auth_result_failure(&mut env, &callback, 2, "missing password");
        return;
    };
    let Ok(callback) = env.new_global_ref(callback) else {
        clear_pending_exception(&mut env);
        return;
    };
    let authenticator = if authenticator.is_null() {
        None
    } else {
        env.new_global_ref(authenticator).ok()
    };
    let cancel = handle.begin_login_cancel();
    thread::spawn(move || {
        let timeout = Duration::from_secs(30);
        let mut config = CredentialsAuthConfig {
            username,
            password,
            persistent_session: persistent_session != JNI_FALSE,
            ..Default::default()
        };
        let key_job = match request_service_method_job(
            &runtime,
            "Authentication.GetPasswordRSAPublicKey#1",
            false,
            build_password_rsa_request(&config).serialize(),
            timeout,
            Some(cancel.as_ref()),
        ) {
            Some(job) => job,
            None => {
                if cancel.load(Ordering::Relaxed) {
                    return;
                }
                dispatch_auth_result(
                    &callback,
                    AuthSessionResult {
                        error_message: "GetPasswordRSAPublicKey timed out".to_string(),
                        ..Default::default()
                    },
                );
                return;
            }
        };
        if key_job.synthetic_failure || key_job.eresult != 1 {
            dispatch_auth_result(
                &callback,
                auth_result_from_job(&key_job, "GetPasswordRSAPublicKey"),
            );
            return;
        }
        let Some(key) = crate::pb::cauthentication::GetPasswordRsaPublicKeyResponse::deserialize(
            &key_job.body,
        ) else {
            dispatch_auth_result(
                &callback,
                AuthSessionResult {
                    error_message: "GetPasswordRSAPublicKey parse failed".to_string(),
                    ..Default::default()
                },
            );
            return;
        };
        let begin_request = match build_credentials_begin_request(&mut config, &key) {
            Ok(request) => request,
            Err(result) => {
                dispatch_auth_result(&callback, result);
                return;
            }
        };
        let begin_job = match request_service_method_job(
            &runtime,
            "Authentication.BeginAuthSessionViaCredentials#1",
            false,
            begin_request.serialize(),
            timeout,
            Some(cancel.as_ref()),
        ) {
            Some(job) => job,
            None => {
                if cancel.load(Ordering::Relaxed) {
                    return;
                }
                dispatch_auth_result(
                    &callback,
                    AuthSessionResult {
                        error_message: "BeginAuthSessionViaCredentials timed out".to_string(),
                        ..Default::default()
                    },
                );
                return;
            }
        };
        if begin_job.synthetic_failure || begin_job.eresult != 1 {
            dispatch_auth_result(
                &callback,
                auth_result_from_job(&begin_job, "BeginAuthSessionViaCredentials"),
            );
            return;
        }
        let Some(begin_response) =
            crate::pb::cauthentication::BeginAuthSessionViaCredentialsResponse::deserialize(
                &begin_job.body,
            )
        else {
            dispatch_auth_result(
                &callback,
                AuthSessionResult {
                    error_message: "BeginAuthSessionViaCredentials parse failed".to_string(),
                    ..Default::default()
                },
            );
            return;
        };
        let pending = match pending_credentials_from_begin_response(begin_response) {
            Ok(pending) => pending,
            Err(result) => {
                dispatch_auth_result(&callback, result);
                return;
            }
        };
        let chosen_guard = choose_guard_confirmation(&pending.allowed_confirmations);
        match chosen_guard {
            crate::pb::cauthentication::EAuthSessionGuardType::DeviceConfirmation => {
                if let Some(authenticator) = authenticator.as_ref() {
                    // Mirrors C++: the boolean is informational. The phone tap is
                    // observed through the poll loop regardless of whether the UI
                    // dismissed the prompt.
                    let _ = authenticator_accept_device_confirmation(authenticator);
                }
            }
            crate::pb::cauthentication::EAuthSessionGuardType::DeviceCode
            | crate::pb::cauthentication::EAuthSessionGuardType::EmailCode => {
                let is_email = chosen_guard
                    == crate::pb::cauthentication::EAuthSessionGuardType::EmailCode;
                let Some(authenticator) = authenticator.as_ref() else {
                    dispatch_auth_result(
                        &callback,
                        AuthSessionResult {
                            error_message: if is_email {
                                "Steam Guard email code required".to_string()
                            } else {
                                "Steam Guard device code required".to_string()
                            },
                            ..Default::default()
                        },
                    );
                    return;
                };
                let email = pending
                    .allowed_confirmations
                    .iter()
                    .find(|confirmation| confirmation.confirmation_type == chosen_guard)
                    .map(|confirmation| confirmation.associated_message.clone())
                    .unwrap_or_default();
                // Like the desktop client / JavaSteam's IAuthenticator contract: a code Steam
                // rejects (88 TwoFactorCodeMismatch, 65 InvalidLoginAuthCode) re-prompts the user
                // with `previousCodeWasIncorrect = true` instead of failing the whole sign-in.
                let mut previous_incorrect = false;
                let mut attempts = 0u32;
                loop {
                    attempts += 1;
                    let code = if is_email {
                        authenticator_get_email_code(authenticator, &email, previous_incorrect)
                    } else {
                        authenticator_get_device_code(authenticator, previous_incorrect)
                    };
                    let Some(code) = code else {
                        if cancel.load(Ordering::Relaxed) {
                            return;
                        }
                        dispatch_auth_result(
                            &callback,
                            AuthSessionResult {
                                error_message: if is_email {
                                    "Steam Guard email code was not provided".to_string()
                                } else {
                                    "Steam Guard device code was not provided".to_string()
                                },
                                ..Default::default()
                            },
                        );
                        return;
                    };
                    let update = build_guard_code_request(
                        pending.client_id,
                        pending.steamid,
                        chosen_guard,
                        code,
                    );
                    let update_job = match request_service_method_job(
                        &runtime,
                        "Authentication.UpdateAuthSessionWithSteamGuardCode#1",
                        false,
                        update.serialize(),
                        timeout,
                        Some(cancel.as_ref()),
                    ) {
                        Some(job) => job,
                        None => {
                            if cancel.load(Ordering::Relaxed) {
                                return;
                            }
                            dispatch_auth_result(
                                &callback,
                                AuthSessionResult {
                                    error_message: "UpdateAuthSessionWithSteamGuardCode timed out"
                                        .to_string(),
                                    ..Default::default()
                                },
                            );
                            return;
                        }
                    };
                    // C++ accepts eresult 1 (OK) or 29 (DuplicateRequest — same code
                    // resubmitted) as a non-fatal "proceed to poll".
                    if crate::auth_session::guard_update_succeeded(&update_job) {
                        break;
                    }
                    if !update_job.synthetic_failure
                        && matches!(update_job.eresult, 65 | 88)
                        && attempts < GUARD_CODE_MAX_ATTEMPTS
                    {
                        android_log(
                            "LUDASHI_STEAM_AUTH",
                            &format!(
                                "steam guard code rejected (eresult={}) — re-prompting (attempt {})",
                                update_job.eresult, attempts
                            ),
                        );
                        previous_incorrect = true;
                        continue;
                    }
                    dispatch_auth_result(
                        &callback,
                        auth_result_from_job(&update_job, "UpdateAuthSessionWithSteamGuardCode"),
                    );
                    return;
                }
            }
            _ => {}
        }

        let poll_interval = Duration::from_secs_f32(pending.poll_interval_seconds.max(0.25));
        let mut client_id = pending.client_id;
        let fallback_account_name = config.username.clone();
        loop {
            for slice in sleep_slices(poll_interval, Duration::from_millis(100)) {
                if cancel.load(Ordering::Relaxed) {
                    return;
                }
                thread::sleep(slice);
            }
            if cancel.load(Ordering::Relaxed) {
                return;
            }
            let poll_job = match request_service_method_job(
                &runtime,
                "Authentication.PollAuthSessionStatus#1",
                false,
                build_poll_request(client_id, pending.request_id.clone()).serialize(),
                timeout,
                Some(cancel.as_ref()),
            ) {
                Some(job) => job,
                None => {
                    if cancel.load(Ordering::Relaxed) {
                        return;
                    }
                    dispatch_auth_result(
                        &callback,
                        AuthSessionResult {
                            error_message: "PollAuthSessionStatus timed out".to_string(),
                            ..Default::default()
                        },
                    );
                    return;
                }
            };
            if poll_job.synthetic_failure || poll_job.eresult != 1 {
                dispatch_auth_result(
                    &callback,
                    auth_result_from_job(&poll_job, "PollAuthSessionStatus"),
                );
                return;
            }
            let Some(resp) = crate::pb::cauthentication::PollAuthSessionStatusResponse::deserialize(
                &poll_job.body,
            ) else {
                dispatch_auth_result(
                    &callback,
                    AuthSessionResult {
                        error_message: "Poll response parse failed".to_string(),
                        ..Default::default()
                    },
                );
                return;
            };
            if resp.new_client_id != 0 {
                client_id = resp.new_client_id;
            }
            if let Some(mut result) = auth_result_from_poll(resp, pending.steamid) {
                apply_account_name_fallback(&mut result, &fallback_account_name);
                dispatch_auth_result(&callback, result);
                return;
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeStartLoginWithQr(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    qr_callback: JObject,
    result_callback: JObject,
) {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        call_auth_result_failure(&mut env, &result_callback, 2, "session closed");
        return;
    };
    let Some(runtime) = handle.connected_runtime() else {
        call_auth_result_failure(
            &mut env,
            &result_callback,
            2,
            "Rust QR auth transport is not connected",
        );
        return;
    };
    let Ok(qr_callback) = env.new_global_ref(qr_callback) else {
        clear_pending_exception(&mut env);
        call_auth_result_failure(&mut env, &result_callback, 2, "QR callback unavailable");
        return;
    };
    let Ok(result_callback) = env.new_global_ref(result_callback) else {
        clear_pending_exception(&mut env);
        return;
    };
    let cancel = handle.begin_login_cancel();
    thread::spawn(move || {
        let timeout = Duration::from_secs(30);
        let request = build_qr_begin_request(&QrAuthConfig::default()).serialize();
        qr_log("BeginAuthSessionViaQR request queued");
        let begin_job = match request_service_method_job(
            &runtime,
            "Authentication.BeginAuthSessionViaQR#1",
            false,
            request,
            timeout,
            Some(cancel.as_ref()),
        ) {
            Some(job) => job,
            None => {
                if cancel.load(Ordering::Relaxed) {
                    qr_log("BeginAuthSessionViaQR cancelled before response");
                    return;
                }
                qr_log("BeginAuthSessionViaQR timed out");
                dispatch_auth_result(
                    &result_callback,
                    AuthSessionResult {
                        error_message: "BeginAuthSessionViaQR timed out".to_string(),
                        ..Default::default()
                    },
                );
                return;
            }
        };
        if begin_job.synthetic_failure || begin_job.eresult != 1 {
            qr_log(&format!(
                "BeginAuthSessionViaQR failed synthetic={} eresult={} body_len={}",
                begin_job.synthetic_failure,
                begin_job.eresult,
                begin_job.body.len()
            ));
            dispatch_auth_result(
                &result_callback,
                auth_result_from_job(&begin_job, "BeginAuthSessionViaQR"),
            );
            return;
        }
        let Some(resp) =
            crate::pb::cauthentication::BeginAuthSessionViaQrResponse::deserialize(&begin_job.body)
        else {
            qr_log(&format!(
                "BeginAuthSessionViaQR parse failed body_len={}",
                begin_job.body.len()
            ));
            dispatch_auth_result(
                &result_callback,
                AuthSessionResult {
                    error_message: "QR response parse failed".to_string(),
                    ..Default::default()
                },
            );
            return;
        };
        let pending = pending_qr_from_begin_response(resp);
        qr_log(&format!(
            "BeginAuthSessionViaQR response client_id={} request_id_len={} interval_s={:.2} challenge_len={}",
            pending.client_id,
            pending.request_id.len(),
            pending.poll_interval_seconds,
            pending.challenge_url.len()
        ));
        if pending.client_id == 0 || pending.request_id.is_empty() {
            qr_log("Steam rejected QR auth session");
            dispatch_auth_result(
                &result_callback,
                AuthSessionResult {
                    eresult: 5,
                    error_message: "Steam rejected the QR auth session".to_string(),
                    ..Default::default()
                },
            );
            return;
        }
        dispatch_qr_challenge(&qr_callback, &pending.challenge_url);
        start_qr_poll_loop(QrPollState {
            runtime,
            qr_callback,
            result_callback,
            cancel,
            client_id: pending.client_id,
            request_id: pending.request_id,
            poll_interval_seconds: pending.poll_interval_seconds,
            last_challenge_url: Arc::new(Mutex::new(pending.challenge_url)),
        });
    });
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeCancelLogin(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(handle) = unsafe { from_session_handle_mut(handle) } {
        handle.cancel_login();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeLogonWithRefreshToken(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    refresh_token: JString,
    account_name: JString,
    steam_id: jlong,
) -> jboolean {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return JNI_FALSE;
    };
    let Some(refresh_token) = jstring_to_string(&mut env, &refresh_token) else {
        return JNI_FALSE;
    };
    if refresh_token.is_empty() {
        return JNI_FALSE;
    }
    let account_name = jstring_to_string(&mut env, &account_name).unwrap_or_default();
    if handle.enqueue_proto(handle.core.build_logon_with_refresh_token(
        refresh_token,
        account_name,
        steam_id as u64,
    )) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativePrepareApp(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
    dlc_app_ids: JIntArray,
    callback: JObject,
) {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        call_prepare_result(&mut env, &callback, false, "session closed");
        return;
    };
    if callback.is_null() {
        return;
    }
    let Some(runtime) = handle.connected_runtime() else {
        call_prepare_result(&mut env, &callback, false, "not connected");
        return;
    };
    let dlc_app_ids = int_array_to_u32_vec(&env, &dlc_app_ids);
    let all_ids = CMClientCore::prepare_app_ids(app_id as u32, &dlc_app_ids);
    if all_ids.is_empty() {
        call_prepare_result(&mut env, &callback, true, "");
        return;
    }

    let missing_tokens = handle.core.prepare_app_missing_token_ids(&all_ids);
    if !missing_tokens.is_empty() {
        if let Some(body) = request_proto_body(&runtime, Duration::from_secs(30), |core, job_id| {
            core.build_pics_access_tokens(Vec::new(), missing_tokens, job_id)
        }) {
            if let Some(response) =
                crate::pb::cmsg_client_pics::CMsgClientPICSAccessTokenResponse::deserialize(&body)
            {
                handle.core.library().ingest_app_access_tokens(&response);
            }
        }
    }

    let apps = handle.core.prepare_app_pics_requests(&all_ids);
    let Some(response) =
        request_pics_product_info(&runtime, Vec::new(), apps, false, Duration::from_secs(30))
    else {
        call_prepare_result(&mut env, &callback, false, "PICS product info failed");
        return;
    };
    handle.core.library().ingest_app_pics_response(&response);

    for app_id in all_ids {
        let _ = request_app_ownership_ticket(&runtime, app_id, Duration::from_secs(30));
    }
    call_prepare_result(&mut env, &callback, true, "");
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeDownloadApp(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
    depot_ids: JIntArray,
    manifest_ids: JLongArray,
    branch: JString,
    install_dir: JString,
    fresh: jboolean,
    ca_bundle_path: JString,
    max_workers: jint,
    decompress_workers: jint,
    listener: JObject,
) {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        call_download_complete(&mut env, &listener, "session closed");
        return;
    };
    if listener.is_null() {
        return;
    }
    let Some(runtime) = handle.connected_runtime() else {
        call_download_complete(&mut env, &listener, "not connected");
        return;
    };
    let depot_ids = int_array_to_u32_vec(&env, &depot_ids);
    let manifest_ids = long_array_to_u64_vec(&env, &manifest_ids);
    if depot_ids.len() != manifest_ids.len() {
        call_download_complete(&mut env, &listener, "depot/manifest array length mismatch");
        return;
    }
    let specs = depot_ids
        .into_iter()
        .zip(manifest_ids)
        .map(
            |(depot_id, manifest_id)| crate::depot_downloader::DepotSpec {
                depot_id,
                manifest_id,
            },
        )
        .collect::<Vec<_>>();
    let install_dir = jstring_to_string(&mut env, &install_dir).unwrap_or_default();
    if crate::depot_downloader::validate_download_inputs(&install_dir, &specs).is_err() {
        call_download_complete(&mut env, &listener, "invalid download request");
        return;
    }
    let branch = jstring_to_string(&mut env, &branch).unwrap_or_else(|| "public".to_string());
    let ca_bundle_path = jstring_to_string(&mut env, &ca_bundle_path).unwrap_or_default();
    let Ok(listener) = env.new_global_ref(&listener) else {
        call_download_complete(&mut env, &listener, "listener ref failed");
        return;
    };
    let app_id = app_id.max(0) as u32;
    let fresh = fresh != JNI_FALSE;
    let max_workers = max_workers.max(1) as u32;
    let max_process_workers = decompress_workers.max(1) as u32;
    handle.download_cancel.store(false, Ordering::Relaxed);
    let download_cancel = Arc::clone(&handle.download_cancel);
    let (cdn_cell_id, cdn_prefer_dc) = handle.cdn_preference();

    thread::spawn(move || {
        let timeout = Duration::from_secs(30);
        if download_cancel.load(Ordering::Relaxed) {
            dispatch_download_complete(
                listener,
                crate::depot_downloader::DepotDownloadResult::fail("cancelled"),
            );
            return;
        }
        let Some(servers) = request_cdn_servers(&runtime, cdn_cell_id, &cdn_prefer_dc, timeout)
        else {
            dispatch_download_complete(
                listener.clone(),
                crate::depot_downloader::DepotDownloadResult::fail(
                    "download: CDN server request failed",
                ),
            );
            return;
        };
        let config_dir = crate::depot_downloader::config_dir_path(&install_dir);
        let installed_cfg = crate::depot_config::DepotConfigStore::load(&config_dir);
        let mut resolved = Vec::with_capacity(specs.len());
        let mut denied: Vec<u32> = Vec::new();
        for spec in specs {
            if download_cancel.load(Ordering::Relaxed) {
                dispatch_download_complete(
                    listener,
                    crate::depot_downloader::DepotDownloadResult::fail("cancelled"),
                );
                return;
            }
            // Already-installed depots need no key/code; the downloader's resume-skip handles them.
            if !fresh && installed_cfg.is_installed(spec.depot_id, spec.manifest_id) {
                resolved.push(crate::depot_downloader::ResolvedDepotSpec {
                    depot_id: spec.depot_id,
                    manifest_id: spec.manifest_id,
                    depot_key: Vec::new(),
                    manifest_request_code: 0,
                });
                continue;
            }
            let depot_key = match resolve_depot_key_with_retry(
                &runtime,
                app_id,
                spec.depot_id,
                timeout,
                download_cancel.as_ref(),
            ) {
                KeyResolution::Granted(key) => key,
                // Access-denied means this account is not entitled; skip that depot, keep the rest.
                KeyResolution::AccessDenied => {
                    denied.push(spec.depot_id);
                    continue;
                }
                KeyResolution::Cancelled => {
                    dispatch_download_complete(
                        listener,
                        crate::depot_downloader::DepotDownloadResult::fail("cancelled"),
                    );
                    return;
                }
                KeyResolution::Unavailable => {
                    dispatch_download_complete(
                        listener.clone(),
                        crate::depot_downloader::DepotDownloadResult::fail(format!(
                            "download: depot key unavailable for depot {}",
                            spec.depot_id
                        )),
                    );
                    return;
                }
            };
            let manifest_request_code = match resolve_manifest_code_with_retry(
                &runtime,
                app_id,
                spec.depot_id,
                spec.manifest_id,
                &branch,
                timeout,
                download_cancel.as_ref(),
            ) {
                CodeResolution::Code(code) => code,
                CodeResolution::AccessDenied => {
                    denied.push(spec.depot_id);
                    continue;
                }
                CodeResolution::Cancelled => {
                    dispatch_download_complete(
                        listener,
                        crate::depot_downloader::DepotDownloadResult::fail("cancelled"),
                    );
                    return;
                }
                CodeResolution::Unavailable => {
                    dispatch_download_complete(
                        listener.clone(),
                        crate::depot_downloader::DepotDownloadResult::fail(format!(
                            "download: manifest request code unavailable for depot {}",
                            spec.depot_id
                        )),
                    );
                    return;
                }
            };
            resolved.push(crate::depot_downloader::ResolvedDepotSpec {
                depot_id: spec.depot_id,
                manifest_id: spec.manifest_id,
                depot_key,
                manifest_request_code,
            });
        }
        write_denied_depots_marker(&config_dir, &denied);
        if resolved.is_empty() {
            dispatch_download_complete(
                listener,
                crate::depot_downloader::DepotDownloadResult::fail(if denied.is_empty() {
                    "download: no depots".to_string()
                } else {
                    "download: no entitled depots (all depot keys denied)".to_string()
                }),
            );
            return;
        }
        let progress_listener = listener.clone();
        let progress = |progress: &crate::depot_downloader::DepotDownloadProgress| {
            dispatch_download_progress(&progress_listener, progress);
        };
        let progress_cb: crate::depot_downloader::DepotProgressCallback = &progress;
        // Re-resolve manifest request codes right before each depot's manifest fetch.
        let code_refresher = |depot_id: u32, manifest_id: u64| -> Option<u64> {
            match resolve_manifest_code_with_retry(
                &runtime,
                app_id,
                depot_id,
                manifest_id,
                &branch,
                timeout,
                download_cancel.as_ref(),
            ) {
                CodeResolution::Code(code) => Some(code),
                _ => None,
            }
        };
        let code_refresher_cb: crate::depot_downloader::ManifestCodeRefresher = &code_refresher;
        // Engine-side download diagnostics (throughput lines, fallocate fallback) → logcat.
        let download_log = |msg: &str| android_log("LUDASHI_STEAM_DL", msg);
        let download_log_cb: crate::depot_writer::DepotLogCallback = &download_log;
        let result =
            crate::depot_downloader::download_resolved_depots_with_cancel_progress(
                &install_dir,
                &resolved,
                &servers,
                &ca_bundle_path,
                fresh,
                max_workers,
                max_process_workers,
                Some(download_cancel.as_ref()),
                Some(progress_cb),
                Some(code_refresher_cb),
                Some(download_log_cb),
            );
        dispatch_download_complete(listener, result);
    });
}

fn dispatch_download_progress(
    listener: &GlobalRef,
    progress: &crate::depot_downloader::DepotDownloadProgress,
) {
    let Some(vm) = JVM.get() else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
        return;
    };
    let _ = env.call_method(
        listener.as_obj(),
        "onProgress",
        "(IJJIIZ)V",
        &[
            JValue::Int(progress.depot_id as jint),
            JValue::Long(progress.depot_done as jlong),
            JValue::Long(progress.depot_total as jlong),
            JValue::Int(progress.depots_done as jint),
            JValue::Int(progress.depots_total as jint),
            JValue::Bool(if progress.verifying { JNI_TRUE } else { JNI_FALSE }),
        ],
    );
    clear_pending_exception(&mut env);
}

/// `ClientCheckAppBetaPassword` for a password-protected beta branch. Returns JSON
/// `{"eresult":n,"betas":{"<branch>":"<hex AES-256 key>"}}` (`eresult` 0 = no reply); the key
/// decrypts `depots/<id>/encryptedmanifests/<branch>/gid` (AES-256-ECB, PKCS7) into the manifest id.
/// The password itself is never logged.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeCheckAppBetaPassword(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
    password: JString,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let Some(password) = jstring_to_string(&mut env, &password) else {
        return ptr::null_mut();
    };
    let app_id = app_id.max(0) as u32;
    let Some((header_eresult, body)) =
        request_proto_response(&runtime, Duration::from_secs(30), |core, job_id| {
            core.build_check_app_beta_password(app_id, &password, job_id)
        })
    else {
        return ptr::null_mut();
    };
    let (eresult, betas) =
        match crate::pb::cmsg_client_check_app_beta_password::CMsgClientCheckAppBetaPasswordResponse::deserialize(&body) {
            Some(response) => {
                let mut map = serde_json::Map::new();
                for beta in response.betapasswords {
                    if !beta.betaname.is_empty() {
                        map.insert(beta.betaname, json!(beta.betapassword));
                    }
                }
                (response.eresult, serde_json::Value::Object(map))
            }
            None => (header_eresult, json!({})),
        };
    android_log(
        "LUDASHI_STEAM_DL",
        &format!(
            "check beta password app={app_id}: eresult={eresult} branches={}",
            betas.as_object().map(|m| m.len()).unwrap_or(0)
        ),
    );
    let value = json!({ "eresult": eresult, "betas": betas }).to_string();
    new_string_or_null(&mut env, &value)
}

/// Metadata-only manifest fetch for one depot (no chunk data, no depot key): the manifest's
/// declared totals + a block-rounded on-disk estimate over its regular files. Returns JSON
/// `{"uncompressed":u64,"compressed":u64,"disk":u64,"files":n}` or null (not logged on, request
/// code refused, CDN failure). Blocking; call off the main thread.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeFetchManifestSizes(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
    depot_id: jint,
    manifest_id: jlong,
    branch: JString,
    ca_bundle_path: JString,
) -> jstring {
    const BLOCK_BYTES: u64 = 4096;
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let app_id = app_id.max(0) as u32;
    let depot_id = depot_id.max(0) as u32;
    let manifest_id = manifest_id as u64;
    if app_id == 0 || depot_id == 0 || manifest_id == 0 {
        return ptr::null_mut();
    }
    let branch = jstring_to_string(&mut env, &branch).unwrap_or_else(|| "public".to_string());
    let ca_bundle_path = jstring_to_string(&mut env, &ca_bundle_path).unwrap_or_default();
    let timeout = Duration::from_secs(30);
    let (cdn_cell_id, cdn_prefer_dc) = handle.cdn_preference();
    let Some(servers) = request_cdn_servers(&runtime, cdn_cell_id, &cdn_prefer_dc, timeout) else {
        return ptr::null_mut();
    };
    let servers = crate::depot_downloader::filter_usable_cdn_servers(servers);
    let cancel = AtomicBool::new(false);
    let request_code = match resolve_manifest_code_with_retry(
        &runtime, app_id, depot_id, manifest_id, &branch, timeout, &cancel,
    ) {
        CodeResolution::Code(code) => code,
        _ => return ptr::null_mut(),
    };
    let cdn = crate::cdn_client::CdnClient::new(ca_bundle_path);
    let fetched = crate::depot_downloader::fetch_manifest_with_retry(
        &cdn,
        &servers,
        depot_id,
        manifest_id,
        request_code,
        "",
        crate::cdn_client::CdnClient::default_timeout(),
    );
    if !fetched.ok() {
        android_log(
            "LUDASHI_STEAM_DL",
            &format!("manifest sizes depot={depot_id}: fetch failed ({})", fetched.error),
        );
        return ptr::null_mut();
    }
    let Some(manifest) = crate::content_manifest::ContentManifest::parse(&fetched.raw_manifest)
    else {
        return ptr::null_mut();
    };
    // Same rule as the JavaSteam resolver: symlinks and empty entries occupy no data blocks;
    // degrade to the raw uncompressed total when the file list carries no sizes.
    let mut disk = 0u64;
    for file in &manifest.files {
        if !file.linktarget.is_empty() || file.size == 0 {
            continue;
        }
        disk += file.size.div_ceil(BLOCK_BYTES) * BLOCK_BYTES;
    }
    let uncompressed = manifest.metadata.cb_disk_original;
    if disk == 0 {
        disk = uncompressed;
    }
    let value = json!({
        "uncompressed": uncompressed,
        "compressed": manifest.metadata.cb_disk_compressed,
        "disk": disk,
        "files": manifest.files.len(),
    })
    .to_string();
    new_string_or_null(&mut env, &value)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeSetCdnPreference(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    cell_id: jint,
    prefer_dc: JString,
) {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return;
    };
    let prefer_dc = jstring_to_string(&mut env, &prefer_dc).unwrap_or_default();
    let cell_id = cell_id.max(0) as u32;
    if let Ok(mut guard) = handle.cdn_preference.lock() {
        *guard = (cell_id, prefer_dc.trim().to_ascii_lowercase());
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeCancelDownload(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(handle) = unsafe { from_session_handle_mut(handle) } {
        handle.download_cancel.store(true, Ordering::Relaxed);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeRequestEncryptedAppTicket(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
) -> jbyteArray {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    if app_id <= 0 || handle.core.state() != ClientState::LoggedOn {
        return ptr::null_mut();
    }
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };

    let app_id = app_id as u32;
    let job_id = runtime.next_job_id();
    let Some(message) = runtime
        .core()
        .build_request_encrypted_app_ticket(app_id, job_id)
    else {
        return ptr::null_mut();
    };

    let (tx, rx) = mpsc::channel();
    runtime.track_job(
        job_id,
        move |job| {
            let ticket = if job.synthetic_failure {
                Vec::new()
            } else {
                crate::pb::cmsg_client_request_encrypted_app_ticket::CMsgClientRequestEncryptedAppTicketResponse::deserialize(&job.body)
                    .map(|response| response.encrypted_app_ticket)
                    .unwrap_or_default()
            };
            let _ = tx.send(ticket);
        },
        Some(Duration::from_secs(30)),
    );
    if !runtime.core().enqueue_wire(message.wire) {
        return ptr::null_mut();
    }
    runtime.flush_outbound();

    let Ok(ticket) = rx.recv_timeout(Duration::from_secs(30)) else {
        return ptr::null_mut();
    };
    if ticket.is_empty() {
        return ptr::null_mut();
    }
    byte_array_or_null(&env, &ticket)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetUserStatsSchema(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
) -> jbyteArray {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let Some(response) =
        request_user_stats_response(&runtime, app_id.max(0) as u32, Duration::from_secs(30))
    else {
        return ptr::null_mut();
    };
    if response.schema.is_empty() {
        return ptr::null_mut();
    }
    byte_array_or_null(&env, &response.schema)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetUserStatsFull(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let Some(response) =
        request_user_stats_response(&runtime, app_id.max(0) as u32, Duration::from_secs(30))
    else {
        return ptr::null_mut();
    };
    let value = json!({
        "eresult": response.eresult,
        "crcStats": response.crc_stats,
        "schema": crate::cdn_client::hex_encode(&response.schema),
        "achievementBlocks": response.achievement_blocks.iter().map(|block| json!({
            "achievementId": block.achievement_id,
            "unlockTimes": block.unlock_time,
        })).collect::<Vec<_>>(),
    })
    .to_string();
    new_string_or_null(&mut env, &value)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetUserStatsJson(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let Some(response) =
        request_user_stats_response(&runtime, app_id.max(0) as u32, Duration::from_secs(8))
    else {
        return ptr::null_mut();
    };
    let schema = if response.schema.is_empty() {
        serde_json::Value::Null
    } else {
        crate::vdf::parse_binary(&response.schema)
            .map(|root| kvnode_to_json_value(&root))
            .unwrap_or(serde_json::Value::Null)
    };
    let value = json!({
        "eresult": response.eresult,
        "crcStats": response.crc_stats,
        "schema": schema,
        "stats": response.stats.iter().map(|stat| json!({
            "id": stat.stat_id,
            "value": stat.stat_value,
        })).collect::<Vec<_>>(),
        "achievementBlocks": response.achievement_blocks.iter().map(|block| json!({
            "achievementId": block.achievement_id,
            "unlockTimes": block.unlock_time,
        })).collect::<Vec<_>>(),
    })
    .to_string();
    new_string_or_null(&mut env, &value)
}

/// Blocking `ClientStoreUserStats2` awaiting `ClientStoreUserStatsResponse`. Returns the EResult
/// (1 = OK), 0 when no response arrived in `timeout_ms` (or the session is not logged on). Stat ids
/// the server refused are logged under `LUDASHI_STEAM_ACHV`.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeStoreUserStatsBlocking(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
    steam_id: jlong,
    crc_stats: jint,
    stat_ids: JIntArray,
    stat_values: JIntArray,
    timeout_ms: jint,
) -> jint {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return 0;
    };
    let Some(runtime) = handle.connected_runtime() else {
        return 0;
    };
    let len = env
        .get_array_length(&stat_ids)
        .unwrap_or(0)
        .min(env.get_array_length(&stat_values).unwrap_or(0));
    if len < 0 {
        return 0;
    }
    let mut ids = vec![0i32; len as usize];
    let mut values = vec![0i32; len as usize];
    if env.get_int_array_region(&stat_ids, 0, &mut ids).is_err()
        || env
            .get_int_array_region(&stat_values, 0, &mut values)
            .is_err()
    {
        return 0;
    }
    let stats = ids
        .into_iter()
        .zip(values)
        .map(|(id, value)| (id as u32, value as u32))
        .collect::<Vec<_>>();
    let app_id = app_id.max(0) as u32;
    let timeout = Duration::from_millis(timeout_ms.max(1_000) as u64);
    let Some((header_eresult, body)) = request_proto_response(&runtime, timeout, |core, job_id| {
        core.build_store_user_stats_job(app_id, steam_id as u64, crc_stats as u32, &stats, job_id)
    }) else {
        return 0;
    };
    match crate::pb::cmsg_client_store_user_stats::CMsgClientStoreUserStatsResponse::deserialize(&body)
    {
        Some(response) => {
            if !response.stats_failed_to_set.is_empty() {
                android_log(
                    "LUDASHI_STEAM_ACHV",
                    &format!(
                        "store user stats app={app_id}: eresult={} refused stat ids {:?}",
                        response.eresult,
                        response
                            .stats_failed_to_set
                            .iter()
                            .map(|s| s.stat_id)
                            .collect::<Vec<_>>()
                    ),
                );
            }
            response.confirmed_result() as jint
        }
        None => header_eresult.max(0) as jint,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetItemDefArchive(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
    ca_bundle_path: JString,
) -> jbyteArray {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let app_id = app_id.max(0) as u32;
    let Some(digest) = request_item_def_digest(&runtime, app_id, Duration::from_secs(30)) else {
        return ptr::null_mut();
    };
    let ca_bundle_path = jstring_to_string(&mut env, &ca_bundle_path).unwrap_or_default();
    let Some(body) = crate::cdn_client::CdnClient::new(ca_bundle_path).fetch_item_def_archive(
        app_id,
        &digest,
        Duration::from_secs(30),
    ) else {
        return ptr::null_mut();
    };
    byte_array_or_null(&env, &body)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetSubscribedWorkshopItems(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let app_id = app_id.max(0) as u32;
    const PAGE_SIZE: u32 = 100;
    const MAX_PAGES: u32 = 50;
    let mut total = 0u32;
    let mut all = Vec::new();
    for page in 1..=MAX_PAGES {
        let Some(response) = request_subscribed_workshop_page(
            &runtime,
            app_id,
            page,
            PAGE_SIZE,
            Duration::from_secs(30),
        ) else {
            return ptr::null_mut();
        };
        if page == 1 {
            total = response.total;
        }
        let count = response.publishedfiledetails.len();
        all.extend(response.publishedfiledetails);
        if count == 0 || (total != 0 && all.len() >= total as usize) {
            break;
        }
    }
    let value = json!(all
        .iter()
        .map(|detail| json!({
            "publishedFileId": detail.publishedfileid,
            "appId": if detail.consumer_appid != 0 { detail.consumer_appid } else { app_id },
            "title": detail.title,
            "fileName": detail.filename,
            "fileUrl": detail.file_url,
            "previewUrl": detail.preview_url,
            "fileSizeBytes": detail.file_size,
            "hcontentFile": detail.hcontent_file,
            "timeUpdated": detail.time_updated,
        }))
        .collect::<Vec<_>>())
    .to_string();
    new_string_or_null(&mut env, &value)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeDownloadWorkshopItem(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
    manifest_id: jlong,
    install_dir: JString,
    ca_bundle_path: JString,
    max_workers: jint,
    decompress_workers: jint,
) -> jlong {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return -1;
    };
    let Some(runtime) = handle.connected_runtime() else {
        return -1;
    };
    let install_dir = jstring_to_string(&mut env, &install_dir).unwrap_or_default();
    if install_dir.is_empty() || app_id <= 0 || manifest_id <= 0 {
        return -1;
    }
    let ca_bundle_path = jstring_to_string(&mut env, &ca_bundle_path).unwrap_or_default();
    let app_id = app_id as u32;
    let manifest_id = manifest_id as u64;
    let timeout = Duration::from_secs(30);
    let (cdn_cell_id, cdn_prefer_dc) = handle.cdn_preference();
    let Some(servers) = request_cdn_servers(&runtime, cdn_cell_id, &cdn_prefer_dc, timeout) else {
        return -1;
    };
    let DepotKeyOutcome::Granted(depot_key) =
        request_depot_key(&runtime, app_id, app_id, timeout)
    else {
        return -1;
    };
    let ManifestCodeOutcome::Code(manifest_request_code) =
        request_manifest_request_code(&runtime, app_id, app_id, manifest_id, "public", timeout)
    else {
        return -1;
    };
    let spec = crate::depot_downloader::ResolvedDepotSpec {
        depot_id: app_id,
        manifest_id,
        depot_key,
        manifest_request_code,
    };
    let result = crate::depot_downloader::download_resolved_depots(
        &install_dir,
        &[spec],
        &servers,
        &ca_bundle_path,
        true,
        max_workers.max(1) as u32,
        decompress_workers.max(1) as u32,
    );
    if result.success {
        result.bytes_written as jlong
    } else {
        -1
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetCloudFileList(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let app_id = app_id.max(0) as u32;
    let Some(body) =
        request_authed_service_body(&runtime, Duration::from_secs(30), |core, job_id| {
            core.build_cloud_app_file_changelist_call(app_id, 0, job_id)
        })
    else {
        return ptr::null_mut();
    };
    let Some(response) = crate::pb::ccloud::CCloudGetAppFileChangelistResponse::deserialize(&body)
    else {
        return ptr::null_mut();
    };
    let value = json!({
        "currentChangeNumber": response.current_change_number,
        "pathPrefixes": response.path_prefixes,
        "machineNames": response.machine_names,
        "files": response.files.iter().map(|file| json!({
            "fileName": file.file_name,
            "sha": crate::cdn_client::hex_encode(&file.sha_file),
            "timestamp": file.time_stamp,
            "size": file.raw_file_size,
            "persistState": file.persist_state,
            "pathPrefixIndex": file.path_prefix_index,
            "machineNameIndex": file.machine_name_index,
        })).collect::<Vec<_>>(),
    })
    .to_string();
    new_string_or_null(&mut env, &value)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetCloudUserQuota(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlongArray {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let Some(body) =
        request_authed_service_body(&runtime, Duration::from_secs(30), |core, job_id| {
            core.build_cloud_user_quota_call(job_id)
        })
    else {
        return ptr::null_mut();
    };
    let Some(response) = crate::pb::ccloud::CCloudGetUserQuotaResponse::deserialize(&body) else {
        return ptr::null_mut();
    };
    let Ok(array) = env.new_long_array(2) else {
        return ptr::null_mut();
    };
    let values = [response.total_bytes as jlong, response.used_bytes as jlong];
    if env.set_long_array_region(&array, 0, &values).is_err() {
        return ptr::null_mut();
    }
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetCloudDownloadInfo(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
    filename: JString,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let app_id = app_id.max(0) as u32;
    let Some(filename) = jstring_to_string(&mut env, &filename) else {
        return ptr::null_mut();
    };
    let Some(body) =
        request_authed_service_body(&runtime, Duration::from_secs(30), |core, job_id| {
            core.build_cloud_file_download_info_call(app_id, filename, job_id)
        })
    else {
        return ptr::null_mut();
    };
    let Some(response) = crate::pb::ccloud::CCloudClientFileDownloadResponse::deserialize(&body)
    else {
        return ptr::null_mut();
    };
    if response.url_host.is_empty() {
        return ptr::null_mut();
    }
    let value = json!({
        "fileSize": response.file_size,
        "rawFileSize": response.raw_file_size,
        "sha": crate::cdn_client::hex_encode(&response.sha_file),
        "timestamp": response.time_stamp,
        "urlHost": response.url_host,
        "urlPath": response.url_path,
        "useHttps": response.use_https,
        "encrypted": response.encrypted,
        "headers": response.request_headers.iter().map(|header| json!({
            "name": header.name,
            "value": header.value,
        })).collect::<Vec<_>>(),
    })
    .to_string();
    new_string_or_null(&mut env, &value)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeCloudBeginUploadBatch(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
    files: JString,
    files_to_delete: JString,
    client_id: jlong,
    machine_name: JString,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let app_id = app_id.max(0) as u32;
    let files = split_nonempty_lines(&jstring_to_string(&mut env, &files).unwrap_or_default());
    let files_to_delete =
        split_nonempty_lines(&jstring_to_string(&mut env, &files_to_delete).unwrap_or_default());
    // The batch's machine name shows in Steam's cloud conflict UI ("saved on <machine>"); the
    // JavaSteam path sends the same "Ludashi (<model>)" label.
    let machine_name = jstring_to_string(&mut env, &machine_name).unwrap_or_default();
    let Some(body) =
        request_authed_service_body(&runtime, Duration::from_secs(30), |core, job_id| {
            core.build_cloud_begin_app_upload_batch_call(
                app_id,
                machine_name,
                files,
                files_to_delete,
                client_id as u64,
                job_id,
            )
        })
    else {
        return ptr::null_mut();
    };
    let Some(response) = crate::pb::ccloud::CCloudBeginAppUploadBatchResponse::deserialize(&body)
    else {
        return ptr::null_mut();
    };
    let value = json!({
        "batchId": response.batch_id,
        "appChangeNumber": response.app_change_number,
    })
    .to_string();
    new_string_or_null(&mut env, &value)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeCloudBeginFileUpload(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
    filename: JString,
    file_size: jint,
    raw_file_size: jint,
    sha_hex: JString,
    timestamp: jlong,
    batch_id: jlong,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let Some(filename) = jstring_to_string(&mut env, &filename) else {
        return ptr::null_mut();
    };
    let Some(sha_hex) = jstring_to_string(&mut env, &sha_hex) else {
        return ptr::null_mut();
    };
    let Some(file_sha) = decode_hex(&sha_hex) else {
        return ptr::null_mut();
    };
    let app_id = app_id.max(0) as u32;
    let Some(body) =
        request_authed_service_body(&runtime, Duration::from_secs(30), |core, job_id| {
            core.build_cloud_begin_file_upload_call(
                app_id,
                filename,
                file_size as u32,
                raw_file_size as u32,
                file_sha,
                timestamp as u64,
                batch_id as u64,
                job_id,
            )
        })
    else {
        return ptr::null_mut();
    };
    let Some(response) = crate::pb::ccloud::CCloudClientBeginFileUploadResponse::deserialize(&body)
    else {
        return ptr::null_mut();
    };
    let value = json!({
        "encryptFile": response.encrypt_file,
        "blocks": response.block_requests.iter().map(|block| json!({
            "urlHost": block.url_host,
            "urlPath": block.url_path,
            "useHttps": block.use_https,
            "httpMethod": block.http_method,
            "blockOffset": block.block_offset,
            "blockLength": block.block_length,
            "mayParallelize": block.may_parallelize,
            "headers": block.request_headers.iter().map(|header| json!({
                "name": header.name,
                "value": header.value,
            })).collect::<Vec<_>>(),
        })).collect::<Vec<_>>(),
    })
    .to_string();
    new_string_or_null(&mut env, &value)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeCloudCommitFileUpload(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    transfer_succeeded: jboolean,
    app_id: jint,
    sha_hex: JString,
    filename: JString,
) -> jboolean {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return JNI_FALSE;
    };
    let Some(runtime) = handle.connected_runtime() else {
        return JNI_FALSE;
    };
    let Some(sha_hex) = jstring_to_string(&mut env, &sha_hex) else {
        return JNI_FALSE;
    };
    let Some(filename) = jstring_to_string(&mut env, &filename) else {
        return JNI_FALSE;
    };
    let Some(file_sha) = decode_hex(&sha_hex) else {
        return JNI_FALSE;
    };
    let app_id = app_id.max(0) as u32;
    let Some(body) =
        request_authed_service_body(&runtime, Duration::from_secs(30), |core, job_id| {
            core.build_cloud_commit_file_upload_call(
                transfer_succeeded != JNI_FALSE,
                app_id,
                file_sha,
                filename,
                job_id,
            )
        })
    else {
        return JNI_FALSE;
    };
    if crate::pb::ccloud::CCloudClientCommitFileUploadResponse::deserialize(&body)
        .map(|response| response.file_committed)
        .unwrap_or(false)
    {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeCloudCompleteUploadBatch(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
    batch_id: jlong,
    batch_eresult: jint,
) -> jboolean {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return JNI_FALSE;
    };
    let Some(runtime) = handle.connected_runtime() else {
        return JNI_FALSE;
    };
    if request_authed_service_success(&runtime, Duration::from_secs(30), |core, job_id| {
        core.build_cloud_complete_app_upload_batch_call(
            app_id.max(0) as u32,
            batch_id as u64,
            batch_eresult as u32,
            job_id,
        )
    }) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetPicsChangesSince(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    since_change_number: jlong,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let Some(body) = request_proto_body(&runtime, Duration::from_secs(30), |core, job_id| {
        core.build_pics_changes_since(since_change_number as u32, job_id)
    }) else {
        return ptr::null_mut();
    };
    let Some(response) =
        crate::pb::cmsg_client_pics::CMsgClientPICSChangesSinceResponse::deserialize(&body)
    else {
        return ptr::null_mut();
    };
    let value = json!({
        "currentChangeNumber": response.current_change_number,
        "forceFullUpdate": response.force_full_update,
        "apps": response.app_changes.iter().map(|app| json!({
            "appid": app.appid,
            "changeNumber": app.change_number,
            "needsToken": app.needs_token,
        })).collect::<Vec<_>>(),
        "packages": response.package_changes.iter().map(|package| json!({
            "packageid": package.packageid,
            "changeNumber": package.change_number,
            "needsToken": package.needs_token,
        })).collect::<Vec<_>>(),
    })
    .to_string();
    new_string_or_null(&mut env, &value)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetPicsAppInfo(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
    access_token: jlong,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let app_id = app_id.max(0) as u32;
    let app = crate::pb::cmsg_client_pics::PicsAppInfoReq {
        appid: app_id,
        access_token: access_token as u64,
        only_public_obsolete: false,
    };
    let Some(response) = request_pics_product_info(
        &runtime,
        Vec::new(),
        vec![app],
        false,
        Duration::from_secs(30),
    ) else {
        return ptr::null_mut();
    };
    for app in response.apps {
        if app.appid != app_id || app.buffer.is_empty() {
            continue;
        }
        let Some(root) = crate::vdf::parse_auto(&app.buffer) else {
            return ptr::null_mut();
        };
        let appinfo = if root.name.eq_ignore_ascii_case("appinfo") {
            &root
        } else {
            root.child("appinfo").unwrap_or(&root)
        };
        let value = json!({
            "changeNumber": app.change_number,
            "appinfo": kvnode_to_json_value(appinfo),
        })
        .to_string();
        return new_string_or_null(&mut env, &value);
    }
    ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetPicsAccessTokens(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_ids: JString,
    package_ids: JString,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let app_ids = parse_u32_lines(&jstring_to_string(&mut env, &app_ids).unwrap_or_default());
    let package_ids =
        parse_u32_lines(&jstring_to_string(&mut env, &package_ids).unwrap_or_default());
    let Some(body) = request_proto_body(&runtime, Duration::from_secs(30), |core, job_id| {
        core.build_pics_access_tokens(package_ids, app_ids, job_id)
    }) else {
        return ptr::null_mut();
    };
    let Some(response) =
        crate::pb::cmsg_client_pics::CMsgClientPICSAccessTokenResponse::deserialize(&body)
    else {
        return ptr::null_mut();
    };
    let app_tokens = response
        .app_access_tokens
        .iter()
        .map(|token| {
            (
                token.appid.to_string(),
                json!(token.access_token.to_string()),
            )
        })
        .collect::<serde_json::Map<_, _>>();
    let package_tokens = response
        .package_access_tokens
        .iter()
        .map(|token| {
            (
                token.packageid.to_string(),
                json!(token.access_token.to_string()),
            )
        })
        .collect::<serde_json::Map<_, _>>();
    let value = json!({
        "appTokens": app_tokens,
        "packageTokens": package_tokens,
    })
    .to_string();
    new_string_or_null(&mut env, &value)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetPicsAppProductInfo(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_ids: JString,
    tokens: JString,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let app_ids = parse_u32_lines(&jstring_to_string(&mut env, &app_ids).unwrap_or_default());
    let tokens = parse_u64_lines(&jstring_to_string(&mut env, &tokens).unwrap_or_default());
    let apps = app_ids
        .into_iter()
        .enumerate()
        .map(
            |(index, appid)| crate::pb::cmsg_client_pics::PicsAppInfoReq {
                appid,
                access_token: tokens.get(index).copied().unwrap_or_default(),
                only_public_obsolete: false,
            },
        )
        .collect::<Vec<_>>();
    let Some(response) =
        request_pics_product_info(&runtime, Vec::new(), apps, false, Duration::from_secs(30))
    else {
        return ptr::null_mut();
    };
    handle.core.library().ingest_app_pics_response(&response);
    let mut apps = Vec::new();
    for app in response.apps {
        if app.buffer.is_empty() {
            continue;
        }
        let Some(root) = crate::vdf::parse_auto(&app.buffer) else {
            continue;
        };
        let appinfo = if root.name.eq_ignore_ascii_case("appinfo") {
            &root
        } else {
            root.child("appinfo").unwrap_or(&root)
        };
        apps.push(json!({
            "appid": app.appid,
            "changeNumber": app.change_number,
            "appinfo": kvnode_to_json_value(appinfo),
        }));
    }
    new_string_or_null(&mut env, &json!(apps).to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetPicsPackageInfo(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    package_ids: JString,
    tokens: JString,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let package_ids =
        parse_u32_lines(&jstring_to_string(&mut env, &package_ids).unwrap_or_default());
    let tokens = parse_u64_lines(&jstring_to_string(&mut env, &tokens).unwrap_or_default());
    let packages = package_ids
        .into_iter()
        .enumerate()
        .map(
            |(index, packageid)| crate::pb::cmsg_client_pics::PicsPackageInfoReq {
                packageid,
                access_token: tokens.get(index).copied().unwrap_or_default(),
            },
        )
        .collect::<Vec<_>>();
    let Some(response) = request_pics_product_info(
        &runtime,
        packages,
        Vec::new(),
        false,
        Duration::from_secs(30),
    ) else {
        return ptr::null_mut();
    };
    handle.core.library().ingest_package_pics_response(&response);
    let mut packages = Vec::new();
    for package in response.packages {
        if package.buffer.is_empty() {
            continue;
        }
        let Some((_package_id, root)) = crate::vdf::parse_binary_package(&package.buffer) else {
            continue;
        };
        let appids = root
            .child("appids")
            .map(|node| {
                node.children
                    .iter()
                    .map(|child| child.as_uint(0))
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();
        let depotids = root
            .child("depotids")
            .map(|node| {
                node.children
                    .iter()
                    .map(|child| child.as_uint(0))
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();
        packages.push(json!({
            "packageid": package.packageid,
            "changeNumber": package.change_number,
            "appids": appids,
            "depotids": depotids,
        }));
    }
    new_string_or_null(&mut env, &json!(packages).to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetFamilyGroup(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    family_group_id: jlong,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let Some(body) =
        request_authed_service_body(&runtime, Duration::from_secs(30), |core, job_id| {
            core.build_family_group_call(family_group_id as u64, job_id)
        })
    else {
        return ptr::null_mut();
    };
    let Some(response) =
        crate::pb::cfamilygroups::CFamilyGroupsGetFamilyGroupResponse::deserialize(&body)
    else {
        return ptr::null_mut();
    };
    let value = json!({
        "name": response.name,
        "members": response.members.iter().map(|member| member.steamid).collect::<Vec<_>>(),
    })
    .to_string();
    new_string_or_null(&mut env, &value)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetOwnedGames(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    steam_id: jlong,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let Some(body) =
        request_authed_service_body(&runtime, Duration::from_secs(8), |core, job_id| {
            core.build_owned_games_call(steam_id as u64, job_id)
        })
    else {
        return ptr::null_mut();
    };
    let Some(response) = crate::pb::cplayer::CPlayerGetOwnedGamesResponse::deserialize(&body)
    else {
        return ptr::null_mut();
    };
    let value = json!(response
        .games
        .iter()
        .map(|game| json!({
            "appId": game.appid,
            "name": game.name,
            "playtimeTwoWeeks": game.playtime_2weeks,
            "playtimeForever": game.playtime_forever,
            "imgIconUrl": game.img_icon_url,
            "sortAs": game.sort_as,
            "rtimeLastPlayed": game.rtime_last_played,
        }))
        .collect::<Vec<_>>())
    .to_string();
    new_string_or_null(&mut env, &value)
}


/// Steam's default profile language when the caller does not supply one.
const DEFAULT_PROFILE_LANGUAGE: &str = "english";

/// Low 32 bits of a SteamID64 — the account id the CM level lookup addresses.
fn account_id_of(steam_id: u64) -> u32 {
    (steam_id & 0xffff_ffff) as u32
}

/// `CMsgClientFSGetFriendsSteamLevels` (EMsg 7528) for a batch of SteamID64s; returns
/// `(accountid, level)` pairs. Accounts the CM declines to answer for are simply absent.
fn fetch_steam_levels(
    runtime: &Arc<CMClientRuntime>,
    steam_ids: &[u64],
    timeout: Duration,
) -> Option<Vec<(u32, u32)>> {
    let account_ids = steam_ids
        .iter()
        .copied()
        .filter(|sid| *sid != 0)
        .map(account_id_of)
        .collect::<Vec<_>>();
    if account_ids.is_empty() {
        return None;
    }
    let body = request_proto_body(runtime, timeout, |core, job_id| {
        core.build_friends_steam_levels(&account_ids, job_id)
    })?;
    let response =
        crate::pb::cmsg_client_fs_get_friends_steam_levels::CMsgClientFSGetFriendsSteamLevelsResponse::deserialize(&body)?;
    Some(
        response
            .friends
            .into_iter()
            .map(|friend| (friend.accountid, friend.level))
            .collect(),
    )
}

/// `Player.GetProfileItemsEquipped#1` for one SteamID64.
fn fetch_equipped_profile_items(
    runtime: &Arc<CMClientRuntime>,
    steam_id: u64,
    language: &str,
    timeout: Duration,
) -> Option<crate::pb::cplayer::CPlayerGetProfileItemsEquippedResponse> {
    let body = request_authed_service_body(runtime, timeout, |core, job_id| {
        core.build_profile_items_equipped_call(steam_id, language, job_id)
    })?;
    crate::pb::cplayer::CPlayerGetProfileItemsEquippedResponse::deserialize(&body)
}

/// `Player.GetFavoriteBadge#1` for one SteamID64.
fn fetch_favorite_badge(
    runtime: &Arc<CMClientRuntime>,
    steam_id: u64,
    timeout: Duration,
) -> Option<crate::pb::cplayer::CPlayerGetFavoriteBadgeResponse> {
    let body = request_authed_service_body(runtime, timeout, |core, job_id| {
        core.build_favorite_badge_call(steam_id, job_id)
    })?;
    crate::pb::cplayer::CPlayerGetFavoriteBadgeResponse::deserialize(&body)
}

/// `Player.GetOwnedGames#1` for one SteamID64. Empty for a private "game details" profile.
fn fetch_owned_games(
    runtime: &Arc<CMClientRuntime>,
    steam_id: u64,
    timeout: Duration,
) -> Option<crate::pb::cplayer::CPlayerGetOwnedGamesResponse> {
    let body = request_authed_service_body(runtime, timeout, |core, job_id| {
        core.build_owned_games_call(steam_id, job_id)
    })?;
    crate::pb::cplayer::CPlayerGetOwnedGamesResponse::deserialize(&body)
}

fn profile_item_json(item: Option<&crate::pb::cplayer::CPlayerProfileItem>) -> serde_json::Value {
    let Some(item) = item.filter(|item| !item.is_empty()) else {
        return serde_json::Value::Null;
    };
    json!({
        "communityItemId": item.communityitemid.to_string(),
        "imageSmall": item.image_small,
        "imageLarge": item.image_large,
        "name": item.name,
        "itemTitle": item.item_title,
        "itemDescription": item.item_description,
        "appId": item.appid,
        "itemType": item.item_type,
        "itemClass": item.item_class,
        "movieWebm": item.movie_webm,
        "movieMp4": item.movie_mp4,
        "movieWebmSmall": item.movie_webm_small,
        "movieMp4Small": item.movie_mp4_small,
        "equippedFlags": item.equipped_flags,
        "tiled": item.tiled,
        "profileColors": item
            .profile_colors
            .iter()
            .map(|color| json!({ "styleName": color.style_name, "color": color.color }))
            .collect::<Vec<_>>(),
    })
}

fn equipped_items_json(
    response: &crate::pb::cplayer::CPlayerGetProfileItemsEquippedResponse,
) -> serde_json::Value {
    json!({
        "avatarFrame": profile_item_json(response.avatar_frame.as_ref()),
        "profileBackground": profile_item_json(response.profile_background.as_ref()),
        "miniProfileBackground": profile_item_json(response.mini_profile_background.as_ref()),
        "animatedAvatar": profile_item_json(response.animated_avatar.as_ref()),
        "profileModifier": profile_item_json(response.profile_modifier.as_ref()),
        "steamDeckKeyboardSkin": profile_item_json(response.steam_deck_keyboard_skin.as_ref()),
    })
}

fn favorite_badge_json(
    badge: &crate::pb::cplayer::CPlayerGetFavoriteBadgeResponse,
) -> serde_json::Value {
    if !badge.has_favorite_badge {
        return serde_json::Value::Null;
    }
    json!({
        "badgeId": badge.badgeid,
        "communityItemId": badge.communityitemid.to_string(),
        "itemType": badge.item_type,
        "borderColor": badge.border_color,
        "appId": badge.appid,
        "level": badge.level,
    })
}

fn owned_game_json(game: &crate::pb::cplayer::CPlayerOwnedGame) -> serde_json::Value {
    json!({
        "appId": game.appid,
        "name": game.name,
        "playtimeTwoWeeks": game.playtime_2weeks,
        "playtimeForever": game.playtime_forever,
        "imgIconUrl": game.img_icon_url,
        "sortAs": game.sort_as,
        "rtimeLastPlayed": game.rtime_last_played,
    })
}

fn recently_played_games(
    response: &crate::pb::cplayer::CPlayerGetOwnedGamesResponse,
    max_count: usize,
) -> Vec<&crate::pb::cplayer::CPlayerOwnedGame> {
    let mut recent = response
        .games
        .iter()
        .filter(|game| game.playtime_2weeks > 0)
        .collect::<Vec<_>>();
    if recent.is_empty() {
        recent = response
            .games
            .iter()
            .filter(|game| game.rtime_last_played > 0)
            .collect();
        recent.sort_by(|a, b| b.rtime_last_played.cmp(&a.rtime_last_played));
    } else {
        recent.sort_by(|a, b| {
            b.playtime_2weeks
                .cmp(&a.playtime_2weeks)
                .then_with(|| b.rtime_last_played.cmp(&a.rtime_last_played))
        });
    }
    recent.truncate(max_count);
    recent
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetSteamLevels(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    steam_ids: JLongArray,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let len = env.get_array_length(&steam_ids).unwrap_or(0);
    if len <= 0 {
        return new_string_or_null(&mut env, "[]");
    }
    let mut ids = vec![0i64; len as usize];
    if env.get_long_array_region(&steam_ids, 0, &mut ids).is_err() {
        return ptr::null_mut();
    }
    let ids = ids.into_iter().map(|id| id as u64).collect::<Vec<_>>();
    let Some(levels) = fetch_steam_levels(&runtime, &ids, Duration::from_secs(15)) else {
        return ptr::null_mut();
    };
    // Map account ids back onto the SteamID64s the caller asked about.
    let value = json!(ids
        .iter()
        .filter_map(|sid| {
            let account_id = account_id_of(*sid);
            levels
                .iter()
                .find(|(id, _)| *id == account_id)
                .map(|(_, level)| json!({
                    "steamId": *sid as i64,
                    "accountId": account_id,
                    "level": level,
                }))
        })
        .collect::<Vec<_>>())
    .to_string();
    new_string_or_null(&mut env, &value)
}

/// Blocking `Player.GetProfileItemsEquipped#1` for any SteamID64; JSON or null.
///
/// Slots with nothing equipped come back as JSON null. Equipped items are public profile
/// decoration, so this resolves for friends as well as for the signed-in user.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetEquippedProfileItems(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    steam_id: jlong,
    language: JString,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let language = jstring_to_string(&mut env, &language)
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| DEFAULT_PROFILE_LANGUAGE.to_string());
    let Some(response) = fetch_equipped_profile_items(
        &runtime,
        steam_id as u64,
        &language,
        Duration::from_secs(15),
    ) else {
        return ptr::null_mut();
    };
    new_string_or_null(&mut env, &equipped_items_json(&response).to_string())
}

/// Blocking `Player.GetFavoriteBadge#1` for any SteamID64; JSON, `null` when no badge is
/// showcased, or null when the request never landed.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetFavoriteBadge(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    steam_id: jlong,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let Some(badge) = fetch_favorite_badge(&runtime, steam_id as u64, Duration::from_secs(15))
    else {
        return ptr::null_mut();
    };
    new_string_or_null(&mut env, &favorite_badge_json(&badge).to_string())
}

/// Recently played games for any SteamID64, derived from `Player.GetOwnedGames#1`
/// (no extra round trip). JSON array of owned-game objects, newest/most-played first.
///
/// Empty for an account whose game details are private.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetRecentlyPlayedGames(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    steam_id: jlong,
    max_count: jint,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let Some(response) = fetch_owned_games(&runtime, steam_id as u64, Duration::from_secs(30))
    else {
        return ptr::null_mut();
    };
    let max_count = if max_count <= 0 {
        usize::MAX
    } else {
        max_count as usize
    };
    let value = json!(recently_played_games(&response, max_count)
        .into_iter()
        .map(owned_game_json)
        .collect::<Vec<_>>())
    .to_string();
    new_string_or_null(&mut env, &value)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeGetPlayerProfile(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    steam_id: jlong,
    language: JString,
    max_recent: jint,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let steam_id = steam_id as u64;
    if steam_id == 0 {
        return ptr::null_mut();
    }
    let language = jstring_to_string(&mut env, &language)
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| DEFAULT_PROFILE_LANGUAGE.to_string());
    let max_recent = if max_recent <= 0 {
        usize::MAX
    } else {
        max_recent as usize
    };
    let timeout = Duration::from_secs(15);

    let profile_info = request_proto_body(&runtime, timeout, |core, job_id| {
        core.build_friend_profile_info(steam_id, job_id)
    })
    .and_then(|body| {
        crate::pb::cmsg_client_friends_ops::CMsgClientFriendProfileInfoResponse::deserialize(&body)
    })
    .map(|resp| {
        json!({
            "eresult": resp.eresult,
            "timeCreated": resp.time_created,
            "realName": resp.real_name,
            "cityName": resp.city_name,
            "stateName": resp.state_name,
            "countryName": resp.country_name,
            "headline": resp.headline,
            "summary": resp.summary,
        })
    })
    .unwrap_or(serde_json::Value::Null);

    let level = fetch_steam_levels(&runtime, &[steam_id], timeout)
        .and_then(|levels| {
            let account_id = account_id_of(steam_id);
            levels
                .into_iter()
                .find(|(id, _)| *id == account_id)
                .map(|(_, level)| level)
        })
        .map(serde_json::Value::from)
        .unwrap_or(serde_json::Value::Null);

    let favorite_badge = fetch_favorite_badge(&runtime, steam_id, timeout)
        .map(|badge| favorite_badge_json(&badge))
        .unwrap_or(serde_json::Value::Null);

    let equipped = fetch_equipped_profile_items(&runtime, steam_id, &language, timeout)
        .map(|response| equipped_items_json(&response))
        .unwrap_or(serde_json::Value::Null);

    let owned = fetch_owned_games(&runtime, steam_id, Duration::from_secs(30));
    let games_public = owned
        .as_ref()
        .is_some_and(|response| response.game_count > 0 || !response.games.is_empty());
    let (owned_count, playtime_forever, playtime_two_weeks, recently_played) = match owned
        .as_ref()
        .filter(|_| games_public)
    {
        Some(response) => {
            let forever: i64 = response
                .games
                .iter()
                .map(|game| i64::from(game.playtime_forever))
                .sum();
            let two_weeks: i64 = response
                .games
                .iter()
                .map(|game| i64::from(game.playtime_2weeks))
                .sum();
            let recent = json!(recently_played_games(response, max_recent)
                .into_iter()
                .map(owned_game_json)
                .collect::<Vec<_>>());
            (
                serde_json::Value::from(response.game_count.max(response.games.len() as u32)),
                serde_json::Value::from(forever),
                serde_json::Value::from(two_weeks),
                recent,
            )
        }
        None => (
            serde_json::Value::Null,
            serde_json::Value::Null,
            serde_json::Value::Null,
            serde_json::Value::Null,
        ),
    };

    let value = json!({
        "steamId": steam_id as i64,
        "accountId": account_id_of(steam_id),
        "isSelf": steam_id == handle.core.steam_id(),
        "level": level,
        "favoriteBadge": favorite_badge,
        "equipped": equipped,
        "profileInfo": profile_info,
        "gamesPublic": games_public,
        "ownedGameCount": owned_count,
        "playtimeForeverMinutes": playtime_forever,
        "playtimeTwoWeeksMinutes": playtime_two_weeks,
        "recentlyPlayed": recently_played,
    })
    .to_string();
    new_string_or_null(&mut env, &value)
}

/// How long to wait for the CM's post-grant `CMsgClientLicenseList` push before reporting that
/// the new package is not visible yet. Steam pushes it unprompted within a second or so; there is
/// no client-initiated "re-send my licenses" request to fall back on.
const FREE_LICENSE_LIST_SETTLE_TIMEOUT: Duration = Duration::from_secs(8);

fn await_license_visibility(
    runtime: &Arc<CMClientRuntime>,
    package_ids: &[u32],
    timeout: Duration,
) -> bool {
    if package_ids.is_empty() {
        return false;
    }
    let deadline = std::time::Instant::now() + timeout;
    loop {
        let licenses = runtime.core().license_list();
        let all_present = package_ids.iter().all(|package_id| {
            licenses
                .iter()
                .any(|license| license.package_id == *package_id)
        });
        if all_present {
            return true;
        }
        if std::time::Instant::now() >= deadline {
            return false;
        }
        thread::sleep(Duration::from_millis(150));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeRequestFreeLicense(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_ids: JIntArray,
) -> jstring {
    use crate::pb::cmsg_client_request_free_license::{
        CMsgClientRequestFreeLicenseResponse, FREE_LICENSE_DEFAULT_ERESULT,
    };

    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let app_ids = int_array_to_u32_vec(&env, &app_ids);

    // Defaults describe a NON-grant: eresult starts at the protobuf's own default of 2 (Fail),
    // so every early exit below reports failure rather than an empty success.
    let mut status = "invalid_request";
    let mut eresult = FREE_LICENSE_DEFAULT_ERESULT;
    let mut header_eresult = 0i32;
    let mut granted = false;
    let mut granted_packages: Vec<u32> = Vec::new();
    let mut granted_apps: Vec<u32> = Vec::new();
    let mut library_updated = false;

    if !app_ids.is_empty() {
        match request_proto_response(&runtime, Duration::from_secs(20), |core, job_id| {
            core.build_request_free_license(&app_ids, job_id)
        }) {
            // Nothing came back: session dropped or the CM never answered. NOT a refusal.
            None => status = "no_response",
            Some((header, body)) => {
                header_eresult = header;
                match CMsgClientRequestFreeLicenseResponse::deserialize(&body) {
                    None => status = "bad_response",
                    Some(response) => {
                        eresult = response.eresult;
                        granted = response.is_granted();
                        granted_packages = response.granted_packageids;
                        granted_apps = response.granted_appids;
                        // Only wait on the license list when Steam actually granted something.
                        library_updated = granted
                            && await_license_visibility(
                                &runtime,
                                &granted_packages,
                                FREE_LICENSE_LIST_SETTLE_TIMEOUT,
                            );
                        status = if granted { "granted" } else { "denied" };
                    }
                }
            }
        }
    }

    android_log(
        "LUDASHI_STEAM_LICENSE",
        &format!(
            "free license apps={app_ids:?} status={status} eresult={eresult} \
             packages={granted_packages:?} apps={granted_apps:?} library_updated={library_updated}"
        ),
    );

    let value = json!({
        "status": status,
        "granted": granted,
        "eresult": eresult,
        "headerEresult": header_eresult,
        "requestedAppIds": app_ids,
        "grantedPackageIds": granted_packages,
        "grantedAppIds": granted_apps,
        "libraryUpdated": library_updated,
    })
    .to_string();
    new_string_or_null(&mut env, &value)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeSignalAppLaunchIntent(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
    client_id: jlong,
    machine_name: JString,
    ignore_pending: jboolean,
    os_type: jint,
) -> jstring {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return ptr::null_mut();
    };
    let Some(runtime) = handle.connected_runtime() else {
        return ptr::null_mut();
    };
    let machine_name = jstring_to_string(&mut env, &machine_name).unwrap_or_default();
    let Some(body) =
        request_authed_service_body(&runtime, Duration::from_secs(30), |core, job_id| {
            core.build_cloud_launch_intent_call(
                app_id.max(0) as u32,
                client_id as u64,
                machine_name,
                ignore_pending != JNI_FALSE,
                os_type,
                job_id,
            )
        })
    else {
        return ptr::null_mut();
    };
    let Some(response) = crate::pb::ccloud::CCloudAppLaunchIntentResponse::deserialize(&body)
    else {
        return ptr::null_mut();
    };
    let value = json!({
        "pendingOps": response.pending_operation_codes,
    })
    .to_string();
    new_string_or_null(&mut env, &value)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_steam_ludashisteam_LudashiSteamSession_nativeSignalAppExitSyncDone(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    app_id: jint,
    client_id: jlong,
    uploads_completed: jboolean,
    uploads_required: jboolean,
) {
    let Some(handle) = (unsafe { from_session_handle_mut(handle) }) else {
        return;
    };
    let Some(runtime) = handle.connected_runtime() else {
        return;
    };
    let _ = request_authed_service_success(&runtime, Duration::from_secs(5), |core, job_id| {
        core.build_cloud_exit_sync_done_call(
            app_id.max(0) as u32,
            client_id as u64,
            uploads_completed != JNI_FALSE,
            uploads_required != JNI_FALSE,
            job_id,
        )
    });
}

#[cfg(test)]
mod tests {
    #[test]
    fn auth_timeout_retains_the_failing_request_stage() {
        let job = crate::job_manager::JobResult {
            eresult: -1, error_message: "job timeout".into(), body: Vec::new(), synthetic_failure: true,
        };
        let result = super::auth_result_from_job(&job, "GetPasswordRSAPublicKey");
        assert!(result.error_message.contains("GetPasswordRSAPublicKey"));
        assert!(result.error_message.contains("job timeout"));
        assert_eq!(result.eresult, -1);
    }

    use super::{parse_u32_lines, parse_u64_lines};

    #[test]
    fn parses_high_bit_token_from_signed_long_string() {
        // High-bit u64 token arrives as a negative Long string; recover unsigned.
        assert_eq!(
            parse_u64_lines("-2956503589389641226"),
            vec![15490240484319910390u64]
        );
        // Plain unsigned strings still parse unchanged.
        assert_eq!(parse_u64_lines("4984014265555654850"), vec![4984014265555654850u64]);
    }

    #[test]
    fn keeps_token_to_id_pairing_aligned_across_negatives() {
        // A dropped negative line shifts later tokens onto the wrong package.
        let ids = parse_u32_lines("305944\n322317\n304933");
        let tokens = parse_u64_lines("4984014265555654850\n-4562710670371372905\n3396749975682522332");
        assert_eq!(ids.len(), 3);
        assert_eq!(tokens.len(), 3, "no line may be dropped or pairing misaligns");
        assert_eq!(tokens[1], (-4562710670371372905i64) as u64);
        assert_eq!(tokens[2], 3396749975682522332u64);
    }

    #[test]
    fn parses_high_bit_u32_id_from_signed_int_string() {
        assert_eq!(parse_u32_lines("-1"), vec![u32::MAX]);
        assert_eq!(parse_u32_lines("601150"), vec![601150u32]);
    }

    #[test]
    fn blank_or_garbage_line_becomes_zero_without_shifting() {
        // A blank/garbage line must keep its slot (0), not drop and misalign.
        assert_eq!(parse_u64_lines("11\n\n22"), vec![11u64, 0, 22]);
        assert_eq!(parse_u32_lines("11\nxx\n22"), vec![11u32, 0, 22]);
        assert!(parse_u64_lines("").is_empty());
    }

    #[test]
    fn appinfo_json_preserves_object_key_order() {
        // The Kotlin appinfo decoder groups DLC depots positionally, so depot
        // keys must stay in source order, not be sorted (BTreeMap default).
        let mut depots = crate::vdf::KVNode::new("depots");
        for id in ["601151", "1432644", "601152"] {
            depots.children.push(crate::vdf::KVNode::new(id));
        }
        assert_eq!(
            super::kvnode_to_json_value(&depots).to_string(),
            r#"{"601151":{},"1432644":{},"601152":{}}"#
        );
    }
}
