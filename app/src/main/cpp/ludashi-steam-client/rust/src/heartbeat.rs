use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;
use std::{panic, panic::AssertUnwindSafe};

#[derive(Default)]
pub struct Heartbeat {
    running: Arc<AtomicBool>,
    stop: Arc<(Mutex<bool>, Condvar)>,
    worker: Option<JoinHandle<()>>,
}

impl Heartbeat {
    pub fn start<F>(&mut self, interval: Duration, cb: F) -> bool
    where
        F: Fn() + Send + 'static,
    {
        if interval.is_zero() {
            return false;
        }
        self.stop();
        self.running.store(true, Ordering::Relaxed);
        {
            let (mu, _) = &*self.stop;
            *mu.lock().expect("heartbeat poisoned") = false;
        }
        let running = Arc::clone(&self.running);
        let stop = Arc::clone(&self.stop);
        self.worker = Some(thread::spawn(move || run_loop(interval, cb, running, stop)));
        true
    }

    pub fn stop(&mut self) {
        if !self.running.load(Ordering::Relaxed) {
            return;
        }
        let (mu, cv) = &*self.stop;
        *mu.lock().expect("heartbeat poisoned") = true;
        cv.notify_all();
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
        self.running.store(false, Ordering::Relaxed);
    }

    pub fn running(&self) -> bool {
        self.running.load(Ordering::Relaxed)
    }
}

impl Drop for Heartbeat {
    fn drop(&mut self) {
        self.stop();
    }
}

fn run_loop<F>(
    interval: Duration,
    cb: F,
    running: Arc<AtomicBool>,
    stop: Arc<(Mutex<bool>, Condvar)>,
) where
    F: Fn() + Send + 'static,
{
    let (mu, cv) = &*stop;
    loop {
        let guard = mu.lock().expect("heartbeat poisoned");
        let (guard, _) = cv
            .wait_timeout_while(guard, interval, |stop_requested| !*stop_requested)
            .expect("heartbeat poisoned");
        if *guard {
            return;
        }
        drop(guard);
        let _ = panic::catch_unwind(AssertUnwindSafe(&cb));
        if !running.load(Ordering::Relaxed) {
            return;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    #[test]
    fn rejects_zero_interval() {
        let mut hb = Heartbeat::default();
        assert!(!hb.start(Duration::ZERO, || {}));
        assert!(!hb.running());
    }

    #[test]
    fn ticks_and_stops() {
        let ticks = Arc::new(AtomicUsize::new(0));
        let tick_copy = Arc::clone(&ticks);
        let mut hb = Heartbeat::default();
        assert!(hb.start(Duration::from_millis(10), move || {
            tick_copy.fetch_add(1, Ordering::Relaxed);
        }));
        thread::sleep(Duration::from_millis(35));
        hb.stop();
        assert!(ticks.load(Ordering::Relaxed) > 0);
        assert!(!hb.running());
    }

    #[test]
    fn callback_panic_does_not_stop_worker() {
        let ticks = Arc::new(AtomicUsize::new(0));
        let tick_copy = Arc::clone(&ticks);
        let (tx, rx) = std::sync::mpsc::channel();
        let mut hb = Heartbeat::default();
        assert!(hb.start(Duration::from_millis(10), move || {
            let n = tick_copy.fetch_add(1, Ordering::Relaxed);
            if n == 0 {
                panic!("first heartbeat panic");
            }
            tx.send(()).unwrap();
        }));
        rx.recv_timeout(Duration::from_secs(1)).unwrap();
        hb.stop();
        assert!(ticks.load(Ordering::Relaxed) > 1);
    }
}
