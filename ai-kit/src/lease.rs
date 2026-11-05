//! The lease a login's sign-in is under while a ThyMind feedback sandbox has it checked out of
//! its `AgentHome`. Plain arithmetic on Unix milliseconds, so it is tested natively.

/// How long a checkout lasts unless it is checked in first. A feedback round is stopped at 45
/// minutes, so a live round always checks in before this ends.
pub const LEASE_MS: u64 = 60 * 60_000;

/// Whether a login is checked out at a moment.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Lease {
    /// Nothing holds the login, or the last lease ran out.
    Free,
    /// A checkout holds the login until this moment.
    Busy(u64),
}

impl Lease {
    /// The lease at `now`, given the stored `busy_until` (absent when nothing is checked out).
    ///
    /// @param now the current time in Unix milliseconds
    /// @param busy_until the end of the last checkout, if one is stored
    /// @returns `Busy` while that end is in the future, otherwise `Free`
    pub fn check(now: u64, busy_until: Option<u64>) -> Lease {
        match busy_until {
            Some(until) if until > now => Lease::Busy(until),
            _ => Lease::Free,
        }
    }

    /// Takes the login for a new checkout.
    ///
    /// @param now the current time in Unix milliseconds
    /// @param busy_until the end of the last checkout, if one is stored
    /// @returns the new end to store, or `Err(Busy)` with the running lease while one holds it
    pub fn take(now: u64, busy_until: Option<u64>) -> Result<u64, Lease> {
        match Lease::check(now, busy_until) {
            Lease::Free => Ok(now + LEASE_MS),
            busy => Err(busy),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn take_when_free() {
        assert_eq!(Lease::take(1_000, None), Ok(1_000 + LEASE_MS));
    }

    #[test]
    fn refuse_when_busy() {
        assert_eq!(Lease::check(1_000, Some(5_000)), Lease::Busy(5_000));
        assert_eq!(Lease::take(1_000, Some(5_000)), Err(Lease::Busy(5_000)));
    }

    #[test]
    fn free_after_expiry() {
        assert_eq!(Lease::check(5_000, Some(5_000)), Lease::Free);
        assert_eq!(Lease::take(6_000, Some(5_000)), Ok(6_000 + LEASE_MS));
    }

    #[test]
    fn checkin_clears() {
        // A checkin deletes the stored end, which reads as no lease at all.
        assert_eq!(Lease::check(1_000, None), Lease::Free);
    }
}
