//! LZ-string's UTF-16/base64 wire format with bounded expansion for untrusted comments.
use std::collections::{HashMap, HashSet};
const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";
struct Writer {
    out: Vec<u8>,
    value: u8,
    used: u8,
}
impl Writer {
    fn bits(&mut self, n: u32, mut value: u32) {
        for _ in 0..n {
            self.value = (self.value << 1) | (value as u8 & 1);
            value >>= 1;
            self.used += 1;
            if self.used == 6 {
                self.out.push(ALPHABET[self.value as usize]);
                self.value = 0;
                self.used = 0;
            }
        }
    }
    fn finish(mut self) -> String {
        while self.used != 0 {
            self.bits(1, 0);
        }
        while self.out.len() % 4 != 0 {
            self.out.push(b'=');
        }
        String::from_utf8(self.out).unwrap()
    }
}
fn emit(
    w: &mut Writer,
    word: &[u16],
    dict: &HashMap<Vec<u16>, u32>,
    pending: &mut HashSet<Vec<u16>>,
    width: u32,
    remaining: &mut u32,
) {
    if pending.remove(word) {
        let c = word[0] as u32;
        if c < 256 {
            w.bits(width, 0);
            w.bits(8, c);
        } else {
            w.bits(width, 1);
            w.bits(16, c);
        }
        *remaining -= 1;
    } else {
        w.bits(width, dict[word]);
    }
}
pub fn compress(input: &str) -> String {
    let mut w = Writer {
        out: Vec::new(),
        value: 0,
        used: 0,
    };
    let mut dict = HashMap::new();
    let mut pending = HashSet::new();
    let (mut next, mut width, mut remaining) = (3, 2, 2);
    let mut word = Vec::new();
    for c in input.encode_utf16() {
        let ch = vec![c];
        if !dict.contains_key(&ch) {
            dict.insert(ch.clone(), next);
            next += 1;
            pending.insert(ch.clone());
        }
        let mut combined = word.clone();
        combined.push(c);
        if dict.contains_key(&combined) {
            word = combined;
            continue;
        }
        emit(&mut w, &word, &dict, &mut pending, width, &mut remaining);
        if remaining == 0 {
            remaining = 1 << width;
            width += 1;
        }
        remaining -= 1;
        if remaining == 0 {
            remaining = 1 << width;
            width += 1;
        }
        dict.insert(combined, next);
        next += 1;
        word = ch;
    }
    if !word.is_empty() {
        emit(&mut w, &word, &dict, &mut pending, width, &mut remaining);
        if remaining == 0 {
            remaining = 1 << width;
            width += 1;
        }
        remaining -= 1;
        if remaining == 0 {
            width += 1;
        }
    }
    w.bits(width, 2); // LZ-string always flushes one complete trailing base64 character.
    w.bits(1, 0);
    w.finish()
}
struct Reader {
    data: Vec<u8>,
    bit: usize,
}
impl Reader {
    fn bits(&mut self, n: u32) -> Option<u32> {
        if n > 24 {
            return None;
        }
        let mut out = 0;
        for i in 0..n {
            let v = *self.data.get(self.bit / 6)?;
            out |= u32::from((v >> (5 - self.bit % 6)) & 1) << i;
            self.bit += 1;
        }
        Some(out)
    }
}
pub fn decompress(input: &str, limit: usize) -> Option<String> {
    if input.len() > 100_000 {
        return None;
    }
    let data: Option<Vec<_>> = input
        .bytes()
        .take_while(|b| *b != b'=')
        .map(|b| ALPHABET[..64].iter().position(|x| *x == b).map(|n| n as u8))
        .collect();
    let mut r = Reader {
        data: data?,
        bit: 0,
    };
    let first = match r.bits(2)? {
        0 => r.bits(8)? as u16,
        1 => r.bits(16)? as u16,
        2 => return Some(String::new()),
        _ => return None,
    };
    let mut dict = vec![Vec::new(), Vec::new(), Vec::new(), vec![first]];
    let mut word = vec![first];
    let mut out = word.clone();
    let (mut width, mut remaining) = (3, 4);
    let mut allocation = 1usize;
    loop {
        let mut code = r.bits(width)? as usize;
        match code {
            0 | 1 => {
                let c = r.bits(if code == 0 { 8 } else { 16 })? as u16;
                dict.push(vec![c]);
                allocation += 1;
                code = dict.len() - 1;
                remaining -= 1;
            }
            2 => return String::from_utf16(&out).ok(),
            _ => {}
        }
        if remaining == 0 {
            remaining = 1 << width;
            width += 1;
        }
        let entry = if code < dict.len() {
            dict[code].clone()
        } else if code == dict.len() {
            let mut v = word.clone();
            v.push(*word.first()?);
            v
        } else {
            return None;
        };
        if entry.is_empty() || out.len().checked_add(entry.len())? > limit {
            return None;
        }
        out.extend_from_slice(&entry);
        let mut added = word;
        added.push(entry[0]);
        allocation = allocation.checked_add(added.len())?;
        if allocation > limit.saturating_mul(8) || dict.len() > limit {
            return None;
        }
        dict.push(added);
        remaining -= 1;
        word = entry;
        if remaining == 0 {
            remaining = 1 << width;
            width += 1;
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn vectors() {
        assert_eq!(compress("Hello world!"), "BIUwNmD2AEDukCcwBMCEQ===");
        for s in ["", "Hello world!", "☀️ UTF16 🦀", &"abc".repeat(1000)] {
            assert_eq!(decompress(&compress(s), 10000).as_deref(), Some(s));
        }
        assert!(decompress(&compress(&"x".repeat(1000)), 10).is_none());
        assert!(decompress("not-lz", 1000).is_none());
    }
}
