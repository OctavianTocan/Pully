use super::*;

#[test]
fn a_normal_twenty_repository_page_does_not_alias_bounded_ownership_slots() {
    let mut fence = RepositoryFence::default();
    let leases = (0..20)
        .map(|id| {
            let scope = format!("account:owner/project-{id}");
            let opening = fence.open(&scope).unwrap();
            let (lease, _) = fence.confirm(&scope, opening, id + 1).unwrap();
            (scope, lease)
        })
        .collect::<Vec<_>>();
    for (scope, lease) in leases {
        assert!(fence.current(&scope, lease));
    }
}

#[test]
fn revisiting_a_repository_protects_it_from_older_inactive_slot_reuse() {
    let mut fence = RepositoryFence::default();
    for id in 0..64 {
        let scope = format!("public:owner/project-{id}");
        let probe = fence.open(&scope).unwrap();
        fence.confirm(&scope, probe, id + 1).unwrap();
    }
    let scope = "public:owner/project-0";
    let probe = fence.open(scope).unwrap();
    let (active, _) = fence.confirm(scope, probe, 1).unwrap();
    for id in 64..84 {
        let other = format!("public:owner/project-{id}");
        let probe = fence.open(&other).unwrap();
        fence.confirm(&other, probe, id + 1).unwrap();
    }
    assert!(fence.current(scope, active));
}

#[test]
fn stale_repository_probes_and_leases_cannot_retire_the_replacement() {
    let mut fence = RepositoryFence::default();
    let scope = "account:owner/project";
    let old_probe = fence.open(scope).unwrap();
    let (old, _) = fence.confirm(scope, old_probe, 42).unwrap();
    let replacement = fence.open(scope).unwrap();
    let (current, changed) = fence.confirm(scope, replacement, 99).unwrap();
    assert!(changed);
    assert!(!fence.current(scope, old));
    assert!(fence.confirm(scope, old_probe, 42).is_none());
    fence.deny(scope, old_probe);
    fence.retire(scope, old);
    assert!(fence.current(scope, current));
    let before_denial = fence.open(scope).unwrap();
    fence.deny(scope, before_denial);
    assert!(!fence.current(scope, current));
    assert!(fence.confirm(scope, before_denial, 99).is_none());
}
