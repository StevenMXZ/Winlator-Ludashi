use crate::base64;
use crate::job_manager::JobResult;
use crate::pb::cauthentication::{
    AllowedConfirmation, BeginAuthSessionViaCredentialsRequest,
    BeginAuthSessionViaCredentialsResponse, BeginAuthSessionViaQrRequest,
    BeginAuthSessionViaQrResponse, EAuthSessionGuardType, EAuthTokenPlatformType,
    ESessionPersistence, GetPasswordRsaPublicKeyRequest, GetPasswordRsaPublicKeyResponse,
    PollAuthSessionStatusRequest, PollAuthSessionStatusResponse,
    UpdateAuthSessionWithSteamGuardCodeRequest,
};
use crate::rsa_password::rsa_pkcs1v15_encrypt_password_with_hex_key;
use std::time::Duration;

pub const AUTH_WEBSITE_ID: &str = "Client";
pub const DEFAULT_WINDOWS_OS_TYPE: i32 = 16;
pub const DEFAULT_POLL_INTERVAL_SECONDS: f32 = 5.0;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AuthSessionResult {
    pub success: bool,
    pub eresult: i32,
    pub error_message: String,
    pub account_name: String,
    pub refresh_token: String,
    pub access_token: String,
    pub new_guard_data: String,
    pub steamid: u64,
    pub had_remote_interaction: bool,
    pub agreement_session_url: String,
}

