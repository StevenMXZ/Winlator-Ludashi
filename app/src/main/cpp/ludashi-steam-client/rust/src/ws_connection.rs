use crate::transport::{Transport, TransportDisconnectReason, TransportState};
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::{mpsc, Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;
use tungstenite::error::Error as WsError;
use tungstenite::stream::MaybeTlsStream;
use tungstenite::{connect, Message};

const NORMAL_CLOSE_CODE: u16 = 1000;
const READ_POLL_TIMEOUT: Duration = Duration::from_millis(100);

type MessageCallback = Arc<dyn Fn(&[u8]) + Send + Sync + 'static>;
type ConnectedCallback = Arc<dyn Fn() + Send + Sync + 'static>;
type DisconnectedCallback = Arc<dyn Fn(TransportDisconnectReason, &str) + Send + Sync + 'static>;

pub struct WsConnection {
    shared: Arc<WsConnectionShared>,
    sender: Mutex<Option<mpsc::Sender<WsCommand>>>,
    worker: Mutex<Option<JoinHandle<()>>>,
}

#[derive(Default)]
struct WsConnectionShared {
    state: AtomicU8,
    user_initiated_close: AtomicBool,
    ca_bundle_path: Mutex<String>,
    on_message: Mutex<Option<MessageCallback>>,
    on_connected: Mutex<Option<ConnectedCallback>>,
    on_disconnected: Mutex<Option<DisconnectedCallback>>,
}

enum WsCommand {
    Send(Vec<u8>),
    Disconnect,
}

impl WsConnection {
    pub fn new() -> Self {
        Self {
            shared: Arc::new(WsConnectionShared {
                state: AtomicU8::new(TransportState::Disconnected as u8),
                user_initiated_close: AtomicBool::new(false),
                ca_bundle_path: Mutex::new(String::new()),
                on_message: Mutex::new(None),
                on_connected: Mutex::new(None),
                on_disconnected: Mutex::new(None),
            }),
            sender: Mutex::new(None),
            worker: Mutex::new(None),
        }
    }

    pub fn set_ca_bundle_path(&self, path: &str) {
        *self
            .shared
            .ca_bundle_path
            .lock()
            .expect("ws connection poisoned") = path.to_string();
    }

    pub fn ca_bundle_path(&self) -> String {
        self.shared
            .ca_bundle_path
            .lock()
            .expect("ws connection poisoned")
            .clone()
    }

    pub fn state(&self) -> TransportState {
        match self.shared.state.load(Ordering::Relaxed) {
            1 => TransportState::Connecting,
            2 => TransportState::Connected,
            3 => TransportState::Disconnecting,
            _ => TransportState::Disconnected,
        }
    }

    pub fn transition_connecting(&self) -> bool {
        self.shared
            .user_initiated_close
            .store(false, Ordering::Relaxed);
        self.shared
            .state
            .compare_exchange(
                TransportState::Disconnected as u8,
                TransportState::Connecting as u8,
                Ordering::AcqRel,
                Ordering::Acquire,
            )
            .is_ok()
    }

    pub fn mark_connected(&self) {
        self.shared.mark_connected();
    }

    pub fn mark_disconnected(&self, close_code: u16, detail: &str, tls_failed: bool) {
        self.shared
            .mark_disconnected(close_code, detail, tls_failed);
    }

    pub fn mark_user_disconnect(&self) {
        self.shared
            .user_initiated_close
            .store(true, Ordering::Relaxed);
        self.shared
            .state
            .store(TransportState::Disconnecting as u8, Ordering::Release);
    }

    pub fn deliver_binary(&self, data: &[u8]) {
        self.shared.deliver_binary(data);
    }

    pub fn set_on_message<F>(&self, cb: F)
    where
        F: Fn(&[u8]) + Send + Sync + 'static,
    {
        *self
            .shared
            .on_message
            .lock()
            .expect("ws connection poisoned") = Some(Arc::new(cb));
    }

    pub fn set_on_connected<F>(&self, cb: F)
    where
        F: Fn() + Send + Sync + 'static,
    {
        *self
            .shared
            .on_connected
            .lock()
            .expect("ws connection poisoned") = Some(Arc::new(cb));
    }

    pub fn set_on_disconnected<F>(&self, cb: F)
    where
        F: Fn(TransportDisconnectReason, &str) + Send + Sync + 'static,
    {
        *self
            .shared
            .on_disconnected
            .lock()
            .expect("ws connection poisoned") = Some(Arc::new(cb));
    }

    fn connect_worker(&self, url: &str) -> bool {
        if !self.transition_connecting() {
            return false;
        }
        let (tx, rx) = mpsc::channel();
        *self.sender.lock().expect("ws connection poisoned") = Some(tx);
        let shared = Arc::clone(&self.shared);
        let url = url.to_string();
        let worker = thread::spawn(move || run_ws_worker(shared, url, rx));
        *self.worker.lock().expect("ws connection poisoned") = Some(worker);
        true
    }
}

impl Transport for WsConnection {
    fn connect(&mut self, url: &str) -> bool {
        self.connect_worker(url)
    }

    fn send(&mut self, data: &[u8]) -> bool {
        if self.state() != TransportState::Connected {
            return false;
        }
        self.sender
            .lock()
            .expect("ws connection poisoned")
            .as_ref()
            .is_some_and(|tx| tx.send(WsCommand::Send(data.to_vec())).is_ok())
    }

    fn disconnect(&mut self) {
        if self.state() == TransportState::Disconnected {
            return;
        }
        self.mark_user_disconnect();
        let sent = self
            .sender
            .lock()
            .expect("ws connection poisoned")
            .as_ref()
            .is_some_and(|tx| tx.send(WsCommand::Disconnect).is_ok());
        if !sent {
            self.mark_disconnected(NORMAL_CLOSE_CODE, "client disconnect", false);
        }
    }

    fn state(&self) -> TransportState {
        WsConnection::state(self)
    }

    fn set_on_message(&mut self, cb: Box<dyn Fn(&[u8]) + Send + Sync>) {
        *self
            .shared
            .on_message
            .lock()
            .expect("ws connection poisoned") = Some(Arc::from(cb));
    }

    fn set_on_connected(&mut self, cb: Box<dyn Fn() + Send + Sync>) {
        *self
            .shared
            .on_connected
            .lock()
            .expect("ws connection poisoned") = Some(Arc::from(cb));
    }

    fn set_on_disconnected(
        &mut self,
        cb: Box<dyn Fn(TransportDisconnectReason, &str) + Send + Sync>,
    ) {
        *self
            .shared
            .on_disconnected
            .lock()
            .expect("ws connection poisoned") = Some(Arc::from(cb));
    }

    fn set_ca_bundle_path(&mut self, path: &str) {
        WsConnection::set_ca_bundle_path(self, path);
    }
}

impl Default for WsConnection {
    fn default() -> Self {
        Self::new()
    }
}

impl Drop for WsConnection {
    fn drop(&mut self) {
        self.disconnect();
    }
}

impl WsConnectionShared {
    fn mark_connected(&self) {
        self.state
            .store(TransportState::Connected as u8, Ordering::Release);
        if let Some(cb) = self
            .on_connected
            .lock()
            .expect("ws connection poisoned")
            .clone()
        {
            cb();
        }
    }

    fn mark_disconnected(&self, close_code: u16, detail: &str, tls_failed: bool) {
        self.state
            .store(TransportState::Disconnected as u8, Ordering::Release);
        let reason = if self.user_initiated_close.load(Ordering::Relaxed) {
            TransportDisconnectReason::UserInitiated
        } else {
            map_close_reason(close_code, tls_failed)
        };
        if let Some(cb) = self
            .on_disconnected
            .lock()
            .expect("ws connection poisoned")
            .clone()
        {
            cb(reason, detail);
        }
    }

    fn deliver_binary(&self, data: &[u8]) {
        if let Some(cb) = self
            .on_message
            .lock()
            .expect("ws connection poisoned")
            .clone()
        {
            cb(data);
        }
    }
}

fn run_ws_worker(shared: Arc<WsConnectionShared>, url: String, rx: mpsc::Receiver<WsCommand>) {
    let (mut socket, _) = match connect(url.as_str()) {
        Ok(connection) => connection,
        Err(err) => {
            let detail = err.to_string();
            let reason = map_error_reason(&detail, false);
            shared.mark_disconnected(reason_to_close_code(reason), &detail, false);
            return;
        }
    };
    let _ = set_read_timeout(socket.get_mut(), Some(READ_POLL_TIMEOUT));
    shared.mark_connected();

    loop {
        while let Ok(cmd) = rx.try_recv() {
            match cmd {
                WsCommand::Send(data) => {
                    if let Err(err) = socket.send(Message::Binary(data.into())) {
                        let detail = err.to_string();
                        let reason = map_error_reason(&detail, false);
                        shared.mark_disconnected(reason_to_close_code(reason), &detail, false);
                        return;
                    }
                }
                WsCommand::Disconnect => {
                    let _ = socket.close(None);
                    shared.mark_disconnected(NORMAL_CLOSE_CODE, "client disconnect", false);
                    return;
                }
            }
        }

        match socket.read() {
            Ok(Message::Binary(bytes)) => shared.deliver_binary(&bytes),
            Ok(Message::Close(frame)) => {
                let detail = frame
                    .as_ref()
                    .map(|frame| frame.reason.to_string())
                    .unwrap_or_else(|| "remote close".to_string());
                let code = frame
                    .map(|frame| u16::from(frame.code))
                    .unwrap_or(NORMAL_CLOSE_CODE);
                shared.mark_disconnected(code, &detail, false);
                return;
            }
            Ok(Message::Ping(_) | Message::Pong(_) | Message::Text(_) | Message::Frame(_)) => {}
            Err(err) if is_timeout_error(&err) => {}
            Err(WsError::ConnectionClosed | WsError::AlreadyClosed) => {
                shared.mark_disconnected(NORMAL_CLOSE_CODE, "remote close", false);
                return;
            }
            Err(err) => {
                let detail = err.to_string();
                let reason = map_error_reason(&detail, false);
                shared.mark_disconnected(reason_to_close_code(reason), &detail, false);
                return;
            }
        }
    }
}

fn set_read_timeout(
    stream: &mut MaybeTlsStream<std::net::TcpStream>,
    timeout: Option<Duration>,
) -> std::io::Result<()> {
    match stream {
        MaybeTlsStream::Plain(stream) => stream.set_read_timeout(timeout),
        MaybeTlsStream::Rustls(stream) => stream.sock.set_read_timeout(timeout),
        #[allow(unreachable_patterns)]
        _ => Ok(()),
    }
}

fn is_timeout_error(err: &WsError) -> bool {
    matches!(err, WsError::Io(io) if matches!(
        io.kind(),
        std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
    ))
}

fn reason_to_close_code(reason: TransportDisconnectReason) -> u16 {
    match reason {
        TransportDisconnectReason::RemoteClose | TransportDisconnectReason::UserInitiated => {
            NORMAL_CLOSE_CODE
        }
        _ => 0,
    }
}

pub fn map_close_reason(close_code: u16, tls_handshake_failed: bool) -> TransportDisconnectReason {
    if tls_handshake_failed {
        TransportDisconnectReason::TlsHandshakeFailed
    } else if (1000..1016).contains(&close_code) {
        TransportDisconnectReason::RemoteClose
    } else {
        TransportDisconnectReason::Unknown
    }
}

pub fn map_error_reason(reason: &str, user_initiated: bool) -> TransportDisconnectReason {
    if user_initiated {
        return TransportDisconnectReason::UserInitiated;
    }
    let tls_failed = reason.contains("tls")
        || reason.contains("TLS")
        || reason.contains("SSL")
        || reason.contains("certificate");
    map_close_reason(0, tls_failed)
}

pub fn normal_close_code() -> u16 {
    NORMAL_CLOSE_CODE
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc;

    #[test]
    fn maps_close_reasons_like_cpp() {
        assert_eq!(
            map_close_reason(0, true),
            TransportDisconnectReason::TlsHandshakeFailed
        );
        assert_eq!(
            map_close_reason(1000, false),
            TransportDisconnectReason::RemoteClose
        );
        assert_eq!(
            map_close_reason(2000, false),
            TransportDisconnectReason::Unknown
        );
        assert_eq!(normal_close_code(), 1000);
        assert_eq!(
            map_error_reason("TLS certificate verify failed", false),
            TransportDisconnectReason::TlsHandshakeFailed
        );
        assert_eq!(
            map_error_reason("connection reset", false),
            TransportDisconnectReason::Unknown
        );
        assert_eq!(
            map_error_reason("connection reset", true),
            TransportDisconnectReason::UserInitiated
        );
    }

    #[test]
    fn transitions_and_callbacks_fire() {
        let ws = WsConnection::new();
        let (tx, rx) = mpsc::channel();
        ws.set_on_connected(move || tx.send("connected").unwrap());
        assert!(ws.transition_connecting());
        assert!(!ws.transition_connecting());
        ws.mark_connected();
        assert_eq!(ws.state(), TransportState::Connected);
        assert_eq!(rx.recv().unwrap(), "connected");
    }
}
