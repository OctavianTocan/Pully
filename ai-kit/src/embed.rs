//! Gemini embeddings with `gemini-embedding-2`: text, images and pages in one 768-dimension space.
//! This module has no I/O. The host sends `{base}/models/gemini-embedding-2:embedContent` (or
//! `:batchEmbedContents`) with its own HTTP client and key, and hands the answer's numbers back here.
//!
//! Strings count in UTF-16 code units and use JavaScript's whitespace set, because the vectors
//! ThyMind stores were made by JavaScript first: the same card MUST give the same text, or a search
//! compares two spaces.

use std::fmt;

/// The embedding model.
pub const MODEL: &str = "gemini-embedding-2";
/// The model as the request body names it.
pub const MODEL_PATH: &str = "models/gemini-embedding-2";
/// Dimensions asked for (`output_dimensionality`) and required back.
pub const DIMENSIONS: usize = 768;
/// Bytes of one stored vector: [`DIMENSIONS`] little-endian `f32`s.
pub const VECTOR_BYTES: usize = DIMENSIONS * 4;
/// Texts per `batchEmbedContents` request.
pub const BATCH_SIZE: usize = 100;
/// UTF-16 units of body or query text sent.
pub const TEXT_CHARS: usize = 6000;
/// UTF-16 units of a document's title sent.
pub const TITLE_CHARS: usize = 500;

/// The single-item endpoint under `base` (for example `https://generativelanguage.googleapis.com/v1beta`).
pub fn embed_url(base: &str) -> String {
    format!("{base}/models/{MODEL}:embedContent")
}

/// The batch endpoint under `base`.
pub fn batch_url(base: &str) -> String {
    format!("{base}/models/{MODEL}:batchEmbedContents")
}

/// Whether JavaScript's `\s` and `trim` treat `character` as whitespace.
pub fn is_whitespace(character: char) -> bool {
    matches!(
        character,
        '\u{9}' | '\u{a}' | '\u{b}' | '\u{c}' | '\u{d}' | ' ' | '\u{a0}' | '\u{1680}' | '\u{2000}'
            ..='\u{200a}'
                | '\u{2028}'
                | '\u{2029}'
                | '\u{202f}'
                | '\u{205f}'
                | '\u{3000}'
                | '\u{feff}'
    )
}

/// `text` with whitespace runs collapsed to one space, trimmed, and cut to its first `max` UTF-16
/// units. A cut through a surrogate pair leaves U+FFFD, as the stored vectors' text did.
pub fn clip(text: Option<&str>, max: usize) -> String {
    let collapsed = text
        .unwrap_or("")
        .split(is_whitespace)
        .filter(|word| !word.is_empty())
        .collect::<Vec<_>>()
        .join(" ");
    if collapsed.is_ascii() {
        return collapsed[..collapsed.len().min(max)].to_owned();
    }
    let units: Vec<u16> = collapsed.encode_utf16().take(max).collect();
    String::from_utf16_lossy(&units)
}

/// A card's text as `title: … | text: …` (`none` for a missing title), or empty when it has
/// neither. Saves and backfills MUST share this, so one card always embeds the same way.
pub fn document_text(title: Option<&str>, body: Option<&str>) -> String {
    let (title, body) = (clip(title, TITLE_CHARS), clip(body, TEXT_CHARS));
    if title.is_empty() && body.is_empty() {
        String::new()
    } else {
        format!(
            "title: {} | text: {body}",
            if title.is_empty() { "none" } else { &title }
        )
    }
}

/// A search query or tag name as `task: search result | query: …`, or `None` when it is empty.
pub fn query_text(query: &str) -> Option<String> {
    let query = clip(Some(query), TEXT_CHARS);
    (!query.is_empty()).then(|| format!("task: search result | query: {query}"))
}

/// Why the answer's numbers are not a usable vector.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VectorError {
    /// The answer had no list of values.
    Missing,
    /// The answer had this many values instead of [`DIMENSIONS`].
    Dimensions(usize),
    /// A value was NaN or infinite.
    Nonfinite,
}

