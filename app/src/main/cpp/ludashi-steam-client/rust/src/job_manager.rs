use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use std::{panic, panic::AssertUnwindSafe};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct JobResult {
    pub eresult: i32,
    pub error_message: String,
    pub body: Vec<u8>,
    pub synthetic_failure: bool,
}

impl Default for JobResult {
    fn default() -> Self {
        Self {
            eresult: 2,
            error_message: String::new(),
            body: Vec::new(),
            synthetic_failure: false,
        }
    }
}

type JobContinuation = Box<dyn FnOnce(JobResult) + Send + 'static>;

struct Entry {
    cb: JobContinuation,
    deadline: Instant,
}

#[derive(Default)]
struct Pending {
    jobs: HashMap<u64, Entry>,
}

pub struct JobManager {
    next_counter: AtomicU64,
    process_epoch: u64,
    default_timeout: Duration,
    pending: Arc<(Mutex<Pending>, Condvar)>,
    stop: Arc<AtomicBool>,
    timeout_thread: Option<JoinHandle<()>>,
}

impl JobManager {
    pub fn new(default_timeout: Duration) -> Self {
        let pending = Arc::new((Mutex::new(Pending::default()), Condvar::new()));
        let stop = Arc::new(AtomicBool::new(false));
        let thread_pending = Arc::clone(&pending);
        let thread_stop = Arc::clone(&stop);
        let timeout_thread = thread::spawn(move || timeout_loop(thread_pending, thread_stop));
        Self {
            next_counter: AtomicU64::new(1),
            process_epoch: make_process_epoch(),
            default_timeout,
            pending,
            stop,
            timeout_thread: Some(timeout_thread),
        }
    }

    pub fn next_job_id(&self) -> u64 {
        let lo = self.next_counter.fetch_add(1, Ordering::Relaxed);
        ((self.process_epoch & 0xFF_FFFF) << 40) | (lo & 0xFF_FFFF_FFFF)
    }

    pub fn track<F>(&self, job_id: u64, cb: F, timeout: Option<Duration>)
    where
        F: FnOnce(JobResult) + Send + 'static,
    {
        let timeout = timeout.unwrap_or(self.default_timeout);
        let (mu, cv) = &*self.pending;
        let mut pending = mu.lock().expect("job manager poisoned");
        pending.jobs.insert(
            job_id,
            Entry {
                cb: Box::new(cb),
                deadline: Instant::now() + timeout,
            },
        );
        cv.notify_all();
    }

    pub fn deliver(
        &self,
        job_id_target: u64,
        mut eresult: i32,
        error_message: String,
        body: &[u8],
    ) {
        let cb = {
            let (mu, _) = &*self.pending;
            let mut pending = mu.lock().expect("job manager poisoned");
            pending.jobs.remove(&job_id_target).map(|entry| entry.cb)
        };
        if let Some(cb) = cb {
            if eresult == -1 {
                eresult = 1;
            }
            invoke_continuation(
                cb,
                JobResult {
                    eresult,
                    error_message,
                    body: body.to_vec(),
                    synthetic_failure: false,
                },
            );
        }
    }

    pub fn fail_all(&self, reason: &str) {
        let drained = {
            let (mu, _) = &*self.pending;
            let mut pending = mu.lock().expect("job manager poisoned");
            std::mem::take(&mut pending.jobs)
        };
        for (_, entry) in drained {
            invoke_continuation(
                entry.cb,
                JobResult {
                    eresult: -1,
                    error_message: reason.to_string(),
                    body: Vec::new(),
                    synthetic_failure: true,
                },
            );
        }
    }
}

impl Default for JobManager {
    fn default() -> Self {
        Self::new(Duration::from_secs(30))
    }
}

impl Drop for JobManager {
    fn drop(&mut self) {
        {
            let _guard = self.pending.0.lock().expect("job manager poisoned");
            self.stop.store(true, Ordering::Relaxed);
            self.pending.1.notify_all();
        }
        if let Some(handle) = self.timeout_thread.take() {
            let _ = handle.join();
        }
        self.fail_all("JobManager shutting down");
    }
}

fn timeout_loop(pending: Arc<(Mutex<Pending>, Condvar)>, stop: Arc<AtomicBool>) {
    let (mu, cv) = &*pending;
    loop {
        let mut guard = mu.lock().expect("job manager poisoned");
        while guard.jobs.is_empty() && !stop.load(Ordering::Relaxed) {
            guard = cv.wait(guard).expect("job manager poisoned");
        }
        if stop.load(Ordering::Relaxed) {
            return;
        }
        let earliest = guard
            .jobs
            .values()
            .map(|entry| entry.deadline)
            .min()
            .unwrap_or_else(Instant::now);
        let now = Instant::now();
        if earliest > now {
            let (g, _) = cv
                .wait_timeout(guard, earliest - now)
                .expect("job manager poisoned");
            guard = g;
        }
        if stop.load(Ordering::Relaxed) {
            return;
        }
        let now = Instant::now();
        let expired_ids: Vec<u64> = guard
            .jobs
            .iter()
            .filter_map(|(id, entry)| (entry.deadline <= now).then_some(*id))
            .collect();
        let expired: Vec<Entry> = expired_ids
            .into_iter()
            .filter_map(|id| guard.jobs.remove(&id))
            .collect();
        drop(guard);
        for entry in expired {
            invoke_continuation(
                entry.cb,
                JobResult {
                    eresult: -1,
                    error_message: "job timeout".to_string(),
                    body: Vec::new(),
                    synthetic_failure: true,
                },
            );
        }
    }
}

fn invoke_continuation(cb: JobContinuation, result: JobResult) {
    let _ = panic::catch_unwind(AssertUnwindSafe(|| cb(result)));
}

fn make_process_epoch() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc;

    #[test]
    fn deliver_maps_missing_eresult_to_ok() {
        let jm = JobManager::new(Duration::from_secs(30));
        let id = jm.next_job_id();
        let (tx, rx) = mpsc::channel();
        jm.track(id, move |result| tx.send(result).unwrap(), None);
        jm.deliver(id, -1, String::new(), b"body");
        let result = rx.recv_timeout(Duration::from_secs(1)).unwrap();
        assert_eq!(result.eresult, 1);
        assert_eq!(result.body, b"body");
    }

    #[test]
    fn timeout_fails_jobs_synthetically() {
        let jm = JobManager::new(Duration::from_millis(20));
        let id = jm.next_job_id();
        let (tx, rx) = mpsc::channel();
        jm.track(id, move |result| tx.send(result).unwrap(), None);
        let result = rx.recv_timeout(Duration::from_secs(2)).unwrap();
        assert!(result.synthetic_failure);
        assert_eq!(result.error_message, "job timeout");
    }

    #[test]
    fn continuation_panic_is_caught() {
        let jm = JobManager::new(Duration::from_secs(30));
        let id = jm.next_job_id();
        jm.track(
            id,
            |_| {
                panic!("continuation panic");
            },
            None,
        );
        jm.deliver(id, 1, String::new(), b"body");
    }
}
