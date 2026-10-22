use super::*;
use crate::personal_auth::provider::ReconnectRequired;

struct RefreshGuard {
    gate: Arc<Mutex<HashSet<String>>>,
    account_id: String,
}
impl Drop for RefreshGuard {
    fn drop(&mut self) {
        if let Ok(mut gate) = self.gate.lock() {
            gate.remove(&self.account_id);
        }
    }
}

impl PersonalVault {
    /// Accepted refresh continues through observer cancellation; only its original account/revision may be committed.
    pub(in crate::personal_auth) async fn resolve_or_refresh(
        &self,
        session: &str,
        provider: Arc<dyn GitHubProvider>,
    ) -> Result<AuthenticatedSession> {
        let (this, session) = (self.clone(), session.to_owned());
        tokio::spawn(async move { this.refresh_owned(session, provider).await })
            .await
            .map_err(|_| anyhow::anyhow!("Personal authorization is unavailable"))?
    }

    async fn refresh_owned(
        &self,
        session: String,
        provider: Arc<dyn GitHubProvider>,
    ) -> Result<AuthenticatedSession> {
        let hash = session_hash(&session)?;
        let this = self.clone();
        let first_hash = hash.clone();
        let refresh_lock = blocking(move || {
            let mut runtime = this
                .runtime
                .lock()
                .map_err(|_| anyhow::anyhow!("Personal authorization is unavailable"))?;
            let snapshot = this.snapshot(first_hash, true)?;
            Ok(runtime
                .refresh_locks
                .entry(snapshot.account_id)
                .or_default()
                .clone())
        })
        .await?;
        let _serial = refresh_lock.lock().await;
        let this = self.clone();
        let snapshot = blocking(move || {
            let _runtime = this
                .runtime
                .lock()
                .map_err(|_| anyhow::anyhow!("Personal authorization is unavailable"))?;
            this.snapshot(hash, true)
        })
        .await?;
        if snapshot
            .credentials
            .tokens
            .expires_at
            .is_none_or(|at| at > self.clock.now().saturating_add(60))
        {
            return self.resolve(&session, provider.as_ref()).await;
        }
        let Some(refresh) = snapshot
            .credentials
            .tokens
            .refresh
            .as_ref()
            .filter(|refresh| refresh.expires_at > self.clock.now())
            .map(|refresh| refresh.token.clone())
        else {
            self.invalidate_account(snapshot).await?;
            return Err(ReconnectRequired.into());
        };
        let this = self.clone();
        let account_id = snapshot.account_id.clone();
        let (source_hash, revision, generation) = (
            snapshot.hash.clone(),
            snapshot.account.revision,
            snapshot.account.generation,
        );
        let gate = blocking(move || {
            let mut runtime = this
                .runtime
                .lock()
                .map_err(|_| anyhow::anyhow!("Personal authorization is unavailable"))?;
            let current = this.snapshot(source_hash, true)?;
            ensure!(
                current.account_id == account_id
                    && current.account.revision == revision
                    && current.account.generation == generation,
                "Personal authorization changed"
            );
            runtime
                .bindings
                .retain(|_, binding| binding.account_id != account_id);
            runtime
                .refreshing
                .lock()
                .map_err(|_| anyhow::anyhow!("Personal authorization is unavailable"))?
                .insert(account_id.clone());
            Ok(RefreshGuard {
                gate: runtime.refreshing.clone(),
                account_id,
            })
        })
        .await?;
        let tokens = match provider.refresh(&refresh, self.clock.now()).await {
            Ok(tokens) => tokens,
            Err(error) => {
                drop(gate);
                if error.is::<ReconnectRequired>() {
                    self.invalidate_account(snapshot).await?;
                }
                return Err(error);
            }
        };
        if tokens.expires_at.is_none_or(|at| at <= self.clock.now())
            || tokens
                .refresh
                .as_ref()
                .is_none_or(|refresh| refresh.expires_at <= self.clock.now())
        {
            drop(gate);
            self.invalidate_account(snapshot).await?;
            return Err(ReconnectRequired.into());
        }
        // Persist the rotated pair before GET /user, so a verification outage never reuses a consumed refresh token.
        let this = self.clone();
        blocking(move || {
            let _runtime = this
                .runtime
                .lock()
                .map_err(|_| anyhow::anyhow!("Personal authorization is unavailable"))?;
            this.store
                .update(STATE_KEY, |value| {
                    let mut database = decode(value)?;
                    let account = database
                        .accounts
                        .get_mut(&snapshot.account_id)
                        .ok_or_else(|| anyhow::anyhow!("Personal authorization changed"))?;
                    ensure!(
                        account.revision == snapshot.account.revision
                            && account.generation == snapshot.account.generation
                            && account.credentials.is_some(),
                        "Personal authorization changed"
                    );
                    account.revision = account
                        .revision
                        .checked_add(1)
                        .ok_or_else(|| anyhow::anyhow!("Credential revision unavailable"))?;
                    let credentials = Credentials {
                        authority: tokens.authority(snapshot.credentials.authority),
                        tokens,
                        verified: false,
                    };
                    account.credentials = Some(this.keys.seal(
                        &credential_context(
                            &snapshot.account_id,
                            account.github_id,
                            account.revision,
                        ),
                        &serde_json::to_vec(&credentials)?,
                    )?);
                    Ok(serde_json::to_value(database)?)
                })
                .map_err(safe_storage)?;
            Ok(())
        })
        .await?;
        drop(gate);
        self.resolve(&session, provider.as_ref()).await
    }