impl Default for AuthSessionResult {
    fn default() -> Self {
        Self {
            success: false,
            eresult: 2,
            error_message: String::new(),
            account_name: String::new(),
            refresh_token: String::new(),
            access_token: String::new(),
            new_guard_data: String::new(),
            steamid: 0,
            had_remote_interaction: false,
            agreement_session_url: String::new(),
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CredentialsAuthConfig {
    pub username: String,
    pub password: String,
    pub device_friendly_name: String,
    pub guard_data: String,
    pub persistent_session: bool,
}

impl Default for CredentialsAuthConfig {
    fn default() -> Self {
        Self {
            username: String::new(),
            password: String::new(),
            device_friendly_name: "Ludashi-Steam-Client".to_string(),
            guard_data: String::new(),
            persistent_session: true,
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct QrAuthConfig {
    pub device_friendly_name: String,
}

impl Default for QrAuthConfig {
    fn default() -> Self {
        Self {
            device_friendly_name: "Ludashi-Steam-Client".to_string(),
        }
    }
}

pub fn secure_clear_string(s: &mut String) {
    if !s.is_empty() {
        // Zero bytes are valid UTF-8, so the String remains well-formed until
        // `clear` drops its logical contents.
        let bytes = unsafe { s.as_bytes_mut() };
        for byte in bytes {
            *byte = 0;
        }
    }
    s.clear();
}

pub fn sleep_slices(total: Duration, tick: Duration) -> impl Iterator<Item = Duration> {
    let mut remaining = total;
    std::iter::from_fn(move || {
        if remaining.is_zero() {
            return None;
        }
        let next = remaining.min(tick);
        remaining -= next;
        Some(next)
    })
}

#[derive(Clone, Debug, PartialEq)]
pub struct PendingCredentialsAuthSession {
    pub client_id: u64,
    pub request_id: Vec<u8>,
    pub poll_interval_seconds: f32,
    pub allowed_confirmations: Vec<AllowedConfirmation>,
    pub steamid: u64,
}

#[derive(Clone, Debug, PartialEq)]
pub struct PendingQrAuthSession {
    pub client_id: u64,
    pub request_id: Vec<u8>,
    pub poll_interval_seconds: f32,
    pub challenge_url: String,
}

pub fn build_password_rsa_request(
    config: &CredentialsAuthConfig,
) -> GetPasswordRsaPublicKeyRequest {
    GetPasswordRsaPublicKeyRequest {
        account_name: config.username.clone(),
    }
}

pub fn build_credentials_begin_request(
    config: &mut CredentialsAuthConfig,
    key: &GetPasswordRsaPublicKeyResponse,
) -> Result<BeginAuthSessionViaCredentialsRequest, AuthSessionResult> {
    let encrypted = rsa_pkcs1v15_encrypt_password_with_hex_key(
        &config.password,
        &key.publickey_mod,
        &key.publickey_exp,
    );
    secure_clear_string(&mut config.password);
    let Some(encrypted) = encrypted else {
        return Err(AuthSessionResult {
            error_message: "password RSA encryption failed".to_string(),
            ..Default::default()
        });
    };

    let guard_data = std::mem::take(&mut config.guard_data);
    let mut req = BeginAuthSessionViaCredentialsRequest {
        account_name: config.username.clone(),
        encrypted_password: base64::encode(&encrypted),
        encryption_timestamp: key.timestamp,
        website_id: AUTH_WEBSITE_ID.to_string(),
        persistence: if config.persistent_session {
            ESessionPersistence::Persistent
        } else {
            ESessionPersistence::Ephemeral
        },
        guard_data,
        ..Default::default()
    };
    req.device_details.device_friendly_name = config.device_friendly_name.clone();
    req.device_details.platform_type = EAuthTokenPlatformType::SteamClient;
    req.device_details.os_type = DEFAULT_WINDOWS_OS_TYPE;
    Ok(req)
}

pub fn pending_credentials_from_begin_response(
    resp: BeginAuthSessionViaCredentialsResponse,
) -> Result<PendingCredentialsAuthSession, AuthSessionResult> {
    if resp.client_id == 0 || resp.request_id.is_empty() {
        return Err(AuthSessionResult {
            eresult: 5,
            error_message: if resp.extended_error_message.is_empty() {
                "Steam rejected the credentials (no auth session created - likely bad password or unrecognized device)"
                    .to_string()
            } else {
                resp.extended_error_message
            },
            ..Default::default()
        });
    }
    Ok(PendingCredentialsAuthSession {
        client_id: resp.client_id,
        request_id: resp.request_id,
        poll_interval_seconds: resp.interval,
        allowed_confirmations: resp.allowed_confirmations,
        steamid: resp.steamid,
    })
}

pub fn choose_guard_confirmation(confirmations: &[AllowedConfirmation]) -> EAuthSessionGuardType {
    confirmations
        .iter()
        .find(|confirmation| {
            confirmation.confirmation_type == EAuthSessionGuardType::DeviceConfirmation
        })
        .or_else(|| {
            confirmations.iter().find(|confirmation| {
                matches!(
                    confirmation.confirmation_type,
                    EAuthSessionGuardType::DeviceCode | EAuthSessionGuardType::EmailCode
                )
            })
        })
        .or_else(|| {
            confirmations
                .iter()
                .find(|confirmation| confirmation.confirmation_type == EAuthSessionGuardType::None)
        })
        .map(|confirmation| confirmation.confirmation_type)
        .unwrap_or(EAuthSessionGuardType::None)
}

pub fn build_guard_code_request(
    client_id: u64,
    steamid: u64,
    code_type: EAuthSessionGuardType,
    code: String,
) -> UpdateAuthSessionWithSteamGuardCodeRequest {
    UpdateAuthSessionWithSteamGuardCodeRequest {
        client_id,
        steamid,
        code,
        code_type,
    }
}

pub fn guard_update_succeeded(job: &JobResult) -> bool {
    !job.synthetic_failure && matches!(job.eresult, 1 | 29)
}

pub fn build_poll_request(client_id: u64, request_id: Vec<u8>) -> PollAuthSessionStatusRequest {
    PollAuthSessionStatusRequest {
        client_id,
        request_id,
        token_to_revoke: 0,
    }
}

pub fn auth_result_from_poll(
    resp: PollAuthSessionStatusResponse,
    steamid: u64,
) -> Option<AuthSessionResult> {
    if resp.refresh_token.is_empty() {
        return None;
    }
    Some(AuthSessionResult {
        success: true,
        eresult: 1,
        account_name: resp.account_name,
        refresh_token: resp.refresh_token,
        access_token: resp.access_token,
        new_guard_data: resp.new_guard_data,
        steamid,
        had_remote_interaction: resp.had_remote_interaction,
        agreement_session_url: resp.agreement_session_url,
        ..Default::default()
    })
}

pub fn apply_account_name_fallback(
    result: &mut AuthSessionResult,
    fallback_account_name: &str,
) -> bool {
    if result.account_name.is_empty() && !fallback_account_name.is_empty() {
        result.account_name = fallback_account_name.to_string();
        return true;
    }
    false
}

pub fn apply_new_client_id(current_client_id: &mut u64, new_client_id: u64) -> bool {
    if new_client_id == 0 {
        return false;
    }
    *current_client_id = new_client_id;
    true
}

pub fn take_qr_challenge_update(
    last_challenge_url: &mut String,
    resp: &PollAuthSessionStatusResponse,
) -> Option<String> {
    if resp.new_challenge_url.is_empty() || resp.new_challenge_url == *last_challenge_url {
        return None;
    }
    *last_challenge_url = resp.new_challenge_url.clone();
    Some(last_challenge_url.clone())
}

pub fn take_qr_remote_interaction(
    reported_remote_interaction: &mut bool,
    resp: &PollAuthSessionStatusResponse,
) -> bool {
    if *reported_remote_interaction || !resp.had_remote_interaction {
        return false;
    }
    *reported_remote_interaction = true;
    true
}

pub fn build_qr_begin_request(config: &QrAuthConfig) -> BeginAuthSessionViaQrRequest {
    let mut req = BeginAuthSessionViaQrRequest {
        device_friendly_name: config.device_friendly_name.clone(),
        platform_type: EAuthTokenPlatformType::SteamClient,
        website_id: AUTH_WEBSITE_ID.to_string(),
        ..Default::default()
    };
    req.device_details.device_friendly_name = config.device_friendly_name.clone();
    req.device_details.platform_type = EAuthTokenPlatformType::SteamClient;
    req.device_details.os_type = DEFAULT_WINDOWS_OS_TYPE;
    req
}

pub fn pending_qr_from_begin_response(resp: BeginAuthSessionViaQrResponse) -> PendingQrAuthSession {
    PendingQrAuthSession {
        client_id: resp.client_id,
        request_id: resp.request_id,
        poll_interval_seconds: resp.interval,
        challenge_url: resp.challenge_url,
    }
}

pub fn job_error(job: JobResult, default_message: &'static str) -> AuthSessionResult {
    AuthSessionResult {
        eresult: job.eresult,
        error_message: if job.error_message.is_empty() {
            default_message.to_string()
        } else {
            job.error_message
        },
        ..Default::default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto_wire::Writer;
    use rand::rngs::OsRng;
    use rsa::traits::PublicKeyParts;

    #[test]
    fn default_auth_configs_match_cpp_defaults() {
        assert_eq!(
            CredentialsAuthConfig::default().device_friendly_name,
            "Ludashi-Steam-Client"
        );
        assert!(CredentialsAuthConfig::default().persistent_session);
        assert_eq!(
            QrAuthConfig::default().device_friendly_name,
            "Ludashi-Steam-Client"
        );
    }

    #[test]
    fn sleep_slices_cover_total_duration() {
        let slices: Vec<_> =
            sleep_slices(Duration::from_millis(250), Duration::from_millis(100)).collect();
        assert_eq!(
            slices,
            [
                Duration::from_millis(100),
                Duration::from_millis(100),
                Duration::from_millis(50)
            ]
        );
    }

    #[test]
    fn credentials_begin_request_matches_desktop_client_shape() {
        let private = rsa::RsaPrivateKey::new(&mut OsRng, 1024).unwrap();
        let public = private.to_public_key();
        let key = GetPasswordRsaPublicKeyResponse {
            publickey_mod: hex_encode(&public.n().to_bytes_be()),
            publickey_exp: hex_encode(&public.e().to_bytes_be()),
            timestamp: 123,
        };
        let mut config = CredentialsAuthConfig {
            username: "ada".into(),
            password: "correct horse".into(),
            device_friendly_name: "Ludashi".into(),
            guard_data: "guard".into(),
            persistent_session: false,
        };

        let req = build_credentials_begin_request(&mut config, &key).unwrap();

        assert_eq!(req.account_name, "ada");
        assert_eq!(req.encryption_timestamp, 123);
        assert!(!req.encrypted_password.is_empty());
        assert_eq!(req.website_id, "Client");
        assert_eq!(req.persistence, ESessionPersistence::Ephemeral);
        assert_eq!(
            req.device_details.platform_type,
            EAuthTokenPlatformType::SteamClient
        );
        assert_eq!(req.device_details.os_type, DEFAULT_WINDOWS_OS_TYPE);
        assert_eq!(req.guard_data, "guard");
        assert!(config.password.is_empty());
        assert!(config.guard_data.is_empty());
    }

    #[test]
    fn begin_response_rejects_empty_session_like_cpp() {
        let err = pending_credentials_from_begin_response(BeginAuthSessionViaCredentialsResponse {
            interval: 1.0,
            ..Default::default()
        })
        .unwrap_err();
        assert_eq!(err.eresult, 5);
        assert!(err.error_message.contains("Steam rejected"));
    }

    #[test]
    fn guard_confirmation_preference_matches_cpp() {
        let confirmations = [
            AllowedConfirmation {
                confirmation_type: EAuthSessionGuardType::EmailCode,
                associated_message: String::new(),
            },
            AllowedConfirmation {
                confirmation_type: EAuthSessionGuardType::DeviceConfirmation,
                associated_message: String::new(),
            },
        ];
        assert_eq!(
            choose_guard_confirmation(&confirmations),
            EAuthSessionGuardType::DeviceConfirmation
        );

        let confirmations = [AllowedConfirmation {
            confirmation_type: EAuthSessionGuardType::DeviceCode,
            associated_message: String::new(),
        }];
        assert_eq!(
            choose_guard_confirmation(&confirmations),
            EAuthSessionGuardType::DeviceCode
        );
        assert_eq!(choose_guard_confirmation(&[]), EAuthSessionGuardType::None);
    }

    #[test]
    fn qr_begin_request_uses_steam_client_audience() {
        let req = build_qr_begin_request(&QrAuthConfig {
            device_friendly_name: "Deck".into(),
        });
        assert_eq!(req.device_friendly_name, "Deck");
        assert_eq!(req.platform_type, EAuthTokenPlatformType::SteamClient);
        assert_eq!(req.website_id, "Client");
        assert_eq!(
            req.device_details.platform_type,
            EAuthTokenPlatformType::SteamClient
        );
        assert_eq!(req.device_details.os_type, DEFAULT_WINDOWS_OS_TYPE);
    }

    #[test]
    fn poll_response_becomes_success_only_when_refresh_token_exists() {
        assert!(auth_result_from_poll(PollAuthSessionStatusResponse::default(), 123).is_none());
        let result = auth_result_from_poll(
            PollAuthSessionStatusResponse {
                refresh_token: "refresh".into(),
                access_token: "access".into(),
                account_name: "ada".into(),
                new_guard_data: "guard".into(),
                had_remote_interaction: true,
                agreement_session_url: "https://steam.example/agreement".into(),
                ..Default::default()
            },
            765,
        )
        .unwrap();
        assert!(result.success);
        assert_eq!(result.steamid, 765);
        assert_eq!(result.account_name, "ada");
        assert!(result.had_remote_interaction);
    }

    #[test]
    fn account_name_fallback_restores_credentials_username() {
        let mut result = auth_result_from_poll(
            PollAuthSessionStatusResponse {
                refresh_token: "refresh".into(),
                access_token: "access".into(),
                ..Default::default()
            },
            765,
        )
        .unwrap();

        assert!(apply_account_name_fallback(&mut result, "ada"));
        assert_eq!(result.account_name, "ada");
        assert!(!apply_account_name_fallback(&mut result, "ignored"));
        assert_eq!(result.account_name, "ada");
    }

    #[test]
    fn poll_updates_client_id_and_qr_challenge_like_cpp() {
        let mut client_id = 10;
        assert!(!apply_new_client_id(&mut client_id, 0));
        assert_eq!(client_id, 10);
        assert!(apply_new_client_id(&mut client_id, 20));
        assert_eq!(client_id, 20);

        let mut last = "old".to_string();
        assert_eq!(
            take_qr_challenge_update(
                &mut last,
                &PollAuthSessionStatusResponse {
                    new_challenge_url: "new".into(),
                    ..Default::default()
                }
            ),
            Some("new".to_string())
        );
        assert_eq!(last, "new");
        assert_eq!(
            take_qr_challenge_update(
                &mut last,
                &PollAuthSessionStatusResponse {
                    new_challenge_url: "new".into(),
                    ..Default::default()
                }
            ),
            None
        );
    }

    #[test]
    fn poll_reports_remote_interaction_only_once() {
        let mut reported = false;
        assert!(!take_qr_remote_interaction(
            &mut reported,
            &PollAuthSessionStatusResponse::default()
        ));
        assert!(take_qr_remote_interaction(
            &mut reported,
            &PollAuthSessionStatusResponse {
                had_remote_interaction: true,
                ..Default::default()
            }
        ));
        assert!(reported);
        assert!(!take_qr_remote_interaction(
            &mut reported,
            &PollAuthSessionStatusResponse {
                had_remote_interaction: true,
                ..Default::default()
            }
        ));
    }

    #[test]
    fn guard_update_accepts_duplicate_request() {
        assert!(guard_update_succeeded(&JobResult {
            eresult: 29,
            error_message: String::new(),
            body: Vec::new(),
            synthetic_failure: false,
        }));
        assert!(!guard_update_succeeded(&JobResult {
            eresult: 29,
            error_message: String::new(),
            body: Vec::new(),
            synthetic_failure: true,
        }));
    }

    #[test]
    fn request_builders_serialize_expected_identity_fields() {
        let rsa_req = build_password_rsa_request(&CredentialsAuthConfig {
            username: "user".into(),
            ..Default::default()
        });
        assert_eq!(rsa_req.serialize(), {
            let mut out = Vec::new();
            Writer::new(&mut out).string_field(1, "user");
            out
        });

        let poll = build_poll_request(42, vec![1, 2, 3]);
        assert_eq!(poll.client_id, 42);
        assert_eq!(poll.request_id, [1, 2, 3]);

        let guard = build_guard_code_request(
            7,
            765,
            EAuthSessionGuardType::EmailCode,
            "12345".to_string(),
        );
        assert_eq!(guard.client_id, 7);
        assert_eq!(guard.steamid, 765);
        assert_eq!(guard.code_type, EAuthSessionGuardType::EmailCode);
    }

    fn hex_encode(bytes: &[u8]) -> String {
        const HEX: &[u8; 16] = b"0123456789abcdef";
        let mut out = String::with_capacity(bytes.len() * 2);
        for b in bytes {
            out.push(HEX[(b >> 4) as usize] as char);
            out.push(HEX[(b & 0x0f) as usize] as char);
        }
        out
    }
}
