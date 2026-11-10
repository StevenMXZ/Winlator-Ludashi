use std::io::Read;
use std::net::{Shutdown, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

type ClientObserver = Arc<dyn Fn(u16, String, Vec<u8>) + Send + Sync + 'static>;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct WineBridgeConfig {
    pub bind_host: String,
    pub steam3_port: u16,
    pub client_svc_port: u16,
    pub snoop_bytes: usize,
}

impl Default for WineBridgeConfig {
    fn default() -> Self {
        Self {
            bind_host: "127.0.0.1".to_string(),
            steam3_port: 57343,
            client_svc_port: 57344,
            snoop_bytes: 64,
        }
    }
}

#[derive(Default)]
pub struct WineBridge {
    running: Arc<AtomicBool>,
    last_error: Arc<Mutex<String>>,
    observer: Arc<Mutex<Option<ClientObserver>>>,
    threads: Vec<JoinHandle<()>>,
}

impl WineBridge {
    pub fn start(&mut self, config: WineBridgeConfig) -> bool {
        if self.running.load(Ordering::Relaxed) {
            return true;
        }
        self.stop();
        let steam3 = match TcpListener::bind((config.bind_host.as_str(), config.steam3_port)) {
            Ok(listener) => listener,
            Err(err) => {
                self.set_error(format!(
                    "bind({}:{}): {err}",
                    config.bind_host, config.steam3_port
                ));
                return false;
            }
        };
        let client = match TcpListener::bind((config.bind_host.as_str(), config.client_svc_port)) {
            Ok(listener) => listener,
            Err(err) => {
                self.set_error(format!(
                    "bind({}:{}): {err}",
                    config.bind_host, config.client_svc_port
                ));
                return false;
            }
        };
        self.running.store(true, Ordering::Relaxed);
        self.threads.push(spawn_listener(
            steam3,
            config.steam3_port,
            config.snoop_bytes,
            Arc::clone(&self.running),
            Arc::clone(&self.observer),
        ));
        self.threads.push(spawn_listener(
            client,
            config.client_svc_port,
            config.snoop_bytes,
            Arc::clone(&self.running),
            Arc::clone(&self.observer),
        ));
        true
    }

    pub fn stop(&mut self) {
        self.running.store(false, Ordering::Relaxed);
        for handle in self.threads.drain(..) {
            let _ = handle.join();
        }
    }

    pub fn running(&self) -> bool {
        self.running.load(Ordering::Relaxed)
    }

    pub fn last_error(&self) -> String {
        self.last_error
            .lock()
            .expect("wine bridge poisoned")
            .clone()
    }

    pub fn set_observer<F>(&self, observer: F)
    where
        F: Fn(u16, String, Vec<u8>) + Send + Sync + 'static,
    {
        *self.observer.lock().expect("wine bridge poisoned") = Some(Arc::new(observer));
    }

    fn set_error(&self, error: String) {
        *self.last_error.lock().expect("wine bridge poisoned") = error;
    }
}

impl Drop for WineBridge {
    fn drop(&mut self) {
        self.stop();
    }
}

fn spawn_listener(
    listener: TcpListener,
    port: u16,
    snoop_bytes: usize,
    running: Arc<AtomicBool>,
    observer: Arc<Mutex<Option<ClientObserver>>>,
) -> JoinHandle<()> {
    thread::spawn(move || {
        let _ = listener.set_nonblocking(true);
        while running.load(Ordering::Relaxed) {
            match listener.accept() {
                Ok((stream, _)) => handle_connection(stream, port, snoop_bytes, &observer),
                Err(err) if err.kind() == std::io::ErrorKind::WouldBlock => {
                    thread::sleep(Duration::from_millis(25));
                }
                Err(_) => break,
            }
        }
    })
}

fn handle_connection(
    mut stream: TcpStream,
    port: u16,
    snoop_bytes: usize,
    observer: &Arc<Mutex<Option<ClientObserver>>>,
) {
    let peer = stream
        .peer_addr()
        .map(|addr| addr.to_string())
        .unwrap_or_default();
    let mut first = vec![0u8; snoop_bytes];
    let n = stream.read(&mut first).unwrap_or(0);
    first.truncate(n);
    let cb = observer.lock().expect("wine bridge poisoned").clone();
    if let Some(cb) = cb {
        cb(port, peer, first);
    }
    let _ = stream.shutdown(Shutdown::Both);
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::net::TcpListener;
    use std::sync::mpsc;

    #[test]
    fn default_ports_match_cpp_bridge() {
        let cfg = WineBridgeConfig::default();
        assert_eq!(cfg.steam3_port, 57343);
        assert_eq!(cfg.client_svc_port, 57344);
    }

    #[test]
    fn observes_first_bytes_on_connection() {
        let port1 = free_port();
        let port2 = free_port();
        let mut bridge = WineBridge::default();
        let (tx, rx) = mpsc::channel();
        bridge.set_observer(move |port, peer, first| {
            tx.send((port, peer, first)).unwrap();
        });
        assert!(bridge.start(WineBridgeConfig {
            steam3_port: port1,
            client_svc_port: port2,
            ..Default::default()
        }));
        let mut stream = TcpStream::connect(("127.0.0.1", port1)).unwrap();
        stream.write_all(b"abcdef").unwrap();
        let (port, peer, first) = rx.recv_timeout(Duration::from_secs(2)).unwrap();
        assert_eq!(port, port1);
        assert!(peer.starts_with("127.0.0.1:"));
        assert_eq!(first, b"abcdef");
        bridge.stop();
    }

    fn free_port() -> u16 {
        TcpListener::bind(("127.0.0.1", 0))
            .unwrap()
            .local_addr()
            .unwrap()
            .port()
    }
}
