use std::collections::HashMap;
use std::sync::Mutex;
use std::time::Instant;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct OwnedAppTicket {
    pub app_id: u32,
    pub ticket: Vec<u8>,
    pub eresult: u32,
    pub fetched_at: Instant,
}

#[derive(Debug, Default)]
pub struct LudashiTicketCache {
    cache: Mutex<HashMap<u32, OwnedAppTicket>>,
}

impl LudashiTicketCache {
    pub fn store(&self, app_id: u32, eresult: u32, ticket: Vec<u8>) {
        let mut cache = self.cache.lock().expect("ticket cache poisoned");
        cache.insert(
            app_id,
            OwnedAppTicket {
                app_id,
                ticket,
                eresult,
                fetched_at: Instant::now(),
            },
        );
    }

    pub fn get(&self, app_id: u32) -> Option<OwnedAppTicket> {
        self.cache
            .lock()
            .expect("ticket cache poisoned")
            .get(&app_id)
            .cloned()
    }

    pub fn has(&self, app_id: u32) -> bool {
        self.cache
            .lock()
            .expect("ticket cache poisoned")
            .contains_key(&app_id)
    }

    pub fn size(&self) -> usize {
        self.cache.lock().expect("ticket cache poisoned").len()
    }

    pub fn clear(&self) {
        self.cache.lock().expect("ticket cache poisoned").clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stores_gets_and_clears_tickets() {
        let cache = LudashiTicketCache::default();
        cache.store(480, 1, vec![1, 2, 3]);
        assert!(cache.has(480));
        assert_eq!(cache.size(), 1);
        assert_eq!(cache.get(480).unwrap().ticket, [1, 2, 3]);
        cache.clear();
        assert!(!cache.has(480));
    }
}
