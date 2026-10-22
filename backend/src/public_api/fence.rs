struct IdentityScope<T> {
    name: String,
    revision: uuid::Uuid,
    next_probe: u64,
    last_confirmed: u64,
    identity: Option<T>,
    last_used: u64,
}
impl<T> Default for IdentityScope<T> {
    fn default() -> Self {
        Self {
            name: String::new(),
            revision: uuid::Uuid::nil(),
            next_probe: 0,
            last_confirmed: 0,
            identity: None,
            last_used: 0,
        }
    }
}
#[derive(Clone, Copy)]
pub(crate) struct IdentityProbe {
    bucket: usize,
    revision: uuid::Uuid,
    sequence: u64,
}
#[derive(Clone, Copy)]
pub(crate) struct IdentityLease<T> {
    bucket: usize,
    revision: uuid::Uuid,
    identity: T,
}
/// @cc [owner:OctavianTocan,label:security] bounded-repository-fences-retire-stale-readers
/// A confirmed denial or repository-ID change MUST retire older captured readers.
/// A delayed probe MUST NOT restore an older identity after retirement or slot reuse.
/// Callers MUST keep this bounded policy within their original public/source owner,
/// never share personal tenant authority. Source: 21 Oct public isolation review and
/// confirmed 22 Oct personal-listener full-review finding 1.
pub(crate) struct IdentityFence<T: Clone + Eq> {
    scopes: [IdentityScope<T>; 64],
    clock: u64,
}
impl<T: Clone + Eq> Default for IdentityFence<T> {
    fn default() -> Self {
        Self {
            scopes: std::array::from_fn(|_| IdentityScope::<T>::default()),
            clock: 0,
        }
    }
}
impl<T: Clone + Eq> IdentityFence<T> {
    pub(crate) fn open(&mut self, scope: &str) -> Option<IdentityProbe> {
        self.clock = self.clock.checked_add(1)?;
        let bucket = self
            .scopes
            .iter()
            .position(|slot| slot.name == scope)
            .unwrap_or_else(|| {
                self.scopes
                    .iter()
                    .enumerate()
                    .min_by_key(|(_, slot)| slot.last_used)
                    .unwrap()
                    .0
            });
        let slot = &mut self.scopes[bucket];
        if slot.name != scope {
            *slot = IdentityScope {
                name: scope.to_owned(),
                revision: uuid::Uuid::new_v4(),
                ..Default::default()
            };
        }
        slot.last_used = self.clock;
        slot.next_probe = slot.next_probe.checked_add(1)?;
        Some(IdentityProbe {
            bucket,
            revision: slot.revision,
            sequence: slot.next_probe,
        })
    }
    pub(crate) fn deny(&mut self, scope: &str, probe: IdentityProbe) {
        let slot = &mut self.scopes[probe.bucket];
        if slot.name == scope && slot.revision == probe.revision {
            slot.revision = uuid::Uuid::new_v4();
            slot.identity = None;
        }
    }
    pub(crate) fn confirm(
        &mut self,
        scope: &str,
        probe: IdentityProbe,
        id: T,
    ) -> Option<(IdentityLease<T>, bool)> {
        let slot = &mut self.scopes[probe.bucket];
        if slot.name != scope
            || slot.revision != probe.revision
            || slot.last_confirmed > probe.sequence && slot.identity.as_ref() != Some(&id)
        {
            return None;
        }
        let changed = slot
            .identity
            .as_ref()
            .is_some_and(|previous| previous != &id);
        if changed {
            slot.revision = uuid::Uuid::new_v4();
        }
        slot.identity = Some(id.clone());
        slot.last_confirmed = slot.last_confirmed.max(probe.sequence);
        Some((
            IdentityLease {
                bucket: probe.bucket,
                revision: slot.revision,
                identity: id,
            },
            changed,
        ))
    }
    pub(crate) fn current(&self, scope: &str, lease: IdentityLease<T>) -> bool {
        let slot = &self.scopes[lease.bucket];
        slot.name == scope
            && slot.revision == lease.revision
            && slot.identity.as_ref() == Some(&lease.identity)
    }
    pub(crate) fn retire(&mut self, scope: &str, lease: IdentityLease<T>) {
        let bucket = lease.bucket;
        if self.current(scope, lease) {
            let slot = &mut self.scopes[bucket];
            slot.revision = uuid::Uuid::new_v4();
            slot.identity = None;
        }
    }
}
