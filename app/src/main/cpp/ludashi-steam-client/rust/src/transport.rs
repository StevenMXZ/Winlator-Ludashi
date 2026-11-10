#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum TransportState {
    Disconnected,
    Connecting,
    Connected,
    Disconnecting,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum TransportDisconnectReason {
    UserInitiated,
    RemoteClose,
    TlsHandshakeFailed,
    NetworkError,
    HandshakeTimeout,
    Unknown,
}

pub type MessageCallback = Box<dyn Fn(&[u8]) + Send + Sync>;
pub type ConnectedCallback = Box<dyn Fn() + Send + Sync>;
pub type DisconnectedCallback = Box<dyn Fn(TransportDisconnectReason, &str) + Send + Sync>;

pub trait Transport: Send {
    fn connect(&mut self, url: &str) -> bool;
    fn send(&mut self, data: &[u8]) -> bool;
    fn disconnect(&mut self);
    fn state(&self) -> TransportState;

    fn set_on_message(&mut self, cb: MessageCallback);
    fn set_on_connected(&mut self, cb: ConnectedCallback);
    fn set_on_disconnected(&mut self, cb: DisconnectedCallback);

    fn set_ca_bundle_path(&mut self, _path: &str) {}
}
