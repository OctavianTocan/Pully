use super::*;

#[test]
fn hosted_lenses_bundle_the_same_curated_instructions_without_filesystem_access() {
    assert_eq!(SHIPPED.len(), 6);
    for (name, text) in SHIPPED {
        assert!(valid_name(name));
        let (fields, body) = parse_skill(text);
        assert!(fields.get("title").is_some_and(|v| !v.is_empty()));
        assert!(instructions(body).is_some());
        assert!(text.len() <= MAX_SKILL_BYTES);
    }
}
use std::{
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering},
};

struct Skills(PathBuf);

impl Skills {
    fn new() -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "pulls-lens-fixture-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir(&path).unwrap();
        Self(path)
    }

    fn skill(&self, name: &str, content: impl AsRef<[u8]>) {
        let path = self.0.join(name);
        std::fs::create_dir_all(&path).unwrap();
        std::fs::write(path.join("SKILL.md"), content).unwrap();
    }
}

impl Drop for Skills {
    fn drop(&mut self) {
        std::fs::remove_dir_all(&self.0).unwrap();
    }
}

#[test]
fn plain_quoted_folded_and_crlf_frontmatter_match_typescript() {
    for newline in ["\n", "\r\n"] {
        let source = [
            "---",
            "name: sec",
            "title: \"Security\"",
            "description: >-",
            "  Looks for",
            "  injection.",
            "literal: |+",
            "  Plain strings",
            "  also fold.",
            "empty:",
            "  'continued value'",
            "nested:",
            "  title: not top-level",
            "---",
            "",
            "# Body",
            "",
        ]
        .join(newline);
        let (fields, body) = parse_skill(&source);
        assert_eq!(fields["name"], "sec");
        assert_eq!(fields["title"], "Security");
        assert_eq!(fields["description"], "Looks for injection.");
        assert_eq!(fields["literal"], "Plain strings also fold.");
        assert_eq!(fields["empty"], "continued value");
        assert_eq!(fields["nested"], "title: not top-level");
        assert_eq!(body, "# Body");
    }
}

#[test]
fn frontmatter_is_plain_fields_not_general_yaml() {
    let (fields, body) = parse_skill(
        "---\ntitle: first\ntitle: 'Last'\ndescription: 42 # literal comment\n  title: ignored\nunclosed: 'quote\nfold: >\n  one\n\n  ignored\n---\nBody",
    );
    assert_eq!(fields["title"], "Last");
    assert_eq!(fields["description"], "42 # literal comment");
    assert_eq!(fields["unclosed"], "'quote");
    assert_eq!(fields["fold"], "one");
    assert_eq!(body, "Body");
}

#[test]
fn original_frontmatter_delimiters_and_whitespace_are_preserved() {
    for text in ["No frontmatter.", "---\ntitle: Unclosed", "---\n---\nBody"] {
        assert!(parse_skill(text).0.is_empty());
        assert_eq!(parse_skill(text).1, text);
    }
    assert!(parse_skill(" ---\ntitle: Indented\n---\nBody").0.is_empty());
    assert_eq!(parse_skill("---\ntitle: End\n---").1, "");
    // The original delimiter regex does not require a newline after the close.
    assert_eq!(parse_skill("---\ntitle: End\n---suffix").1, "suffix");
    assert_eq!(parse_skill("\u{feff}  Body\u{00a0}\n").1, "Body");
    assert_eq!(
        parse_skill("\u{0085}Body\u{0085}").1,
        "\u{0085}Body\u{0085}"
    );
    assert!(parse_skill("---\ntitle: a\u{2028}b\n---\n").0.is_empty());
    for value in ["a\u{2028}", "a\u{2029}", "a\r\r"] {
        assert!(parse_skill(&format!("---\ntitle: {value}\n---\n"))
            .0
            .is_empty());
    }
    assert!(parse_skill("---\n__proto__: ignored\n---\n").0.is_empty());
}

#[test]
fn names_accept_dots_after_the_first_ascii_character_only() {
    for name in ["security", "security.v2", "a..b", "a..", "-", "_", "A-._2"] {
        assert!(valid_name(name), "{name:?}");
    }
    for name in [
        "",
        ".",
        "..",
        ".hidden",
        "../security",
        "a/../b",
        "a/b",
        "a\\b",
        "/tmp",
        "two words",
        "é",
        "a\0b",
        "a\n",
        "a\r",
        "a\u{2028}",
    ] {
        assert!(!valid_name(name), "{name:?}");
    }
}

#[test]
fn catalog_keeps_metadata_and_empty_skills_but_omits_absent_files() {
    let skills = Skills::new();
    skills.skill(
        "security.v2",
        "---\ntitle: Security\ndescription: Finds holes.\n---\nCheck auth.",
    );
    skills.skill("perf", "---\ntitle: Performance\n---\nCount the loops.");
    skills.skill("empty", "");
    skills.skill("blank", "---\ntitle: ''\ndescription: \"\"\n---\n \t ");
    skills.skill(".hidden", "Hidden");
    std::fs::create_dir(skills.0.join("no-skill")).unwrap();
    assert_eq!(
        list(&skills.0).unwrap(),
        json!([
            {"name":"blank","title":"blank"},
            {"name":"empty","title":"empty"},
            {"name":"perf","title":"Performance"},
            {"name":"security.v2","title":"Security","description":"Finds holes."},
        ])
    );
    assert_eq!(
        get(&skills.0, "security.v2").unwrap().as_deref(),
        Some("Check auth.")
    );
    for name in [
        "empty",
        "blank",
        "missing",
        "no-skill",
        "../escape",
        ".hidden",
    ] {
        assert_eq!(get(&skills.0, name).unwrap(), None);
    }
    assert_eq!(list(&skills.0.join("missing")).unwrap(), json!([]));
    assert_eq!(get(&skills.0.join("missing"), "security.v2").unwrap(), None);
}

