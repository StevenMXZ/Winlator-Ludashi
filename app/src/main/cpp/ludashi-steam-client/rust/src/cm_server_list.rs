use crate::cm_server::{CmServer, CmTransport};
use std::sync::Mutex;
use std::time::{Duration, Instant};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ServerQuality {
    Good,
    Bad,
}

pub const DEFAULT_BAD_MEMORY: Duration = Duration::from_secs(5 * 60);

pub struct CmServerList {
    inner: Mutex<Inner>,
    bad_memory: Duration,
}

#[derive(Default)]
struct Inner {
    entries: Vec<Entry>,
    next_index: usize,
}

struct Entry {
    server: CmServer,
    quality: ServerQuality,
    marked_bad_at: Option<Instant>,
}

impl Default for CmServerList {
    fn default() -> Self {
        Self::new(DEFAULT_BAD_MEMORY)
    }
}

impl CmServerList {
    pub fn new(bad_memory: Duration) -> Self {
        Self {
            inner: Mutex::new(Inner::default()),
            bad_memory,
        }
    }

    pub fn replace_all(&self, servers: &[CmServer]) {
        let mut inner = self.inner.lock().unwrap();
        inner.entries = servers
            .iter()
            .cloned()
            .map(|server| Entry {
                server,
                quality: ServerQuality::Good,
                marked_bad_at: None,
            })
            .collect();
        inner.next_index = 0;
    }

    pub fn add(&self, server: CmServer) {
        self.inner.lock().unwrap().entries.push(Entry {
            server,
            quality: ServerQuality::Good,
            marked_bad_at: None,
        });
    }

    pub fn size(&self) -> usize {
        self.inner.lock().unwrap().entries.len()
    }

    pub fn next_good(&self) -> Option<CmServer> {
        let mut inner = self.inner.lock().unwrap();
        if inner.entries.is_empty() {
            return None;
        }
        promote_expired(&mut inner.entries, self.bad_memory);
        let n = inner.entries.len();
        for i in 0..n {
            let idx = (inner.next_index + i) % n;
            if inner.entries[idx].quality == ServerQuality::Good {
                inner.next_index = (idx + 1) % n;
                return Some(inner.entries[idx].server.clone());
            }
        }
        None
    }

    pub fn mark_bad(&self, endpoint: &str) {
        let mut inner = self.inner.lock().unwrap();
        if let Some(entry) = inner
            .entries
            .iter_mut()
            .find(|e| e.server.endpoint == endpoint)
        {
            entry.quality = ServerQuality::Bad;
            entry.marked_bad_at = Some(Instant::now());
        }
    }

    pub fn mark_good(&self, endpoint: &str) {
        let mut inner = self.inner.lock().unwrap();
        if let Some(entry) = inner
            .entries
            .iter_mut()
            .find(|e| e.server.endpoint == endpoint)
        {
            entry.quality = ServerQuality::Good;
            entry.marked_bad_at = None;
        }
    }

    pub fn reset_quality(&self) {
        let mut inner = self.inner.lock().unwrap();
        for entry in &mut inner.entries {
            entry.quality = ServerQuality::Good;
            entry.marked_bad_at = None;
        }
    }
}

fn promote_expired(entries: &mut [Entry], bad_memory: Duration) {
    let now = Instant::now();
    for entry in entries {
        if entry.quality == ServerQuality::Bad
            && entry
                .marked_bad_at
                .is_some_and(|t| now.duration_since(t) >= bad_memory)
        {
            entry.quality = ServerQuality::Good;
            entry.marked_bad_at = None;
        }
    }
}

pub fn hardcoded_fallback_servers() -> Vec<CmServer> {
    const FALLBACK: &[(&str, &str)] = &[
        ("ext1-sea1.steamserver.net", "sea1"),
        ("ext2-sea1.steamserver.net", "sea1"),
        ("ext1-iad1.steamserver.net", "iad1"),
        ("ext2-iad1.steamserver.net", "iad1"),
        ("ext1-fra1.steamserver.net", "fra1"),
        ("ext2-fra1.steamserver.net", "fra1"),
        ("ext1-lax1.steamserver.net", "lax1"),
        ("ext1-sgp1.steamserver.net", "sgp1"),
    ];
    FALLBACK
        .iter()
        .map(|(host, dc)| CmServer {
            endpoint: format!("{host}:443"),
            host: (*host).to_string(),
            port: 443,
            transport: CmTransport::WebSocket,
            realm: "steamglobal".to_string(),
            datacenter: (*dc).to_string(),
            load: 0,
            weighted_load: 0.0,
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn next_good_round_robins_and_skips_bad() {
        let list = CmServerList::default();
        let a = server("a:443");
        let b = server("b:443");
        list.replace_all(&[a.clone(), b.clone()]);
        assert_eq!(list.next_good().unwrap().endpoint, a.endpoint);
        list.mark_bad(&b.endpoint);
        assert_eq!(list.next_good().unwrap().endpoint, a.endpoint);
        list.mark_good(&b.endpoint);
        assert_eq!(list.next_good().unwrap().endpoint, b.endpoint);
    }

    #[test]
    fn bad_servers_promote_after_memory_interval() {
        let list = CmServerList::new(Duration::ZERO);
        let a = server("a:443");
        list.replace_all(std::slice::from_ref(&a));
        list.mark_bad(&a.endpoint);
        assert_eq!(list.next_good().unwrap().endpoint, a.endpoint);
    }

    #[test]
    fn fallback_servers_are_websocket_urls() {
        let servers = hardcoded_fallback_servers();
        assert!(!servers.is_empty());
        assert!(servers
            .iter()
            .all(|s| s.websocket_url().starts_with("wss://")));
    }

    fn server(endpoint: &str) -> CmServer {
        let (host, port) = crate::cm_server::parse_endpoint(endpoint).unwrap();
        CmServer {
            endpoint: endpoint.to_string(),
            host,
            port,
            transport: CmTransport::WebSocket,
            ..Default::default()
        }
    }
}
