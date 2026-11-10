#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
#[repr(u8)]
pub enum CmTransport {
    #[default]
    Unknown = 0,
    WebSocket = 1,
    Tcp = 2,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct CmServer {
    pub endpoint: String,
    pub host: String,
    pub port: u16,
    pub transport: CmTransport,
    pub realm: String,
    pub datacenter: String,
    pub load: i32,
    pub weighted_load: f32,
}

impl CmServer {
    pub fn websocket_url(&self) -> String {
        if self.transport != CmTransport::WebSocket || self.host.is_empty() || self.port == 0 {
            return String::new();
        }
        format!("wss://{}:{}/cmsocket/", self.host, self.port)
    }
}

pub fn parse_endpoint(endpoint: &str) -> Option<(String, u16)> {
    let colon = endpoint.rfind(':')?;
    if colon == 0 || colon + 1 == endpoint.len() {
        return None;
    }
    let mut host = &endpoint[..colon];
    if host.len() >= 2 && host.starts_with('[') && host.ends_with(']') {
        host = &host[1..host.len() - 1];
    }
    let port_text = &endpoint[colon + 1..];
    if !port_text.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let port = port_text.parse::<u32>().ok()?;
    if port == 0 || port > u16::MAX as u32 {
        return None;
    }
    Some((host.to_string(), port as u16))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_host_port_and_ipv6_brackets() {
        assert_eq!(
            parse_endpoint("cm.example.com:443"),
            Some(("cm.example.com".to_string(), 443))
        );
        assert_eq!(
            parse_endpoint("[2001:db8::1]:27017"),
            Some(("2001:db8::1".to_string(), 27017))
        );
        assert_eq!(parse_endpoint("missing-port"), None);
        assert_eq!(parse_endpoint("host:0"), None);
        assert_eq!(parse_endpoint("host:+443"), None);
        assert_eq!(parse_endpoint("host:70000"), None);
    }

    #[test]
    fn websocket_url_requires_websocket_transport() {
        let server = CmServer {
            host: "cm.example.com".to_string(),
            port: 443,
            transport: CmTransport::WebSocket,
            ..Default::default()
        };
        assert_eq!(server.websocket_url(), "wss://cm.example.com:443/cmsocket/");
    }
}
