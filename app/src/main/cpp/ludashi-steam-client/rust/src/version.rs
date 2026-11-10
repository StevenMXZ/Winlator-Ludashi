#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Version {
    pub major: i32,
    pub minor: i32,
    pub patch: i32,
    pub string: &'static str,
}

pub const VERSION: Version = Version {
    major: parse_i32_or(option_env!("LUDASHI_STEAM_CLIENT_VERSION_MAJOR"), 0),
    minor: parse_i32_or(option_env!("LUDASHI_STEAM_CLIENT_VERSION_MINOR"), 1),
    patch: parse_i32_or(option_env!("LUDASHI_STEAM_CLIENT_VERSION_PATCH"), 0),
    string: str_or(option_env!("LUDASHI_STEAM_CLIENT_VERSION_STRING"), "0.1.0"),
};

pub const fn version() -> Version {
    VERSION
}

const fn parse_i32(s: &str) -> Option<i32> {
    let bytes = s.as_bytes();
    if bytes.is_empty() {
        return None;
    }
    let mut i = 0;
    let mut out: i32 = 0;
    while i < bytes.len() {
        let b = bytes[i];
        if b < b'0' || b > b'9' {
            return None;
        }
        out = match out.checked_mul(10) {
            Some(v) => v,
            None => return None,
        };
        out = match out.checked_add((b - b'0') as i32) {
            Some(v) => v,
            None => return None,
        };
        i += 1;
    }
    Some(out)
}

const fn parse_i32_or(s: Option<&str>, default: i32) -> i32 {
    match s {
        Some(s) => match parse_i32(s) {
            Some(v) => v,
            None => default,
        },
        None => default,
    }
}

const fn str_or(s: Option<&'static str>, default: &'static str) -> &'static str {
    match s {
        Some(s) => s,
        None => default,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_version_matches_cmake_defaults() {
        assert_eq!(
            version(),
            Version {
                major: 0,
                minor: 1,
                patch: 0,
                string: "0.1.0"
            }
        );
    }
}
