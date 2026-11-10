use crate::cm_server::{parse_endpoint, CmServer, CmTransport};
use serde_json::Value;
use std::fs;
use std::time::Duration;

pub const DEFAULT_USER_AGENT: &str = "Valve/Steam HTTP Client 1.0";
pub const DIRECTORY_ENDPOINT: &str =
    "https://api.steampowered.com/ISteamDirectory/GetCMListForConnect/v1/";
pub const DEFAULT_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Clone, Debug, Default, PartialEq)]
pub struct SteamDirectoryResult {
    pub servers: Vec<CmServer>,
    pub error: String,
    pub http_status: i32,
}

impl SteamDirectoryResult {
    pub fn ok(&self) -> bool {
        self.error.is_empty() && !self.servers.is_empty()
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct SteamDirectoryClient {
    ca_bundle_path: String,
}

impl SteamDirectoryClient {
    pub fn new(ca_bundle_path: impl Into<String>) -> Self {
        Self {
            ca_bundle_path: ca_bundle_path.into(),
        }
    }

    pub fn ca_bundle_path(&self) -> &str {
        &self.ca_bundle_path
    }

    pub fn build_url(cell_id: u32) -> String {
        format!(
            "{DIRECTORY_ENDPOINT}?cellid={cell_id}&cmtype=websockets&maxcount=20&realm=steamglobal"
        )
    }

    pub fn fetch(&self, cell_id: u32, timeout: Duration) -> SteamDirectoryResult {
        self.fetch_with_user_agent(cell_id, timeout, DEFAULT_USER_AGENT)
    }

    pub fn fetch_with_user_agent(
        &self,
        cell_id: u32,
        timeout: Duration,
        user_agent: &str,
    ) -> SteamDirectoryResult {
        let url = SteamDirectoryClient::build_url(cell_id);
        match self.http_get_text(&url, timeout, user_agent) {
            Ok(response) => {
                SteamDirectoryClient::validate_response(response.http_status, &response.body)
            }
            Err(error) => SteamDirectoryResult {
                error,
                ..Default::default()
            },
        }
    }

    pub fn validate_response(http_status: i32, body: &str) -> SteamDirectoryResult {
        if http_status != 200 {
            return SteamDirectoryResult {
                error: "non-200 HTTP status".to_string(),
                http_status,
                ..Default::default()
            };
        }
        let mut result = parse_directory_response(body);
        result.http_status = http_status;
        result
    }

    pub fn default_timeout() -> Duration {
        DEFAULT_TIMEOUT
    }

    fn http_get_text(
        &self,
        url: &str,
        timeout: Duration,
        user_agent: &str,
    ) -> Result<HttpTextResponse, String> {
        let client = self.http_client(timeout, user_agent)?;
        let response = client
            .get(url)
            .send()
            .map_err(|err| format!("http get: {err}"))?;
        let http_status = response.status().as_u16() as i32;
        let body = response.text().map_err(|err| format!("http body: {err}"))?;
        Ok(HttpTextResponse { http_status, body })
    }

    fn http_client(
        &self,
        timeout: Duration,
        user_agent: &str,
    ) -> Result<reqwest::blocking::Client, String> {
        let mut builder = reqwest::blocking::Client::builder()
            .user_agent(user_agent)
            .timeout(timeout)
            .connect_timeout(timeout);
        if !self.ca_bundle_path.is_empty() {
            let pem =
                fs::read(&self.ca_bundle_path).map_err(|err| format!("read CA bundle: {err}"))?;
            let certs = reqwest::Certificate::from_pem_bundle(&pem)
                .map_err(|err| format!("parse CA bundle: {err}"))?;
            for cert in certs {
                builder = builder.add_root_certificate(cert);
            }
        }
        builder.build().map_err(|err| format!("http client: {err}"))
    }
}

struct HttpTextResponse {
    http_status: i32,
    body: String,
}

pub fn parse_directory_response(body: &str) -> SteamDirectoryResult {
    let mut result = SteamDirectoryResult::default();
    let root: Value = match serde_json::from_str(body) {
        Ok(v) => v,
        Err(e) => {
            result.error = format!("json parse error: {e}");
            return result;
        }
    };
    let Some(response) = root.get("response") else {
        result.error = "directory response: missing response".to_string();
        return result;
    };
    let ok = response
        .get("success")
        .map(|s| {
            s.as_bool()
                .unwrap_or_else(|| s.as_i64().is_some_and(|n| n == 1))
        })
        .unwrap_or(false);
    if !ok {
        result.error = "directory response: success=false".to_string();
        return result;
    }
    let Some(list) = response.get("serverlist").and_then(|v| v.as_array()) else {
        result.error = "directory response: missing serverlist".to_string();
        return result;
    };

    for entry in list {
        let Some(endpoint) = entry.get("endpoint").and_then(|v| v.as_str()) else {
            continue;
        };
        let Some((host, port)) = parse_endpoint(endpoint) else {
            continue;
        };
        let transport = entry
            .get("type")
            .and_then(|v| v.as_str())
            .map(parse_transport)
            .unwrap_or(CmTransport::Unknown);
        if transport != CmTransport::WebSocket {
            continue;
        }
        result.servers.push(CmServer {
            endpoint: endpoint.to_string(),
            host,
            port,
            transport,
            realm: entry
                .get("realm")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            datacenter: entry
                .get("dc")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            load: entry.get("load").and_then(|v| v.as_i64()).unwrap_or(0) as i32,
            weighted_load: entry
                .get("wtd_load")
                .and_then(|v| v.as_f64())
                .unwrap_or(0.0) as f32,
        });
    }
    result
}

pub fn parse_transport(s: &str) -> CmTransport {
    match s {
        "websockets" | "websocket" => CmTransport::WebSocket,
        "netfilter" => CmTransport::Tcp,
        _ => CmTransport::Unknown,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_websocket_entries_and_filters_tcp() {
        let json = r#"{
          "response": {
            "success": 1,
            "serverlist": [
              {"endpoint":"ext1-sea1.steamserver.net:443","type":"websockets","realm":"steamglobal","dc":"sea1","load":1,"wtd_load":2.5},
              {"endpoint":"tcp.example.com:27017","type":"netfilter"},
              {"endpoint":"bad","type":"websockets"}
            ]
          }
        }"#;
        let result = parse_directory_response(json);
        assert!(result.error.is_empty());
        assert_eq!(result.servers.len(), 1);
        assert_eq!(result.servers[0].host, "ext1-sea1.steamserver.net");
        assert_eq!(result.servers[0].weighted_load, 2.5);
    }

    #[test]
    fn accepts_boolean_success() {
        let json = r#"{"response":{"success":true,"serverlist":[]}}"#;
        let result = parse_directory_response(json);
        assert!(result.error.is_empty());
        assert!(result.servers.is_empty());
    }

    #[test]
    fn builds_directory_url_and_tracks_ca_bundle() {
        let client = SteamDirectoryClient::new("/cacert.pem");
        assert_eq!(client.ca_bundle_path(), "/cacert.pem");
        assert_eq!(
            SteamDirectoryClient::default_timeout(),
            Duration::from_secs(10)
        );
        assert_eq!(
            SteamDirectoryClient::build_url(123),
            "https://api.steampowered.com/ISteamDirectory/GetCMListForConnect/v1/?cellid=123&cmtype=websockets&maxcount=20&realm=steamglobal"
        );
    }

    #[test]
    fn validates_http_status_before_json() {
        let result = SteamDirectoryClient::validate_response(503, "{}");
        assert_eq!(result.http_status, 503);
        assert_eq!(result.error, "non-200 HTTP status");

        let ok = SteamDirectoryClient::validate_response(
            200,
            r#"{"response":{"success":1,"serverlist":[{"endpoint":"ext1-sea1.steamserver.net:443","type":"websockets"}]}}"#,
        );
        assert_eq!(ok.http_status, 200);
        assert!(ok.ok());
    }
}