    pub(super) async fn invalidate_account(&self, snapshot: Snapshot) -> Result<()> {
        let this = self.clone();
        blocking(move || {
            let mut runtime = this
                .runtime
                .lock()
                .map_err(|_| anyhow::anyhow!("Personal authorization is unavailable"))?;
            runtime.bindings.retain(|_, binding| {
                binding.account_id != snapshot.account_id
                    || binding.revision != snapshot.account.revision
            });
            this.store
                .update(STATE_KEY, |value| {
                    let mut database = decode(value)?;
                    let account = database
                        .accounts
                        .get_mut(&snapshot.account_id)
                        .ok_or_else(|| anyhow::anyhow!("Personal authorization changed"))?;
                    ensure!(
                        account.revision == snapshot.account.revision
                            && account.generation == snapshot.account.generation,
                        "Personal authorization changed"
                    );
                    account.credentials = None;
                    account.revision = account
                        .revision
                        .checked_add(1)
                        .ok_or_else(|| anyhow::anyhow!("Credential revision unavailable"))?;
                    account.generation = account
                        .generation
                        .checked_add(1)
                        .ok_or_else(|| anyhow::anyhow!("Credential revision unavailable"))?;
                    database
                        .sessions
                        .retain(|_, session| session.account_id != snapshot.account_id);
                    Ok(serde_json::to_value(database)?)
                })
                .map_err(safe_storage)?;
            Ok(())
        })
        .await
    }

    pub(super) fn confirm_verified(&self, current: &mut Snapshot, login: &str) -> Result<()> {
        if current.credentials.verified && current.account.login == login {
            return Ok(());
        }
        current.credentials.verified = true;
        current.account.login = login.to_owned();
        let sealed = self.keys.seal(
            &credential_context(
                &current.account_id,
                current.account.github_id,
                current.account.revision,
            ),
            &serde_json::to_vec(&current.credentials)?,
        )?;
        self.store
            .update(STATE_KEY, |value| {
                let mut database = decode(value)?;
                let account = database
                    .accounts
                    .get_mut(&current.account_id)
                    .ok_or_else(|| anyhow::anyhow!("Personal authorization changed"))?;
                ensure!(
                    account.revision == current.account.revision
                        && account.generation == current.account.generation
                        && database.sessions.contains_key(&current.hash),
                    "Personal authorization changed"
                );
                account.credentials = Some(sealed);
                account.login = current.account.login.clone();
                Ok(serde_json::to_value(database)?)
            })
            .map_err(safe_storage)?;
        Ok(())
    }
}