impl fmt::Display for VectorError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Missing => write!(
                f,
                "gemini returned no embedding dimensions, expected {DIMENSIONS}"
            ),
            Self::Dimensions(found) => write!(
                f,
                "gemini returned {found} embedding dimensions, expected {DIMENSIONS}"
            ),
            Self::Nonfinite => write!(f, "gemini returned a nonfinite embedding"),
        }
    }
}

impl std::error::Error for VectorError {}

/// The answer's `values` as a unit-length vector. `None` means the answer had no list.
///
/// Each value is cast to `f32` before the length is taken, and an all-zero vector stays as it is.
pub fn unit_vector(values: Option<&[f64]>) -> Result<Vec<f32>, VectorError> {
    let values = values.ok_or(VectorError::Missing)?;
    if values.len() != DIMENSIONS {
        return Err(VectorError::Dimensions(values.len()));
    }
    let vector: Vec<f32> = values.iter().map(|value| *value as f32).collect();
    if !vector.iter().all(|value| value.is_finite()) {
        return Err(VectorError::Nonfinite);
    }
    let length = hypot(&vector);
    Ok(if length > 0.0 {
        vector
            .iter()
            .map(|value| (f64::from(*value) / length) as f32)
            .collect()
    } else {
        vector
    })
}

/// The Euclidean length of `values` as V8's `Math.hypot` finds it: scaled by the largest magnitude,
/// with Kahan summation, so stored vectors normalise to the same bits.
pub fn hypot(values: &[f32]) -> f64 {
    let max = values
        .iter()
        .fold(0.0_f64, |max, value| max.max(f64::from(*value).abs()));
    if max == 0.0 {
        return 0.0;
    }
    let mut sum = 0.0_f64;
    let mut compensation = 0.0_f64;
    for value in values {
        let n = f64::from(*value).abs() / max;
        let summand = n * n - compensation;
        let preliminary = sum + summand;
        compensation = (preliminary - sum) - summand;
        sum = preliminary;
    }
    sum.sqrt() * max
}

/// Cosine similarity in [-1, 1]. A `b` shorter than `a` gives 0, and so does an all-zero vector.
pub fn cosine<A: Copy + Into<f64>, B: Copy + Into<f64>>(a: &[A], b: &[B]) -> f64 {
    let (mut dot, mut norm_a, mut norm_b) = (0.0_f64, 0.0_f64, 0.0_f64);
    for (index, x) in a.iter().enumerate() {
        let x: f64 = (*x).into();
        let y: f64 = b.get(index).map_or(f64::NAN, |y| (*y).into());
        dot += x * y;
        norm_a += x * x;
        norm_b += y * y;
    }
    let truthy = |norm: f64| norm != 0.0 && !norm.is_nan();
    if truthy(norm_a) && truthy(norm_b) {
        dot / (norm_a * norm_b).sqrt()
    } else {
        0.0
    }
}

/// `vector` as stored: little-endian `f32`s.
pub fn encode_vector(vector: &[f32]) -> Vec<u8> {
    vector
        .iter()
        .flat_map(|value| value.to_le_bytes())
        .collect()
}

