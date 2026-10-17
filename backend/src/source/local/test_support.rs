use super::*;

pub(super) struct Fixture {
    pub(super) dir: std::path::PathBuf,
    pub(super) state: Arc<AppState>,
}
impl Fixture {
    pub(super) async fn new() -> Self {
        let dir = std::env::temp_dir().join(format!("pulls-batch-search-{}", uuid::Uuid::new_v4()));
        let root = dir.join("repo");
        std::fs::create_dir_all(&root).unwrap();
        git(&root, &["init", "-q"]).await.unwrap();
        let state = AppState::new(root.clone(), root.join("private-cache")).unwrap();
        Self { dir, state }
    }
    pub(super) fn write(&self, path: &str, bytes: impl AsRef<[u8]>) {
        let path = self.state.host.cwd.as_ref().unwrap().join(path);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, bytes).unwrap();
    }
    pub(super) async fn commit(&self) -> String {
        self.commit_message("fixture").await
    }
    pub(super) async fn commit_message(&self, message: &str) -> String {
        git(&self.state.host.cwd.as_ref().unwrap(), &["add", "--all"])
            .await
            .unwrap();
        git(
            &self.state.host.cwd.as_ref().unwrap(),
            &[
                "-c",
                "user.name=Fixture",
                "-c",
                "user.email=fixture@example.invalid",
                "-c",
                "commit.gpgsign=false",
                "commit",
                "-qm",
                message,
            ],
        )
        .await
        .unwrap();
        rev(&self.state.host.cwd.as_ref().unwrap(), "HEAD")
            .await
            .unwrap()
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}
