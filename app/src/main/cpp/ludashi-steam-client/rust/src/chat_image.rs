use sha1::{Digest, Sha1};
use std::time::Duration;

const COMMUNITY: &str = "https://steamcommunity.com";

fn sha1_hex(bytes: &[u8]) -> String {
    let mut hasher = Sha1::new();
    hasher.update(bytes);
    hasher
        .finalize()
        .iter()
        .map(|b| format!("{:02x}", b))
        .collect()
}

fn random_sessionid() -> String {
    let mut bytes = [0u8; 12];
    rand::Rng::fill(&mut rand::thread_rng(), &mut bytes[..]);
    bytes.iter().map(|b| format!("{:02x}", b)).collect()
}

/// Best-effort image dimensions for PNG/JPEG/GIF without an image crate.
fn image_dimensions(bytes: &[u8]) -> (u32, u32) {
    if bytes.len() > 24 && &bytes[0..8] == b"\x89PNG\r\n\x1a\n" {
        let w = u32::from_be_bytes([bytes[16], bytes[17], bytes[18], bytes[19]]);
        let h = u32::from_be_bytes([bytes[20], bytes[21], bytes[22], bytes[23]]);
        return (w, h);
    }
    if bytes.len() > 10 && (&bytes[0..6] == b"GIF89a" || &bytes[0..6] == b"GIF87a") {
        let w = u16::from_le_bytes([bytes[6], bytes[7]]) as u32;
        let h = u16::from_le_bytes([bytes[8], bytes[9]]) as u32;
        return (w, h);
    }
    if bytes.len() > 4 && bytes[0] == 0xFF && bytes[1] == 0xD8 {
        let mut i = 2usize;
        while i + 9 < bytes.len() {
            if bytes[i] != 0xFF {
                i += 1;
                continue;
            }
            let marker = bytes[i + 1];
            if (0xC0..=0xCF).contains(&marker)
                && marker != 0xC4
                && marker != 0xC8
                && marker != 0xCC
            {
                let h = u16::from_be_bytes([bytes[i + 5], bytes[i + 6]]) as u32;
                let w = u16::from_be_bytes([bytes[i + 7], bytes[i + 8]]) as u32;
                return (w, h);
            }
            let len = u16::from_be_bytes([bytes[i + 2], bytes[i + 3]]) as usize;
            if len < 2 {
                break;
            }
            i += 2 + len;
        }
    }
    (0, 0)
}

fn content_type(bytes: &[u8]) -> &'static str {
    if bytes.len() > 8 && &bytes[0..8] == b"\x89PNG\r\n\x1a\n" {
        "image/png"
    } else if bytes.len() > 3 && bytes[0] == 0xFF && bytes[1] == 0xD8 {
        "image/jpeg"
    } else if bytes.len() > 6 && (&bytes[0..6] == b"GIF89a" || &bytes[0..6] == b"GIF87a") {
        "image/gif"
    } else if bytes.len() > 12 && &bytes[0..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        "image/webp"
    } else {
        "image/png"
    }
}

fn build_client(ca_bundle_path: &str) -> Result<reqwest::blocking::Client, String> {
    let mut builder = reqwest::blocking::Client::builder()
        .user_agent("Mozilla/5.0")
        .connect_timeout(Duration::from_secs(15));
    if !ca_bundle_path.is_empty() {
        if let Ok(pem) = std::fs::read(ca_bundle_path) {
            if let Ok(certs) = reqwest::Certificate::from_pem_bundle(&pem) {
                for cert in certs {
                    builder = builder.add_root_certificate(cert);
                }
            }
        }
    }
    builder
        .build()
        .map_err(|err| format!("http client: {err}"))
}

fn json_get_str(value: &serde_json::Value, key: &str) -> String {
    value
        .get(key)
        .and_then(|v| {
            if v.is_string() {
                v.as_str().map(|s| s.to_string())
            } else if v.is_number() {
                Some(v.to_string())
            } else {
                None
            }
        })
        .unwrap_or_default()
}

