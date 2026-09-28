//! Serialises tests that mutate process-global environment variables.
//!
//! `env::set_var` is process-wide, so two tests that touch the same key (or read a
//! value another test just wrote) race unless they hold a shared lock for their whole
//! duration.  The suite has many such tests — timeouts, mirrors, locale, runtime dirs,
//! `PATH` — and the races show up as one-off `left == right` failures under load.
//!
//! The lock is **reentrant per thread**: a single test may take several scoped guards
//! (`set_env(a, ..)` followed by `set_env(b, ..)`) without deadlocking on a
//! non-reentrant mutex.  Each test runs on its own thread, so the depth counter never
//! leaks between tests.
//!
//! This module is declared by **both** crate roots (`lib.rs` and `main.rs`) because
//! `daemon.rs` is compiled into both targets and reaches the lock as
//! `crate::test_env`.  A single definition inside `main.rs` would leave the library
//! test target (`cargo test --lib`) unable to resolve it.

use std::cell::Cell;
use std::sync::{Mutex, MutexGuard};

static ENV_LOCK: Mutex<()> = Mutex::new(());

thread_local! {
    static DEPTH: Cell<usize> = const { Cell::new(0) };
}

/// Held for the duration of an env-mutating scope.
pub(crate) struct EnvLock {
    _guard: Option<MutexGuard<'static, ()>>,
}

/// Acquire the shared environment lock, tolerating a poisoned mutex from a test
/// that panicked while holding it.
pub(crate) fn lock() -> EnvLock {
    let depth = DEPTH.with(|depth| {
        let current = depth.get();
        depth.set(current + 1);
        current
    });

    if depth == 0 {
        EnvLock {
            _guard: Some(ENV_LOCK.lock().unwrap_or_else(|poisoned| poisoned.into_inner())),
        }
    } else {
        EnvLock { _guard: None }
    }
}

impl Drop for EnvLock {
    fn drop(&mut self) {
        DEPTH.with(|depth| depth.set(depth.get().saturating_sub(1)));
        // `_guard` drops right after this, releasing the lock for the outermost holder.
    }
}