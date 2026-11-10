use crate::crypto::{
    aes256_cbc_decrypt, aes256_cbc_encrypt, aes256_ecb_decrypt_block, aes256_ecb_encrypt_block,
    hmac_sha1, secure_random_bytes, AesBlock, SecureSessionKey, SessionKey, AES_BLOCK_BYTES,
    HMAC_KEY_LENGTH,
};
use crate::transport::{Transport, TransportDisconnectReason};
use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::{Arc, Mutex};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum ChannelState {
    Disconnected,
    Connected,
    Challenged,
    Encrypted,
    Closing,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum ChannelDisconnectReason {
    UserInitiated,
    TransportError,
    HandshakeProtocolError,
    HandshakeFailed,
    EnvelopeDecryptFailed,
    HmacMismatch,
}

type MessageCallback = Box<dyn Fn(&[u8]) + Send + Sync + 'static>;
type ConnectedCallback = Box<dyn Fn() + Send + Sync + 'static>;
type DisconnectedCallback = Box<dyn Fn(ChannelDisconnectReason, &str) + Send + Sync + 'static>;

pub struct EncryptedChannel {
    inner: Arc<EncryptedChannelInner>,
    transport: Mutex<Box<dyn Transport>>,
}

struct EncryptedChannelInner {
    state: AtomicU8,
    on_message: Mutex<Option<MessageCallback>>,
    on_connected: Mutex<Option<ConnectedCallback>>,
    on_disconnected: Mutex<Option<DisconnectedCallback>>,
}

impl Default for EncryptedChannelInner {
    fn default() -> Self {
        Self {
            state: AtomicU8::new(ChannelState::Disconnected as u8),
            on_message: Mutex::new(None),
            on_connected: Mutex::new(None),
            on_disconnected: Mutex::new(None),
        }
    }
}

impl EncryptedChannel {
    pub fn new(mut transport: Box<dyn Transport>) -> Self {
        let inner = Arc::new(EncryptedChannelInner::default());

        let weak = Arc::downgrade(&inner);
        transport.set_on_connected(Box::new(move || {
            if let Some(inner) = weak.upgrade() {
                inner
                    .state
                    .store(ChannelState::Encrypted as u8, Ordering::Release);
                if let Some(callback) = inner
                    .on_connected
                    .lock()
                    .expect("encrypted channel callback poisoned")
                    .as_ref()
                {
                    callback();
                }
            }
        }));

        let weak = Arc::downgrade(&inner);
        transport.set_on_disconnected(Box::new(move |reason, detail| {
            if let Some(inner) = weak.upgrade() {
                inner
                    .state
                    .store(ChannelState::Disconnected as u8, Ordering::Release);
                let reason = match reason {
                    TransportDisconnectReason::UserInitiated => {
                        ChannelDisconnectReason::UserInitiated
                    }
                    _ => ChannelDisconnectReason::TransportError,
                };
                if let Some(callback) = inner
                    .on_disconnected
                    .lock()
                    .expect("encrypted channel callback poisoned")
                    .as_ref()
                {
                    callback(reason, detail);
                }
            }
        }));

        let weak = Arc::downgrade(&inner);
        transport.set_on_message(Box::new(move |bytes| {
            if let Some(inner) = weak.upgrade() {
                if inner.state() != ChannelState::Encrypted {
                    return;
                }
                if let Some(callback) = inner
                    .on_message
                    .lock()
                    .expect("encrypted channel callback poisoned")
                    .as_ref()
                {
                    callback(bytes);
                }
            }
        }));

        Self {
            inner,
            transport: Mutex::new(transport),
        }
    }

    pub fn connect(&self, url: &str) -> bool {
        if self
            .inner
            .state
            .compare_exchange(
                ChannelState::Disconnected as u8,
                ChannelState::Connected as u8,
                Ordering::AcqRel,
                Ordering::Acquire,
            )
            .is_err()
        {
            return false;
        }
        if !self
            .transport
            .lock()
            .expect("encrypted channel transport poisoned")
            .connect(url)
        {
            self.inner
                .state
                .store(ChannelState::Disconnected as u8, Ordering::Release);
            return false;
        }
        true
    }

    pub fn send(&self, plaintext: &[u8]) -> bool {
        if self.state() != ChannelState::Encrypted {
            return false;
        }
        self.transport
            .lock()
            .expect("encrypted channel transport poisoned")
            .send(plaintext)
    }

    pub fn disconnect(&self) {
        if self.state() == ChannelState::Disconnected {
            return;
        }
        self.inner
            .state
            .store(ChannelState::Closing as u8, Ordering::Release);
        self.transport
            .lock()
            .expect("encrypted channel transport poisoned")
            .disconnect();
    }

    pub fn state(&self) -> ChannelState {
        self.inner.state()
    }

    pub fn set_ca_bundle_path(&self, path: &str) {
        self.transport
            .lock()
            .expect("encrypted channel transport poisoned")
            .set_ca_bundle_path(path);
    }

    pub fn set_on_message<F>(&self, callback: F)
    where
        F: Fn(&[u8]) + Send + Sync + 'static,
    {
        *self
            .inner
            .on_message
            .lock()
            .expect("encrypted channel callback poisoned") = Some(Box::new(callback));
    }

    pub fn set_on_connected<F>(&self, callback: F)
    where
        F: Fn() + Send + Sync + 'static,
    {
        *self
            .inner
            .on_connected
            .lock()
            .expect("encrypted channel callback poisoned") = Some(Box::new(callback));
    }

    pub fn set_on_disconnected<F>(&self, callback: F)
    where
        F: Fn(ChannelDisconnectReason, &str) + Send + Sync + 'static,
    {
        *self
            .inner
            .on_disconnected
            .lock()
            .expect("encrypted channel callback poisoned") = Some(Box::new(callback));
    }
}

impl Drop for EncryptedChannel {
    fn drop(&mut self) {
        self.disconnect();
    }
}

impl EncryptedChannelInner {
    fn state(&self) -> ChannelState {
        match self.state.load(Ordering::Acquire) {
            1 => ChannelState::Connected,
            2 => ChannelState::Challenged,
            3 => ChannelState::Encrypted,
            4 => ChannelState::Closing,
            _ => ChannelState::Disconnected,
        }
    }
}

#[derive(Clone)]
pub struct EncryptedEnvelope {
    session_key: SecureSessionKey,
    hmac_key: [u8; HMAC_KEY_LENGTH],
}

impl EncryptedEnvelope {
    pub fn new(session_key: SessionKey) -> Self {
        let mut hmac_key = [0u8; HMAC_KEY_LENGTH];
        hmac_key.copy_from_slice(&session_key[..HMAC_KEY_LENGTH]);
        Self {
            session_key: SecureSessionKey::new(session_key),
            hmac_key,
        }
    }

    pub fn encrypt(&self, plaintext: &[u8]) -> Option<Vec<u8>> {
        let mut random_iv = [0u8; AES_BLOCK_BYTES - 4];
        if !secure_random_bytes(&mut random_iv) {
            return None;
        }
        self.encrypt_with_random_iv(plaintext, random_iv)
    }

    fn encrypt_with_random_iv(
        &self,
        plaintext: &[u8],
        random_iv: [u8; AES_BLOCK_BYTES - 4],
    ) -> Option<Vec<u8>> {
        let mut hmac_input = Vec::with_capacity(random_iv.len() + plaintext.len());
        hmac_input.extend_from_slice(&random_iv);
        hmac_input.extend_from_slice(plaintext);
        let hmac = hmac_sha1(&self.hmac_key, &hmac_input)?;

        let mut iv_plaintext = [0u8; AES_BLOCK_BYTES];
        iv_plaintext[..4].copy_from_slice(&hmac[..4]);
        iv_plaintext[4..].copy_from_slice(&random_iv);

        let iv_ciphertext = aes256_ecb_encrypt_block(&self.session_key.bytes, &iv_plaintext)?;
        let body = aes256_cbc_encrypt(&self.session_key.bytes, &iv_plaintext, plaintext)?;

        let mut out = Vec::with_capacity(AES_BLOCK_BYTES + body.len());
        out.extend_from_slice(&iv_ciphertext);
        out.extend_from_slice(&body);
        Some(out)
    }

    pub fn decrypt(&self, wire: &[u8]) -> Option<Vec<u8>> {
        if wire.len() < AES_BLOCK_BYTES * 2 {
            return None;
        }
        let iv_ciphertext: AesBlock = wire[..AES_BLOCK_BYTES].try_into().ok()?;
        let iv_plaintext = aes256_ecb_decrypt_block(&self.session_key.bytes, &iv_ciphertext)?;
        let plaintext = aes256_cbc_decrypt(
            &self.session_key.bytes,
            &iv_plaintext,
            &wire[AES_BLOCK_BYTES..],
        )?;

        let mut hmac_input = Vec::with_capacity((AES_BLOCK_BYTES - 4) + plaintext.len());
        hmac_input.extend_from_slice(&iv_plaintext[4..]);
        hmac_input.extend_from_slice(&plaintext);
        let expected = hmac_sha1(&self.hmac_key, &hmac_input)?;
        ct_equal(&iv_plaintext[..4], &expected[..4]).then_some(plaintext)
    }
}

fn ct_equal(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::transport::TransportState;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::mpsc;

    #[test]
    fn envelope_roundtrips_with_fixed_iv() {
        let key = [7u8; 32];
        let env = EncryptedEnvelope::new(key);
        let encrypted = env
            .encrypt_with_random_iv(b"hello steam", [3u8; AES_BLOCK_BYTES - 4])
            .unwrap();
        assert_ne!(encrypted, b"hello steam");
        assert_eq!(env.decrypt(&encrypted).unwrap(), b"hello steam");
    }

    #[test]
    fn envelope_rejects_hmac_mismatch() {
        let env = EncryptedEnvelope::new([7u8; 32]);
        let mut encrypted = env
            .encrypt_with_random_iv(b"hello steam", [3u8; AES_BLOCK_BYTES - 4])
            .unwrap();
        let last = encrypted.last_mut().unwrap();
        *last ^= 0x55;
        assert!(env.decrypt(&encrypted).is_none());
    }

    #[test]
    fn websocket_channel_skips_app_layer_handshake() {
        let shared = Arc::new(MockTransportState::default());
        let channel = EncryptedChannel::new(Box::new(MockTransport {
            shared: Arc::clone(&shared),
        }));
        let connected = Arc::new(AtomicBool::new(false));
        let connected_cb = Arc::clone(&connected);
        let (tx, rx) = mpsc::channel();
        channel.set_on_connected(move || {
            connected_cb.store(true, Ordering::SeqCst);
        });
        channel.set_on_message(move |bytes| {
            tx.send(bytes.to_vec()).unwrap();
        });

        assert!(channel.connect("wss://cm.example.com:443/cmsocket/"));
        assert_eq!(channel.state(), ChannelState::Connected);
        shared.fire_connected();
        assert_eq!(channel.state(), ChannelState::Encrypted);
        assert!(connected.load(Ordering::SeqCst));

        assert!(channel.send(b"client hello"));
        assert_eq!(shared.sent.lock().unwrap()[0], b"client hello");

        shared.fire_message(b"server frame");
        assert_eq!(rx.recv().unwrap(), b"server frame");
    }

    #[test]
    fn channel_maps_transport_disconnect_reasons() {
        let shared = Arc::new(MockTransportState::default());
        let channel = EncryptedChannel::new(Box::new(MockTransport {
            shared: Arc::clone(&shared),
        }));
        let (tx, rx) = mpsc::channel();
        channel.set_on_disconnected(move |reason, detail| {
            tx.send((reason, detail.to_string())).unwrap();
        });
        assert!(channel.connect("wss://cm.example.com:443/cmsocket/"));
        shared.fire_connected();
        shared.fire_disconnected(TransportDisconnectReason::NetworkError, "net down");
        assert_eq!(
            rx.recv().unwrap(),
            (
                ChannelDisconnectReason::TransportError,
                "net down".to_string()
            )
        );
        assert_eq!(channel.state(), ChannelState::Disconnected);
    }

    #[derive(Default)]
    struct MockTransportState {
        connected: Mutex<Option<crate::transport::ConnectedCallback>>,
        disconnected: Mutex<Option<crate::transport::DisconnectedCallback>>,
        message: Mutex<Option<crate::transport::MessageCallback>>,
        sent: Mutex<Vec<Vec<u8>>>,
        ca_bundle_path: Mutex<String>,
    }

    impl MockTransportState {
        fn fire_connected(&self) {
            if let Some(callback) = self.connected.lock().unwrap().as_ref() {
                callback();
            }
        }

        fn fire_disconnected(&self, reason: TransportDisconnectReason, detail: &str) {
            if let Some(callback) = self.disconnected.lock().unwrap().as_ref() {
                callback(reason, detail);
            }
        }

        fn fire_message(&self, bytes: &[u8]) {
            if let Some(callback) = self.message.lock().unwrap().as_ref() {
                callback(bytes);
            }
        }
    }

    struct MockTransport {
        shared: Arc<MockTransportState>,
    }

    impl Transport for MockTransport {
        fn connect(&mut self, _url: &str) -> bool {
            true
        }

        fn send(&mut self, data: &[u8]) -> bool {
            self.shared.sent.lock().unwrap().push(data.to_vec());
            true
        }

        fn disconnect(&mut self) {}

        fn state(&self) -> TransportState {
            TransportState::Connected
        }

        fn set_on_message(&mut self, cb: Box<dyn Fn(&[u8]) + Send + Sync>) {
            *self.shared.message.lock().unwrap() = Some(cb);
        }

        fn set_on_connected(&mut self, cb: Box<dyn Fn() + Send + Sync>) {
            *self.shared.connected.lock().unwrap() = Some(cb);
        }

        fn set_on_disconnected(
            &mut self,
            cb: Box<dyn Fn(TransportDisconnectReason, &str) + Send + Sync>,
        ) {
            *self.shared.disconnected.lock().unwrap() = Some(cb);
        }

        fn set_ca_bundle_path(&mut self, path: &str) {
            *self.shared.ca_bundle_path.lock().unwrap() = path.to_string();
        }
    }
}