/// Uploads an image to Steam's chat UGC and returns the resulting image URL.
pub fn upload(
    ca_bundle_path: &str,
    self_steamid: u64,
    friend_steamid: u64,
    access_token: &str,
    image: &[u8],
    file_name: &str,
) -> Result<String, String> {
    if image.is_empty() {
        return Err("image is empty".into());
    }
    let client = build_client(ca_bundle_path)?;
    let sessionid = random_sessionid();
    let cookie = format!(
        "sessionid={sessionid}; steamLoginSecure={self_steamid}%7C%7C{access_token}"
    );
    let sha = sha1_hex(image);
    let (width, height) = image_dimensions(image);
    let size = image.len().to_string();
    let width_s = width.to_string();
    let height_s = height.to_string();

    let begin = client
        .post(format!("{COMMUNITY}/chat/beginfileupload/?l=english"))
        .header("Cookie", cookie.as_str())
        .header("Referer", format!("{COMMUNITY}/chat/"))
        .header("Origin", COMMUNITY)
        .form(&[
            ("sessionid", sessionid.as_str()),
            ("l", "english"),
            ("file_size", size.as_str()),
            ("file_name", file_name),
            ("file_sha", sha.as_str()),
            ("file_image_width", width_s.as_str()),
            ("file_image_height", height_s.as_str()),
            ("file_type", content_type(image)),
        ])
        .timeout(Duration::from_secs(30))
        .send()
        .map_err(|err| format!("begin send: {err}"))?;
    let begin_status = begin.status().as_u16();
    let begin_body = begin.text().map_err(|err| format!("begin body: {err}"))?;
    if begin_status != 200 {
        return Err(format!("begin http {begin_status}: {begin_body}"));
    }
    let begin_json: serde_json::Value =
        serde_json::from_str(&begin_body).map_err(|err| format!("begin json: {err}"))?;
    let payload = begin_json.get("result").unwrap_or(&begin_json);
    let ugcid = json_get_str(payload, "ugcid");
    let hmac = json_get_str(&begin_json, "hmac");
    let timestamp = {
        let top = json_get_str(&begin_json, "timestamp");
        if top.is_empty() { json_get_str(payload, "timestamp") } else { top }
    };
    let url_host = json_get_str(payload, "url_host");
    let url_path = json_get_str(payload, "url_path");
    let use_https = payload
        .get("use_https")
        .and_then(|v| v.as_bool())
        .unwrap_or(true);
    if ugcid.is_empty() || url_host.is_empty() {
        return Err(format!("begin missing ugcid/url_host: {begin_body}"));
    }

    let scheme = if use_https { "https" } else { "http" };
    let put_url = format!("{scheme}://{url_host}{url_path}");
    let mut put = client
        .put(&put_url)
        .header("Content-Type", content_type(image))
        .body(image.to_vec())
        .timeout(Duration::from_secs(45));
    if let Some(headers) = payload.get("request_headers").and_then(|v| v.as_array()) {
        for h in headers {
            let name = json_get_str(h, "name");
            let value = json_get_str(h, "value");
            if !name.is_empty() {
                put = put.header(name, value);
            }
        }
    }
    let put_resp = put.send().map_err(|err| format!("ugc put: {err}"))?;
    let put_status = put_resp.status().as_u16();
    if !(200..300).contains(&put_status) {
        return Err(format!("ugc put http {put_status}"));
    }

    let commit = client
        .post(format!("{COMMUNITY}/chat/commitfileupload/"))
        .header("Cookie", cookie.as_str())
        .header("Referer", format!("{COMMUNITY}/chat/"))
        .header("Origin", COMMUNITY)
        .form(&[
            ("sessionid", sessionid.as_str()),
            ("l", "english"),
            ("file_name", file_name),
            ("file_sha", sha.as_str()),
            ("file_size", size.as_str()),
            ("file_image_width", width_s.as_str()),
            ("file_image_height", height_s.as_str()),
            ("file_type", content_type(image)),
            ("success", "1"),
            ("ugcid", ugcid.as_str()),
            ("timestamp", timestamp.as_str()),
            ("hmac", hmac.as_str()),
            ("friend_steamid", &friend_steamid.to_string()),
            ("spoiler", "0"),
        ])
        .timeout(Duration::from_secs(30))
        .send()
        .map_err(|err| format!("commit send: {err}"))?;
    let commit_status = commit.status().as_u16();
    let commit_body = commit.text().map_err(|err| format!("commit body: {err}"))?;
    if commit_status != 200 {
        return Err(format!("commit http {commit_status}: {commit_body}"));
    }
    let commit_json: serde_json::Value =
        serde_json::from_str(&commit_body).map_err(|err| format!("commit json: {err}"))?;
    let details = commit_json
        .get("result")
        .and_then(|r| r.get("details"))
        .ok_or_else(|| format!("commit no details: {commit_body}"))?;
    let file_sha = json_get_str(details, "file_sha");
    let sha_upper = if file_sha.is_empty() {
        sha.to_uppercase()
    } else {
        file_sha.to_uppercase()
    };
    Ok(format!(
        "https://images.steamusercontent.com/ugc/{ugcid}/{sha_upper}/"
    ))
}
