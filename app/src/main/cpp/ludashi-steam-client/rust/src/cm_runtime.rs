use crate::cm_bridge;
use crate::cm_client::{decode_multi_records, CMClientCore, ClientState, InboundAction};
use crate::cmsg_protobuf_header::{CMsgProtoBufHeader, INVALID_JOB_ID};
use crate::emsg::{has_proto_flag, strip_proto_flag, EMsg};
use crate::encrypted_channel::{ChannelDisconnectReason, ChannelState, EncryptedChannel};
use crate::heartbeat::Heartbeat;
use crate::job_manager::{JobManager, JobResult};
use crate::pb::cmsg_client_pics::CMsgClientPICSProductInfoResponse;
use crate::pb::cmsg_clientserver_login::CMsgClientLogonResponse;
use crate::proto_envelope::decode_proto_envelope;
use crate::transport::Transport;
use crate::wire_format::read_u32_le;
use std::collections::HashMap;
use std::panic::{self, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

type StateCallback = Box<dyn Fn(ClientState) + Send + Sync + 'static>;
type ClientMessageCallback = Box<dyn Fn(EMsg, &CMsgProtoBufHeader, &[u8]) + Send + Sync + 'static>;

pub struct CMClientRuntime {
    core: Arc<CMClientCore>,
    channel: EncryptedChannel,
    jobs: JobManager,
    heartbeat: Mutex<Heartbeat>,
    pics_product_info: Mutex<HashMap<u64, CMsgClientPICSProductInfoResponse>>,
    on_state: Mutex<Option<StateCallback>>,
    on_client_message: Mutex<Option<ClientMessageCallback>>,
    /// Announce the persona as Online right after a successful logon. The desktop client does; a
    /// headless session that must leave no social footprint (friends/chat opted out) turns it off
    /// and announces later, explicitly, when the user opts in.
    auto_persona_online: AtomicBool,
}

impl CMClientRuntime {
    pub fn new(core: Arc<CMClientCore>, transport: Box<dyn Transport>) -> Arc<Self> {
        let runtime = Arc::new(Self {
            core,
            channel: EncryptedChannel::new(transport),
            jobs: JobManager::default(),
            heartbeat: Mutex::new(Heartbeat::default()),
            pics_product_info: Mutex::new(HashMap::new()),
            on_state: Mutex::new(None),
            on_client_message: Mutex::new(None),
            auto_persona_online: AtomicBool::new(true),
        });

        let weak = Arc::downgrade(&runtime);
        runtime.channel.set_on_connected(move || {
            if let Some(runtime) = weak.upgrade() {
                runtime.on_channel_connected();
            }
        });

        let weak = Arc::downgrade(&runtime);
        runtime.channel.set_on_disconnected(move |reason, detail| {
            if let Some(runtime) = weak.upgrade() {
                runtime.on_channel_disconnected(reason, detail);
            }
        });

        let weak = Arc::downgrade(&runtime);
        runtime.channel.set_on_message(move |bytes| {
            if let Some(runtime) = weak.upgrade() {
                runtime.handle_channel_message(bytes);
            }
        });

        runtime
    }

    pub fn core(&self) -> &Arc<CMClientCore> {
        &self.core
    }

    pub fn connect(&self, url: &str) -> bool {
        self.core.set_state(ClientState::Connecting);
        self.notify_state(ClientState::Connecting);
        if self.channel.connect(url) {
            true
        } else {
            self.core.set_state(ClientState::Disconnected);
            self.notify_state(ClientState::Disconnected);
            false
        }
    }

    pub fn disconnect(&self) {
        self.heartbeat.lock().expect("heartbeat poisoned").stop();
        self.channel.disconnect();
        self.jobs.fail_all("CMClient disconnected");
        self.core.reset_session_identity();
        self.core.set_state(ClientState::Disconnected);
        self.notify_state(ClientState::Disconnected);
    }

    pub fn set_auto_persona_online(&self, enabled: bool) {
        self.auto_persona_online.store(enabled, Ordering::Relaxed);
    }

    pub fn auto_persona_online(&self) -> bool {
        self.auto_persona_online.load(Ordering::Relaxed)
    }

    pub fn set_ca_bundle_path(&self, path: &str) {
        self.channel.set_ca_bundle_path(path);
    }

    pub fn set_on_state<F>(&self, callback: F)
    where
        F: Fn(ClientState) + Send + Sync + 'static,
    {
        *self.on_state.lock().expect("runtime callback poisoned") = Some(Box::new(callback));
    }

    pub fn set_on_client_message<F>(&self, callback: F)
    where
        F: Fn(EMsg, &CMsgProtoBufHeader, &[u8]) + Send + Sync + 'static,
    {
        *self
            .on_client_message
            .lock()
            .expect("runtime callback poisoned") = Some(Box::new(callback));
    }

    pub fn next_job_id(&self) -> u64 {
        self.jobs.next_job_id()
    }

    pub fn track_job<F>(&self, job_id: u64, callback: F, timeout: Option<Duration>)
    where
        F: FnOnce(JobResult) + Send + 'static,
    {
        self.jobs.track(job_id, callback, timeout);
    }

    pub fn flush_outbound(&self) -> usize {
        if self.channel.state() != ChannelState::Encrypted {
            return 0;
        }
        let wires = self.core.take_outbound_wires();
        let mut sent = 0usize;
        for (idx, wire) in wires.iter().enumerate() {
            if self.channel.send(wire) {
                sent += 1;
                continue;
            }
            self.core
                .restore_outbound_wires_front(wires[idx..].to_vec());
            break;
        }
        sent
    }

    pub fn handle_channel_message(self: &Arc<Self>, bytes: &[u8]) -> InboundAction {
        let Some(envelope) = decode_proto_envelope(bytes) else {
            return self.handle_non_proto_message(bytes);
        };
        self.process_envelope(envelope.emsg, &envelope.header, &envelope.body)
    }

    fn process_envelope(
        self: &Arc<Self>,
        emsg: EMsg,
        header: &CMsgProtoBufHeader,
        body: &[u8],
    ) -> InboundAction {
        let action = self.core.route_inbound(emsg, header, body);
        match &action {
            InboundAction::Multi => {
                if let Some(records) = decode_multi_records(body) {
                    for record in records {
                        self.handle_channel_message(&record);
                    }
                }
            }
            InboundAction::DeliverJob(result) => {
                self.jobs.deliver(
                    header.jobid_target,
                    result.eresult,
                    result.error_message.clone(),
                    &result.body,
                );
            }
            InboundAction::LogonOk => {
                self.start_heartbeat_from_logon(body);
                if self.auto_persona_online() {
                    self.core
                        .enqueue_proto_message(self.core.build_set_persona_state(1));
                }
                self.core
                    .enqueue_proto_message(self.core.build_request_user_persona());
                let job_id = self.jobs.next_job_id();
                self.core
                    .enqueue_service_call(self.core.build_request_friend_persona_states(job_id));
                self.flush_outbound();
                cm_bridge::global_bridge()
                    .observers()
                    .dispatch_logon_state(true);
                cm_bridge::global_bridge()
                    .observers()
                    .dispatch_server_realtime(self.core.server_realtime());
                self.notify_state(ClientState::LoggedOn);
                self.notify_client_message(emsg, header, body);
            }
            InboundAction::LoggedOff => {
                self.jobs.fail_all("Steam session logged off");
                self.heartbeat.lock().expect("heartbeat poisoned").stop();
                cm_bridge::global_bridge()
                    .observers()
                    .dispatch_logon_state(false);
                self.notify_state(self.core.state());
                self.notify_client_message(emsg, header, body);
            }
            InboundAction::Ignored | InboundAction::ParseFailed(_) => {}
            InboundAction::PicsProductInfo => self.handle_pics_product_info(header, body),
            InboundAction::LobbyPush => {
                cm_bridge::global_bridge().dispatch_lobby_push(emsg, body);
            }
            InboundAction::LicenseList(_) => {
                let licenses = self
                    .core
                    .license_list()
                    .iter()
                    .map(cm_bridge::LudashiCmLicenseEntry::from)
                    .collect::<Vec<_>>();
                cm_bridge::global_bridge()
                    .observers()
                    .dispatch_license_list(&licenses);
                self.notify_client_message(emsg, header, body);
            }
            InboundAction::FriendsList(_) => {
                let friends = self.core.friends_list();
                cm_bridge::global_bridge()
                    .observers()
                    .dispatch_friends_list(&friends);
                self.notify_client_message(emsg, header, body);
            }
            InboundAction::PersonaState(_) => {
                let bridge = cm_bridge::global_bridge();
                if let Some(self_persona) = self.core.self_persona() {
                    bridge.dispatch_persona_friend(&self_persona);
                }
                for snapshot in self.core.friend_personas() {
                    bridge.dispatch_persona_snapshot(&snapshot);
                }
                self.notify_client_message(emsg, header, body);
            }
            InboundAction::AccountInfo(snapshot) => {
                cm_bridge::global_bridge().dispatch_account_info_snapshot(snapshot);
                self.notify_client_message(emsg, header, body);
            }
            InboundAction::ClientMessage | InboundAction::PlayingSessionState(_) => {
                self.notify_client_message(emsg, header, body);
            }
        }
        action
    }

    fn on_channel_connected(self: &Arc<Self>) {
        self.core.set_state(ClientState::Connected);
        // Always send ClientHello first; pre-existing queued wires (e.g. a
        // logon enqueued before the channel finished negotiating) must come
        // after the Hello or Steam rejects the conversation as malformed.
        // If a Hello was already prequeued, do not duplicate it.
        let queued = self.core.take_outbound_wires();
        let head_is_hello = queued
            .first()
            .and_then(|wire| decode_proto_envelope(wire))
            .is_some_and(|env| env.emsg == EMsg::CLIENT_HELLO);
        let combined = if head_is_hello {
            queued
        } else {
            let hello = self.core.build_client_hello().wire;
            let mut combined = Vec::with_capacity(queued.len() + 1);
            combined.push(hello);
            combined.extend(queued);
            combined
        };
        self.core.restore_outbound_wires_front(combined);
        self.flush_outbound();
        // Observers may immediately enqueue/flush saved logon. Publish readiness
        // only after Hello is sent (or remains at the front of the retry queue).
        self.notify_state(ClientState::Connected);
    }

    fn on_channel_disconnected(self: &Arc<Self>, _reason: ChannelDisconnectReason, detail: &str) {
        self.heartbeat.lock().expect("heartbeat poisoned").stop();
        self.jobs
            .fail_all(&format!("channel disconnected: {detail}"));
        self.pics_product_info
            .lock()
            .expect("pics product info poisoned")
            .clear();
        self.core.reset_session_identity();
        self.core.set_state(ClientState::Disconnected);
        self.notify_state(ClientState::Disconnected);
    }

    fn handle_pics_product_info(&self, header: &CMsgProtoBufHeader, body: &[u8]) {
        if header.jobid_target == INVALID_JOB_ID {
            return;
        }
        let Some(response) = CMsgClientPICSProductInfoResponse::deserialize(body) else {
            self.jobs.deliver(
                header.jobid_target,
                -1,
                "PICS product-info parse failed".to_string(),
                &[],
            );
            return;
        };
        let mut pending = self
            .pics_product_info
            .lock()
            .expect("pics product info poisoned");
        if response.response_pending {
            let acc = pending.entry(header.jobid_target).or_default();
            merge_pics_product_info(acc, response);
            return;
        }
        let mut merged = pending.remove(&header.jobid_target).unwrap_or_default();
        merge_pics_product_info(&mut merged, response);
        let body = merged.serialize();
        self.jobs
            .deliver(header.jobid_target, 1, String::new(), &body);
    }

    fn start_heartbeat_from_logon(self: &Arc<Self>, body: &[u8]) {
        let Some(resp) = CMsgClientLogonResponse::deserialize(body) else {
            return;
        };
        if resp.heartbeat_seconds <= 0 {
            return;
        }
        let weak = Arc::downgrade(self);
        let interval = Duration::from_secs(resp.heartbeat_seconds as u64);
        self.heartbeat
            .lock()
            .expect("heartbeat poisoned")
            .start(interval, move || {
                if let Some(runtime) = weak.upgrade() {
                    runtime
                        .core
                        .enqueue_wire(runtime.core.build_heartbeat().wire);
                    runtime.flush_outbound();
                }
            });
    }

    fn handle_non_proto_message(&self, bytes: &[u8]) -> InboundAction {
        if bytes.len() >= 4 {
            let raw = read_u32_le(bytes);
            if !has_proto_flag(raw) {
                let legacy = strip_proto_flag(raw);
                if legacy == EMsg::CHANNEL_ENCRYPT_REQUEST
                    || legacy == EMsg::CHANNEL_ENCRYPT_RESPONSE
                    || legacy == EMsg::CHANNEL_ENCRYPT_RESULT
                {
                    return InboundAction::Ignored;
                }
            }
        }
        InboundAction::ParseFailed("ProtoEnvelope")
    }

    fn notify_state(&self, state: ClientState) {
        if let Some(callback) = self
            .on_state
            .lock()
            .expect("runtime callback poisoned")
            .as_ref()
        {
            let _ = panic::catch_unwind(AssertUnwindSafe(|| callback(state)));
        }
    }

    fn notify_client_message(&self, emsg: EMsg, header: &CMsgProtoBufHeader, body: &[u8]) {
        if let Some(callback) = self
            .on_client_message
            .lock()
            .expect("runtime callback poisoned")
            .as_ref()
        {
            let _ = panic::catch_unwind(AssertUnwindSafe(|| callback(emsg, header, body)));
        }
    }
}

fn merge_pics_product_info(
    acc: &mut CMsgClientPICSProductInfoResponse,
    mut next: CMsgClientPICSProductInfoResponse,
) {
    acc.apps.append(&mut next.apps);
    acc.packages.append(&mut next.packages);
    acc.unknown_appids.append(&mut next.unknown_appids);
    acc.unknown_packageids.append(&mut next.unknown_packageids);
    if next.http_min_size > 0 {
        acc.http_min_size = next.http_min_size;
    }
    if !next.http_host.is_empty() {
        acc.http_host = next.http_host;
    }
    acc.meta_data_only = next.meta_data_only;
    acc.response_pending = false;
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto_envelope::encode_proto_envelope;
    use crate::proto_wire::Writer as ProtoWriter;
    use crate::transport::{ConnectedCallback, DisconnectedCallback, MessageCallback};
    use crate::transport::{TransportDisconnectReason, TransportState};
    use crate::wire_format::Writer as WireWriter;
    use std::sync::mpsc;

    #[test]
    fn connected_channel_sends_client_hello_and_reports_state() {
        let shared = Arc::new(MockTransportState::default());
        let runtime = runtime_with_shared_transport(Arc::clone(&shared));
        let states = Arc::new(Mutex::new(Vec::new()));
        let states_cb = Arc::clone(&states);
        runtime.set_on_state(move |state| states_cb.lock().unwrap().push(state));

        assert!(runtime.connect("wss://cm.example.com:443/cmsocket/"));
        shared.fire_connected();

        assert_eq!(
            states.lock().unwrap().as_slice(),
            &[ClientState::Connecting, ClientState::Connected]
        );
        let sent = shared.take_sent();
        assert_eq!(
            decode_proto_envelope(&sent[0]).unwrap().emsg,
            EMsg::CLIENT_HELLO
        );
    }

    #[test]
    fn connected_observer_can_log_on_only_after_client_hello_was_sent() {
        let shared = Arc::new(MockTransportState::default());
        let runtime = runtime_with_shared_transport(Arc::clone(&shared));
        let weak = Arc::downgrade(&runtime);
        runtime.set_on_state(move |state| {
            if state == ClientState::Connected {
                let runtime = weak.upgrade().unwrap();
                let logon = runtime.core.build_logon_with_refresh_token("test-token", "test-account", 123).unwrap();
                runtime.core.enqueue_wire(logon.wire);
                runtime.flush_outbound();
            }
        });
        assert!(runtime.connect("wss://cm.example.com:443/cmsocket/"));
        shared.fire_connected();
        let sent = shared.take_sent();
        assert!(sent.len() >= 2);
        assert_eq!(decode_proto_envelope(&sent[0]).unwrap().emsg, EMsg::CLIENT_HELLO);
    }

    #[test]
    fn connected_channel_flushes_prequeued_hello_without_duplicate() {
        let shared = Arc::new(MockTransportState::default());
        let runtime = runtime_with_shared_transport(Arc::clone(&shared));
        runtime
            .core
            .enqueue_wire(runtime.core.build_client_hello().wire);

        assert!(runtime.connect("wss://cm.example.com:443/cmsocket/"));
        shared.fire_connected();

        let sent = shared.take_sent();
        assert_eq!(sent.len(), 1);
        assert_eq!(
            decode_proto_envelope(&sent[0]).unwrap().emsg,
            EMsg::CLIENT_HELLO
        );
    }

    #[test]
    fn flush_outbound_preserves_core_queue_order() {
        let shared = Arc::new(MockTransportState::default());
        let runtime = runtime_with_shared_transport(Arc::clone(&shared));
        assert!(runtime.connect("wss://cm.example.com:443/cmsocket/"));
        shared.fire_connected();
        shared.take_sent();

        runtime.core.enqueue_wire(vec![1, 2, 3]);
        runtime.core.enqueue_wire(vec![4, 5, 6]);
        assert_eq!(runtime.flush_outbound(), 2);
        assert_eq!(shared.take_sent(), vec![vec![1, 2, 3], vec![4, 5, 6]]);
    }

    #[test]
    fn inbound_job_response_is_delivered() {
        let shared = Arc::new(MockTransportState::default());
        let runtime = runtime_with_shared_transport(shared);
        let job_id = runtime.next_job_id();
        let (tx, rx) = mpsc::channel();
        runtime.track_job(job_id, move |result| tx.send(result).unwrap(), None);

        let header = CMsgProtoBufHeader {
            jobid_target: job_id,
            eresult: 1,
            ..Default::default()
        };
        let wire = encode_proto_envelope(EMsg::SERVICE_METHOD_RESPONSE, &header, b"reply");
        assert_eq!(
            runtime.handle_channel_message(&wire),
            InboundAction::DeliverJob(JobResult {
                eresult: 1,
                error_message: String::new(),
                body: b"reply".to_vec(),
                synthetic_failure: false,
            })
        );
        assert_eq!(rx.recv().unwrap().body, b"reply");
    }

    #[test]
    fn multi_records_are_redispatched() {
        let shared = Arc::new(MockTransportState::default());
        let runtime = runtime_with_shared_transport(shared);
        let (tx, rx) = mpsc::channel();
        runtime.set_on_client_message(move |emsg, _, body| {
            tx.send((emsg, body.to_vec())).unwrap();
        });

        let inner = encode_proto_envelope(
            EMsg::CLIENT_ACCOUNT_INFO,
            &CMsgProtoBufHeader::default(),
            &account_info_body("Ada"),
        );
        let mut records = Vec::new();
        WireWriter::new(&mut records).u32(inner.len() as u32);
        records.extend_from_slice(&inner);
        let mut multi_body = Vec::new();
        ProtoWriter::new(&mut multi_body).bytes_field(2, &records);
        let multi = encode_proto_envelope(EMsg::MULTI, &CMsgProtoBufHeader::default(), &multi_body);

        assert_eq!(runtime.handle_channel_message(&multi), InboundAction::Multi);
        assert_eq!(
            rx.recv().unwrap(),
            (EMsg::CLIENT_ACCOUNT_INFO, account_info_body("Ada"))
        );
    }

    #[test]
    fn legacy_channel_encrypt_frames_are_ignored() {
        let shared = Arc::new(MockTransportState::default());
        let runtime = runtime_with_shared_transport(shared);
        let mut legacy = Vec::new();
        WireWriter::new(&mut legacy).u32(EMsg::CHANNEL_ENCRYPT_REQUEST.0);
        assert_eq!(
            runtime.handle_channel_message(&legacy),
            InboundAction::Ignored
        );
    }

    fn runtime_with_shared_transport(shared: Arc<MockTransportState>) -> Arc<CMClientRuntime> {
        CMClientRuntime::new(
            Arc::new(CMClientCore::default()),
            Box::new(MockTransport { shared }),
        )
    }

    fn account_info_body(name: &str) -> Vec<u8> {
        let mut body = Vec::new();
        ProtoWriter::new(&mut body).string_field(1, name);
        body
    }

    struct MockTransportState {
        state: Mutex<TransportState>,
        connected: Mutex<Option<ConnectedCallback>>,
        disconnected: Mutex<Option<DisconnectedCallback>>,
        message: Mutex<Option<MessageCallback>>,
        sent: Mutex<Vec<Vec<u8>>>,
    }

    impl Default for MockTransportState {
        fn default() -> Self {
            Self {
                state: Mutex::new(TransportState::Disconnected),
                connected: Mutex::new(None),
                disconnected: Mutex::new(None),
                message: Mutex::new(None),
                sent: Mutex::new(Vec::new()),
            }
        }
    }

    impl MockTransportState {
        fn fire_connected(&self) {
            *self.state.lock().unwrap() = TransportState::Connected;
            if let Some(callback) = self.connected.lock().unwrap().as_ref() {
                callback();
            }
        }

        #[allow(dead_code)]
        fn fire_disconnected(&self, reason: TransportDisconnectReason, detail: &str) {
            *self.state.lock().unwrap() = TransportState::Disconnected;
            if let Some(callback) = self.disconnected.lock().unwrap().as_ref() {
                callback(reason, detail);
            }
        }

        #[allow(dead_code)]
        fn fire_message(&self, bytes: &[u8]) {
            if let Some(callback) = self.message.lock().unwrap().as_ref() {
                callback(bytes);
            }
        }

        fn take_sent(&self) -> Vec<Vec<u8>> {
            std::mem::take(&mut *self.sent.lock().unwrap())
        }
    }

    struct MockTransport {
        shared: Arc<MockTransportState>,
    }

    impl Transport for MockTransport {
        fn connect(&mut self, _url: &str) -> bool {
            *self.shared.state.lock().unwrap() = TransportState::Connecting;
            true
        }

        fn send(&mut self, data: &[u8]) -> bool {
            if self.state() != TransportState::Connected {
                return false;
            }
            self.shared.sent.lock().unwrap().push(data.to_vec());
            true
        }

        fn disconnect(&mut self) {
            *self.shared.state.lock().unwrap() = TransportState::Disconnected;
        }

        fn state(&self) -> TransportState {
            *self.shared.state.lock().unwrap()
        }

        fn set_on_message(&mut self, cb: MessageCallback) {
            *self.shared.message.lock().unwrap() = Some(cb);
        }

        fn set_on_connected(&mut self, cb: ConnectedCallback) {
            *self.shared.connected.lock().unwrap() = Some(cb);
        }

        fn set_on_disconnected(&mut self, cb: DisconnectedCallback) {
            *self.shared.disconnected.lock().unwrap() = Some(cb);
        }
    }
}