#[test]
fn current_curated_metadata_body_and_display_order_match_original() {
    let skills = Skills::new();
    for (name, content) in [
        (
            "security",
            include_str!("../../../../docker/agents/skills/security/SKILL.md"),
        ),
        (
            "android-compose",
            include_str!("../../../../docker/agents/skills/android-compose/SKILL.md"),
        ),
        (
            "tests",
            include_str!("../../../../docker/agents/skills/tests/SKILL.md"),
        ),
        (
            "performance",
            include_str!("../../../../docker/agents/skills/performance/SKILL.md"),
        ),
        (
            "api-and-contracts",
            include_str!("../../../../docker/agents/skills/api-and-contracts/SKILL.md"),
        ),
        (
            "correctness",
            include_str!("../../../../docker/agents/skills/correctness/SKILL.md"),
        ),
    ] {
        skills.skill(name, content);
    }
    let catalog = list(&skills.0).unwrap();
    let lenses = catalog.as_array().unwrap();
    assert_eq!(
        lenses
            .iter()
            .map(|lens| lens["name"].as_str().unwrap())
            .collect::<Vec<_>>(),
        [
            "android-compose",
            "api-and-contracts",
            "correctness",
            "performance",
            "security",
            "tests",
        ]
    );
    for lens in lenses {
        assert_ne!(lens["name"], lens["title"]);
        assert!(lens["description"].as_str().unwrap().ends_with('.'));
        assert!(get(&skills.0, lens["name"].as_str().unwrap())
            .unwrap()
            .unwrap()
            .starts_with("# "));
    }
}

#[test]
fn empty_and_bounded_utf16_instruction_bodies_keep_the_marker() {
    assert_eq!(instructions(""), None);
    for body in [
        "a".repeat(MAX_INSTRUCTIONS),
        "😀".repeat(MAX_INSTRUCTIONS / 2),
    ] {
        assert_eq!(instructions(&body), Some(body.clone()));
        assert_eq!(
            instructions(&format!("{body}x")),
            Some(format!("{body}\n[truncated]"))
        );
    }
    let prefix = "a".repeat(MAX_INSTRUCTIONS - 1);
    let result = instructions(&format!("{prefix}😀more")).unwrap();
    assert_eq!(result, format!("{prefix}\n[truncated]"));
    let encoded = serde_json::to_string(&result).unwrap();
    assert_eq!(serde_json::from_str::<String>(&encoded).unwrap(), result);
}

#[test]
fn malformed_utf8_has_nodes_replacement_semantics() {
    let skills = Skills::new();
    skills.skill("invalid-utf8", b"Body \xff rest");
    assert_eq!(
        get(&skills.0, "invalid-utf8").unwrap().as_deref(),
        Some("Body � rest")
    );
}

#[cfg(unix)]
#[test]
fn file_and_directory_symlinks_cannot_escape_curated_root() {
    use std::os::unix::fs::symlink;
    let skills = Skills::new();
    let outside = Skills::new();
    outside.skill("secret", "Must never be read");
    skills.skill("allowed", "Curated instructions");
    symlink(outside.0.join("secret"), skills.0.join("escape-dir")).unwrap();
    std::fs::create_dir(skills.0.join("escape-file")).unwrap();
    symlink(
        outside.0.join("secret/SKILL.md"),
        skills.0.join("escape-file/SKILL.md"),
    )
    .unwrap();
    symlink(skills.0.join("allowed"), skills.0.join("internal-alias")).unwrap();
    for name in ["escape-dir", "escape-file"] {
        assert!(get(&skills.0, name)
            .unwrap_err()
            .to_string()
            .contains("escapes"));
    }
    assert_eq!(
        get(&skills.0, "internal-alias").unwrap().as_deref(),
        Some("Curated instructions")
    );
    let catalog = list(&skills.0).unwrap();
    assert_eq!(catalog.as_array().unwrap().len(), 2);
    assert!(catalog.to_string().find("escape").is_none());
}

#[test]
fn single_file_read_and_catalog_output_are_bounded() {
    let skills = Skills::new();
    skills.skill("large", "x".repeat(MAX_SKILL_BYTES + 1));
    assert!(get(&skills.0, "large")
        .unwrap_err()
        .to_string()
        .contains("1 MiB read"));
    assert_eq!(list(&skills.0).unwrap(), json!([]));
    let large_title = "t".repeat(MAX_LIST_BYTES / 2);
    for name in ["one", "two"] {
        skills.skill(name, format!("---\ntitle: {large_title}\n---\nBody"));
    }
    assert!(list(&skills.0)
        .unwrap_err()
        .to_string()
        .contains("1 MiB output"));
}

#[test]
fn aggregate_catalog_reads_and_enumeration_are_bounded() {
    let skills = Skills::new();
    for n in 0..=MAX_LIST_READ_BYTES / MAX_SKILL_BYTES {
        skills.skill(&format!("skill{n}"), "x".repeat(MAX_SKILL_BYTES));
    }
    assert!(list(&skills.0).unwrap_err().to_string().contains("8 MiB"));
    let skills = Skills::new();
    for n in 0..=MAX_ENTRIES {
        std::fs::create_dir(skills.0.join(format!("folder{n}"))).unwrap();
    }
    assert!(list(&skills.0)
        .unwrap_err()
        .to_string()
        .contains("1024 entry"));
}
