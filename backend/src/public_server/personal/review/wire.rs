use super::super::{Code, ReadError, Result};
use serde::{Deserialize, Serialize};

pub(super) const BASE: &str =
    "/api/personal/v1/github/account/repositories/{owner}/{repo}/pulls/{number}/review";

#[derive(Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct Target {
    pub repository_id: u64,
    pub pull_id: u64,
    pub number: u32,
    pub base_sha: String,
    pub head_sha: String,
    pub private: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(super) struct Selection {
    pub repository_id: u64,
    pub pull_id: Option<u64>,
    pub base_sha: Option<String>,
    pub head_sha: Option<String>,
    pub private: Option<bool>,
    pub path: Option<String>,
}

pub(super) fn sha(value: &str) -> Result<String> {
    if !matches!(value.len(), 40 | 64) || !value.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(ReadError::new(Code::InvalidRequest));
    }
    Ok(value.to_ascii_lowercase())
}

impl Selection {
    pub fn target(&self, number: u32, opening: bool) -> Result<Option<Target>> {
        if self.repository_id == 0 || number == 0 || number > i32::MAX as u32 {
            return Err(ReadError::new(Code::InvalidRequest));
        }
        if opening {
            if self.pull_id.is_some()
                || self.base_sha.is_some()
                || self.head_sha.is_some()
                || self.private.is_some()
                || self.path.is_some()
            {
                return Err(ReadError::new(Code::InvalidRequest));
            }
            return Ok(None);
        }
        Ok(Some(Target {
            repository_id: self.repository_id,
            pull_id: self
                .pull_id
                .filter(|id| *id > 0)
                .ok_or_else(|| ReadError::new(Code::InvalidRequest))?,
            number,
            base_sha: sha(self
                .base_sha
                .as_deref()
                .ok_or_else(|| ReadError::new(Code::InvalidRequest))?)?,
            head_sha: sha(self
                .head_sha
                .as_deref()
                .ok_or_else(|| ReadError::new(Code::InvalidRequest))?)?,
            private: self
                .private
                .ok_or_else(|| ReadError::new(Code::InvalidRequest))?,
        }))
    }
    pub fn file_path(&self, files: bool) -> Result<Option<&str>> {
        let Some(path) = self.path.as_deref() else {
            return if files {
                Err(ReadError::new(Code::InvalidRequest))
            } else {
                Ok(None)
            };
        };
        if !files
            || path.is_empty()
            || path.len() > 4096
            || path.starts_with('/')
            || path.chars().any(char::is_control)
            || path
                .split('/')
                .any(|part| part.is_empty() || part == "." || part == "..")
        {
            return Err(ReadError::new(Code::InvalidRequest));
        }
        Ok(Some(path))
    }
}
