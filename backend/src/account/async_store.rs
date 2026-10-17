use super::*;

impl Accounts {
    /// @cc [owner:OctavianTocan,label:architecture] rust-account-transactions-off-runtime
    /// Async account endpoints MUST run complete account/storage operations, including
    /// mutex acquisition and credential-use writes, on the blocking pool (Rust parity
    /// review, 16 Oct 2026, round 12 finding 6).
    async fn transaction<T: Send + 'static>(
        &self,
        operation: impl FnOnce(Self) -> Result<T> + Send + 'static,
    ) -> Result<T> {
        let accounts = self.clone();
        crate::store::blocking(move || operation(accounts)).await
    }
    pub async fn by_session_async(&self, token: Option<&str>) -> Result<Option<Account>> {
        let token = token.map(str::to_owned);
        self.transaction(move |accounts| accounts.by_session(token.as_deref()))
            .await
    }
    pub async fn by_key_async(&self, key: Option<&str>) -> Result<Option<KeyAccount>> {
        let key = key.map(str::to_owned);
        self.transaction(move |accounts| accounts.by_key(key.as_deref()))
            .await
    }
    pub async fn sign_out_async(&self, token: &str) -> Result<()> {
        let token = token.to_owned();
        self.transaction(move |accounts| accounts.sign_out(&token))
            .await
    }
    pub async fn keys_async(&self, account_id: &str) -> Result<Vec<AgentKey>> {
        let account_id = account_id.to_owned();
        self.transaction(move |accounts| accounts.keys(&account_id))
            .await
    }
    pub async fn create_key_async(&self, account_id: &str, name: &str) -> Result<CreatedKey> {
        let (account_id, name) = (account_id.to_owned(), name.to_owned());
        self.transaction(move |accounts| accounts.create_key(&account_id, &name))
            .await
    }
    pub async fn revoke_key_async(&self, account_id: &str, id: &str) -> Result<bool> {
        let (account_id, id) = (account_id.to_owned(), id.to_owned());
        self.transaction(move |accounts| accounts.revoke_key(&account_id, &id))
            .await
    }
    pub async fn settings_async(&self, account_id: &str) -> Result<Value> {
        let account_id = account_id.to_owned();
        self.transaction(move |accounts| accounts.settings(&account_id))
            .await
    }
    pub async fn merge_settings_async(&self, account_id: &str, values: Value) -> Result<Value> {
        let account_id = account_id.to_owned();
        self.transaction(move |accounts| accounts.merge_settings(&account_id, values))
            .await
    }
}

#[cfg(test)]
impl Accounts {
    pub(crate) fn held_for_test(&self) -> crate::store::test_support::HeldLock {
        let serial = self.serial.clone();
        crate::store::test_support::HeldLock::new(move |ready, release| {
            let _guard = serial.lock().unwrap();
            ready.send(()).unwrap();
            release
                .recv_timeout(std::time::Duration::from_secs(5))
                .is_ok()
        })
    }
    pub(crate) fn fixture_for_test(&self) -> (Account, String, CreatedKey) {
        let account = Account {
            id: "fixture-owner".into(),
            email: "owner@example.invalid".into(),
        };
        self.store
            .set(
                &format!("account:{}", account.id),
                &json!({
                    "id":account.id,"email":account.email,"createdAt":iso_ms(now_ms()),
                    "password":{"salt":"","hash":"","iterations":1}
                }),
            )
            .unwrap();
        let session = "fixture-session".to_owned();
        self.store
            .set(
                &format!("account-session:{}", sha256(&session)),
                &json!({"accountId":account.id}),
            )
            .unwrap();
        let created = self.create_key(&account.id, "Fixture key").unwrap();
        (account, session, created)
    }
}