/// A stored vector, or `None` unless `bytes` is [`VECTOR_BYTES`] of finite floats.
pub fn decode_vector(bytes: &[u8]) -> Option<Vec<f32>> {
    if bytes.len() != VECTOR_BYTES {
        return None;
    }
    let vector: Vec<f32> = bytes
        .chunks_exact(4)
        .map(|chunk| f32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]))
        .collect();
    vector
        .iter()
        .all(|value| value.is_finite())
        .then_some(vector)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn texts_collapse_whitespace_and_count_utf16_units() {
        assert_eq!(clip(Some("  a\u{a0}\u{2028}b\t\nc  "), 10), "a b c");
        assert_eq!(clip(Some("abcdef"), 3), "abc");
        assert_eq!(clip(None, 3), "");
        // 🦀 is two UTF-16 units; a cut through it leaves U+FFFD.
        assert_eq!(clip(Some("a🦀b"), 3), "a🦀");
        assert_eq!(clip(Some("a🦀b"), 2), "a\u{fffd}");
        assert_eq!(clip(Some("雪雪雪"), 2), "雪雪");
    }

    #[test]
    fn documents_and_queries_keep_their_prompts() {
        assert_eq!(document_text(None, None), "");
        assert_eq!(document_text(Some("  "), Some("\n")), "");
        assert_eq!(
            document_text(None, Some("body")),
            "title: none | text: body"
        );
        assert_eq!(document_text(Some("T"), None), "title: T | text: ");
        assert_eq!(
            document_text(Some(&"t".repeat(600)), Some(&"b".repeat(7000))),
            format!("title: {} | text: {}", "t".repeat(500), "b".repeat(6000))
        );
        assert_eq!(query_text(" \t"), None);
        assert_eq!(
            query_text(" red  shoes "),
            Some("task: search result | query: red shoes".into())
        );
        assert_eq!(
            embed_url("https://g/v1beta"),
            "https://g/v1beta/models/gemini-embedding-2:embedContent"
        );
        assert_eq!(
            batch_url("https://g/v1beta"),
            "https://g/v1beta/models/gemini-embedding-2:batchEmbedContents"
        );
        assert_eq!(MODEL_PATH, format!("models/{MODEL}"));
    }

    #[test]
    fn answers_become_unit_vectors_or_name_their_fault() {
        assert_eq!(unit_vector(None), Err(VectorError::Missing));
        assert_eq!(unit_vector(Some(&[1.0])), Err(VectorError::Dimensions(1)));
        let mut values = vec![0.0; DIMENSIONS];
        assert_eq!(unit_vector(Some(&values)), Ok(vec![0.0; DIMENSIONS]));
        values[3] = f64::INFINITY;
        assert_eq!(unit_vector(Some(&values)), Err(VectorError::Nonfinite));
        // Finite as f64 but not as f32.
        values[3] = 1e300;
        assert_eq!(unit_vector(Some(&values)), Err(VectorError::Nonfinite));
        values[3] = 3.0;
        values[4] = 4.0;
        let vector = unit_vector(Some(&values)).unwrap();
        assert_eq!((vector[3], vector[4]), (0.6, 0.8));
        assert_eq!(
            VectorError::Dimensions(1).to_string(),
            "gemini returned 1 embedding dimensions, expected 768"
        );
        assert_eq!(
            VectorError::Missing.to_string(),
            "gemini returned no embedding dimensions, expected 768"
        );
    }

    #[test]
    fn hypot_matches_v8_where_naive_summing_drifts() {
        assert_eq!(hypot(&[]), 0.0);
        assert_eq!(hypot(&[3.0, -4.0]), 5.0);
        let values = [1e-3_f32; 768];
        let naive = values
            .iter()
            .map(|value| f64::from(*value).powi(2))
            .sum::<f64>()
            .sqrt();
        assert!((hypot(&values) - naive).abs() < 1e-12);
        // Scaling by the largest magnitude keeps huge values from overflowing.
        assert_eq!(
            hypot(&[f32::MAX, f32::MAX]),
            f64::from(f32::MAX) * 2f64.sqrt()
        );
    }

    #[test]
    fn cosine_treats_short_and_zero_vectors_as_unrelated() {
        assert_eq!(cosine(&[1.0_f32, 0.0], &[1.0_f32, 0.0]), 1.0);
        assert_eq!(cosine(&[1.0_f32, 0.0], &[-1.0_f64, 0.0]), -1.0);
        assert_eq!(cosine(&[1.0_f32, 1.0], &[1.0_f32]), 0.0);
        assert_eq!(cosine(&[0.0_f32, 0.0], &[1.0_f32, 0.0]), 0.0);
        assert_eq!(cosine::<f32, f32>(&[], &[]), 0.0);
    }

    #[test]
    fn stored_vectors_round_trip_and_reject_bad_blobs() {
        let vector: Vec<f32> = (0..DIMENSIONS).map(|index| index as f32 / 7.0).collect();
        let bytes = encode_vector(&vector);
        assert_eq!(bytes.len(), VECTOR_BYTES);
        assert_eq!(&bytes[4..8], &(1.0_f32 / 7.0).to_le_bytes());
        assert_eq!(decode_vector(&bytes), Some(vector));
        assert_eq!(decode_vector(&bytes[..VECTOR_BYTES - 4]), None);
        let mut bad = bytes;
        bad[..4].copy_from_slice(&f32::NAN.to_le_bytes());
        assert_eq!(decode_vector(&bad), None);
    }
}
