use crate::pb::cmsg_client_license_list::CMsgClientLicenseList;
use crate::pb::cmsg_client_pics::{
    CMsgClientPICSAccessTokenResponse, CMsgClientPICSProductInfoResponse, PicsAppInfoReq,
    PicsPackageInfoReq,
};
use crate::vdf::{self, KVNode};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};

#[derive(Clone, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
pub struct OwnedPackage {
    pub package_id: u32,
    pub access_token: u64,
    pub change_number: i32,
    pub license_flags: u32,
    pub license_type: u32,
    pub pics_fetched: bool,
    pub app_ids: Vec<u32>,
    pub depot_ids: Vec<u32>,
}

/// Where an app's library logo sits over its hero image
/// (`common.library_assets_full.library_logo.logo_position`, or the compact
/// `common.library_assets.logo_position`). Percentages arrive as decimal strings.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct LogoPosition {
    pub width_pct: f64,
    pub height_pct: f64,
    pub pinned_position: String,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct AppArtwork {
    /// 92:43 store capsule (`header.jpg`, 460x215) — `common.header_image.<lang>`.
    pub header: String,
    /// 231x87 store-list capsule — `common.small_capsule.<lang>`.
    pub small_capsule: String,
    /// Portrait library capsule (600x900) — `library_assets_full.library_capsule.image`.
    pub library_capsule: String,
    pub library_capsule_2x: String,
    /// Wide library header, published by only some apps (`library_header.image`).
    pub library_header: String,
    pub library_hero: String,
    pub library_logo: String,
    /// Hash-valued community images: `common.icon` / `clienticon` / `logo`.
    pub icon: String,
    pub client_icon: String,
    pub logo: String,
    /// `common.store_asset_mtime` — bump this and every store asset above has changed.
    pub store_asset_mtime: u32,
    pub logo_position: Option<LogoPosition>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct OwnedApp {
    pub app_id: u32,
    pub change_number: u32,
    pub name: String,
    pub sort_as: String,
    pub app_type: String,
    pub os_list: String,
    pub parent_app_id: u32,
    pub dlc_app_ids: Vec<u32>,
    pub build_id: u32,
    pub source_package_ids: Vec<u32>,
    pub pics_fetched: bool,
    pub missing_token: bool,
    pub access_token: u64,
    pub artwork: AppArtwork,
}

type SnapshotObserver = Arc<dyn Fn() + Send + Sync + 'static>;

#[derive(Default)]
pub struct LudashiLibraryStore {
    packages: Mutex<HashMap<u32, OwnedPackage>>,
    apps: Mutex<HashMap<u32, OwnedApp>>,
    observer: Mutex<Option<SnapshotObserver>>,
}

impl LudashiLibraryStore {
    pub fn ingest_license_list(&self, msg: &CMsgClientLicenseList) {
        {
            let mut packages = self.packages.lock().expect("library packages poisoned");
            let current: HashSet<_> = msg.licenses.iter().map(|license| license.package_id).collect();
            packages.retain(|id, _| current.contains(id));
            for license in &msg.licenses {
                let slot = packages.entry(license.package_id).or_default();
                slot.package_id = license.package_id;
                if license.access_token != 0 {
                    slot.access_token = license.access_token;
                }
                if license.change_number != slot.change_number {
                    slot.change_number = license.change_number;
                    slot.pics_fetched = false;
                    slot.app_ids.clear();
                    slot.depot_ids.clear();
                }
                slot.license_flags = license.flags;
                slot.license_type = license.license_type;
            }
            let mut apps = self.apps.lock().expect("library apps poisoned");
            for app in apps.values_mut() {
                let app_id = app.app_id;
                app.source_package_ids.retain(|id| packages.get(id)
                    .map_or(false, |package| package.app_ids.contains(&app_id)));
            }
        }
        self.notify();
    }

    pub fn get_pending_package_pics_request(&self, max_count: usize) -> Vec<PicsPackageInfoReq> {
        let packages = self.packages.lock().expect("library packages poisoned");
        packages
            .values()
            .filter(|p| !p.pics_fetched)
            .take(max_count)
            .map(|p| PicsPackageInfoReq {
                packageid: p.package_id,
                access_token: p.access_token,
            })
            .collect()
    }

    pub fn get_pending_app_pics_request(&self, max_count: usize) -> Vec<PicsAppInfoReq> {
        let apps = self.apps.lock().expect("library apps poisoned");
        apps.values()
            .filter(|a| !a.pics_fetched && (!a.missing_token || a.access_token != 0))
            .take(max_count)
            .map(|a| PicsAppInfoReq {
                appid: a.app_id,
                access_token: a.access_token,
                only_public_obsolete: false,
            })
            .collect()
    }

    pub fn get_apps_needing_access_token(&self) -> Vec<u32> {
        let apps = self.apps.lock().expect("library apps poisoned");
        apps.values()
            .filter(|a| a.missing_token && a.access_token == 0)
            .map(|a| a.app_id)
            .collect()
    }

    pub fn ingest_package_pics_response(&self, resp: &CMsgClientPICSProductInfoResponse) {
        {
            let mut packages = self.packages.lock().expect("library packages poisoned");
            let mut apps = self.apps.lock().expect("library apps poisoned");
            for package in &resp.packages {
                let slot = packages.entry(package.packageid).or_default();
                slot.package_id = package.packageid;
                slot.change_number = package.change_number as i32;
                slot.pics_fetched = true;
                if !package.buffer.is_empty() {
                    if let Some((_prefix, root)) = vdf::parse_binary_package(&package.buffer) {
                        extract_uint32_array(root.child("appids"), &mut slot.app_ids);
                        extract_uint32_array(root.child("depotids"), &mut slot.depot_ids);
                        for app_id in &slot.app_ids {
                            let app = apps.entry(*app_id).or_default();
                            app.app_id = *app_id;
                            if !app.source_package_ids.contains(&package.packageid) {
                                app.source_package_ids.push(package.packageid);
                            }
                        }
                    }
                }
            }
            for package_id in &resp.unknown_packageids {
                if let Some(package) = packages.get_mut(package_id) {
                    package.pics_fetched = true;
                }
            }
        }
        self.notify();
    }

    pub fn ingest_app_pics_response(&self, resp: &CMsgClientPICSProductInfoResponse) {
        {
            let mut apps = self.apps.lock().expect("library apps poisoned");
            for app_resp in &resp.apps {
                let app = apps.entry(app_resp.appid).or_default();
                app.app_id = app_resp.appid;
                app.change_number = app_resp.change_number;
                app.pics_fetched = true;
                if app_resp.missing_token {
                    app.missing_token = true;
                    app.pics_fetched = false;
                    continue;
                }
                app.missing_token = false;
                if app_resp.buffer.is_empty() {
                    continue;
                }
                let Some(root) = vdf::parse_auto(&app_resp.buffer) else {
                    continue;
                };
                let appinfo = if root.name.eq_ignore_ascii_case("appinfo") {
                    &root
                } else {
                    root.child("appinfo").unwrap_or(&root)
                };
                if let Some(common) = appinfo.child("common") {
                    set_string(common, "name", &mut app.name);
                    set_string(common, "sortas", &mut app.sort_as);
                    set_string(common, "type", &mut app.app_type);
                    set_string(common, "oslist", &mut app.os_list);
                    if let Some(parent) = common.child("parent") {
                        app.parent_app_id = parent.as_uint(0) as u32;
                    }
                    // Steam's own published art, so the UI never has to guess a capsule URL.
                    app.artwork = parse_app_artwork(app.app_id, common);
                }
                if let Some(list) = appinfo
                    .child("extended")
                    .and_then(|extended| extended.child("listofdlc"))
                {
                    app.dlc_app_ids.clear();
                    parse_csv_appids(&list.as_string(""), &mut app.dlc_app_ids);
                }
                if let Some(buildid) = appinfo
                    .child("depots")
                    .and_then(|depots| depots.child("branches"))
                    .and_then(|branches| branches.child("public"))
                    .and_then(|public| public.child("buildid"))
                {
                    app.build_id = buildid.as_uint(0) as u32;
                }
                let child_id = app.app_id;
                let parent_id = app.parent_app_id;
                if parent_id != 0 {
                    let parent = apps.entry(parent_id).or_default();
                    parent.app_id = parent_id;
                    if !parent.dlc_app_ids.contains(&child_id) {
                        parent.dlc_app_ids.push(child_id);
                    }
                }
            }
            for app_id in &resp.unknown_appids {
                if let Some(app) = apps.get_mut(app_id) {
                    app.pics_fetched = true;
                }
            }
        }
        self.notify();
    }

    pub fn ingest_app_access_tokens(&self, resp: &CMsgClientPICSAccessTokenResponse) {
        {
            let mut apps = self.apps.lock().expect("library apps poisoned");
            for token in &resp.app_access_tokens {
                let app = apps.entry(token.appid).or_default();
                app.app_id = token.appid;
                app.access_token = token.access_token;
                app.missing_token = false;
            }
            for app_id in &resp.app_denied_tokens {
                if let Some(app) = apps.get_mut(app_id) {
                    app.pics_fetched = true;
                    app.missing_token = false;
                }
            }
        }
        self.notify();
    }

    pub fn packages(&self) -> Vec<OwnedPackage> {
        self.packages
            .lock()
            .expect("library packages poisoned")
            .values()
            .cloned()
            .collect()
    }

    pub fn apps(&self) -> Vec<OwnedApp> {
        self.apps
            .lock()
            .expect("library apps poisoned")
            .values()
            .cloned()
            .collect()
    }

    pub fn owned_apps(&self) -> Vec<OwnedApp> {
        self.apps()
            .into_iter()
            .filter(|app| !app.source_package_ids.is_empty())
            .collect()
    }

    pub fn find_app(&self, app_id: u32) -> Option<OwnedApp> {
        self.apps
            .lock()
            .expect("library apps poisoned")
            .get(&app_id)
            .cloned()
    }

    pub fn package_count(&self) -> usize {
        self.packages
            .lock()
            .expect("library packages poisoned")
            .len()
    }

    pub fn app_count(&self) -> usize {
        self.apps.lock().expect("library apps poisoned").len()
    }

    pub fn owned_app_count(&self) -> usize {
        self.apps
            .lock()
            .expect("library apps poisoned")
            .values()
            .filter(|app| !app.source_package_ids.is_empty())
            .count()
    }

    pub fn cache_json(&self) -> String {
        json!({"version": 1, "packages": self.packages(), "apps": self.owned_apps()}).to_string()
    }

    pub fn restore_cache_json(&self, text: &str) -> Result<(), serde_json::Error> {
        #[derive(Deserialize)]
        struct Cache {
            version: u32,
            packages: Vec<OwnedPackage>,
            apps: Vec<OwnedApp>,
        }
        let cache: Cache = serde_json::from_str(text)?;
        if cache.version != 1 {
            return Err(<serde_json::Error as serde::de::Error>::custom("unsupported library cache"));
        }
        {
            let mut packages = self.packages.lock().expect("library packages poisoned");
            let mut apps = self.apps.lock().expect("library apps poisoned");
            for cached in cache.packages {
                let Some(package) = packages.get_mut(&cached.package_id) else { continue };
                if !package.pics_fetched && cached.pics_fetched && package.change_number == cached.change_number {
                    package.pics_fetched = true;
                    package.app_ids = cached.app_ids;
                    package.depot_ids = cached.depot_ids;
                }
            }
            let mut sources: HashMap<u32, Vec<u32>> = HashMap::new();
            for package in packages.values().filter(|p| p.pics_fetched) {
                for id in &package.app_ids {
                    sources.entry(*id).or_default().push(package.package_id);
                }
            }
            for mut cached in cache.apps {
                let Some(ids) = sources.remove(&cached.app_id) else { continue };
                cached.source_package_ids = ids;
                apps.entry(cached.app_id).or_insert(cached);
            }
            for (id, ids) in sources {
                let app = apps.entry(id).or_default();
                app.app_id = id;
                app.source_package_ids = ids;
            }
        }
        self.notify();
        Ok(())
    }

    pub fn snapshot_json(&self) -> String {
        let packages = self.packages();
        let apps = self.apps();
        let owned: Vec<_> = apps
            .iter()
            .filter(|app| !app.source_package_ids.is_empty())
            .collect();
        json!({
            "packages": packages.iter().map(|p| json!({
                "id": p.package_id,
                "pics_fetched": p.pics_fetched,
                "flags": p.license_flags,
                "license_type": p.license_type,
                "change_number": p.change_number,
                "access_token": p.access_token.to_string(),
            })).collect::<Vec<_>>(),
            "owned_apps": owned.iter().map(|a| json!({
                "id": a.app_id,
                "pics_fetched": a.pics_fetched,
                "change_number": a.change_number,
                "name": a.name,
                "type": a.app_type,
                "sort_as": a.sort_as,
                "os_list": a.os_list,
                "parent": a.parent_app_id,
                "access_token": a.access_token.to_string(),
                "build_id": a.build_id,
                "dlc": a.dlc_app_ids,
                "src_packages": a.source_package_ids,
                "art": artwork_json(&a.artwork),
            })).collect::<Vec<_>>(),
            "all_apps_count": apps.len(),
            "owned_apps_count": owned.len(),
        })
        .to_string()
    }

    pub fn set_observer<F>(&self, observer: F)
    where
        F: Fn() + Send + Sync + 'static,
    {
        *self.observer.lock().expect("library observer poisoned") = Some(Arc::new(observer));
    }

    fn notify(&self) {
        let cb = self
            .observer
            .lock()
            .expect("library observer poisoned")
            .clone();
        if let Some(cb) = cb {
            cb();
        }
    }
}

fn extract_uint32_array(parent: Option<&KVNode>, out: &mut Vec<u32>) {
    if let Some(parent) = parent {
        for child in &parent.children {
            let value = child.as_uint(0);
            if value != 0 {
                out.push(value as u32);
            }
        }
    }
}

fn parse_csv_appids(csv: &str, out: &mut Vec<u32>) {
    for part in csv.split(',') {
        if let Ok(value) = part.trim().parse::<u32>() {
            if value != 0 {
                out.push(value);
            }
        }
    }
}

fn set_string(parent: &KVNode, key: &str, out: &mut String) {
    if let Some(node) = parent.child(key) {
        *out = node.as_string(out);
    }
}


/// Root for `common.header_image` / `small_capsule` / `library_assets_full.*` values, which are
/// paths RELATIVE to `<root>/<appid>/` and may carry a content-hash directory prefix.
const STORE_ITEM_ASSETS_BASE: &str =
    "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps";

/// Root for the hash-valued `common.icon` / `clienticon` / `logo` fields, which are bare SHA-1
/// hex with the extension implied by the field (`.jpg`, except `clienticon` which is `.ico`).
const COMMUNITY_IMAGES_BASE: &str =
    "https://cdn.fastly.steamstatic.com/steamcommunity/public/images/apps";

/// Language preferred when an art key is a per-language map. Steam always publishes `english`
/// for apps that publish anything; other keys seen in the wild are `schinese` / `sc_schinese` /
/// `tchinese`, which we accept as a fallback rather than showing nothing.
const ART_LANGUAGE: &str = "english";

/// Pick a localized art value: the preferred language, else the first non-empty entry.
fn pick_localized(node: Option<&KVNode>) -> String {
    let Some(node) = node else {
        return String::new();
    };
    if let Some(preferred) = node.child(ART_LANGUAGE) {
        let value = preferred.as_string("");
        if !value.is_empty() {
            return value;
        }
    }
    for child in &node.children {
        let value = child.as_string("");
        if !value.is_empty() {
            return value;
        }
    }
    // Some apps publish the asset as a bare scalar instead of a per-language map. Taking it is
    // safe: it still has to clear `is_safe_relative_asset` before it can become a URL.
    node.as_string("")
}

/// True when a Steam-supplied relative asset path is safe to paste into a URL.
///
/// These strings come off the wire, so anything that could escape the app's own asset directory
/// or inject a different origin is rejected outright rather than emitted.
fn is_safe_relative_asset(path: &str) -> bool {
    // Must name a file. This also stops the compact `library_assets` block's language codes
    // ("en", "en,zh-xc") from ever being mistaken for a filename.
    let names_a_file = path
        .rsplit('/')
        .next()
        .is_some_and(|leaf| leaf.contains('.') && !leaf.ends_with('.'));
    names_a_file
        && !path.is_empty()
        && path.len() <= 256
        && !path.starts_with('/')
        && !path.contains("..")
        && !path.contains("//")
        && !path.contains(':')
        && !path.contains('?')
        && !path.contains('#')
        && !path.contains('\\')
        && path
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '/' | '.' | '_' | '-'))
}

/// True for the bare hash values of `icon` / `clienticon` / `logo` (`logo_small` may carry a
/// `_thumb` suffix), so a malformed value never becomes a URL.
fn is_safe_asset_hash(hash: &str) -> bool {
    !hash.is_empty()
        && hash.len() <= 64
        && hash
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_')
}

/// `<STORE_ITEM_ASSETS_BASE>/<app_id>/<relative>`; empty when nothing is published.
fn store_item_asset_url(app_id: u32, relative: &str) -> String {
    if !is_safe_relative_asset(relative) {
        return String::new();
    }
    format!("{STORE_ITEM_ASSETS_BASE}/{app_id}/{relative}")
}

/// `<COMMUNITY_IMAGES_BASE>/<app_id>/<hash>.<ext>`; empty when nothing is published.
fn community_image_url(app_id: u32, hash: &str, ext: &str) -> String {
    if !is_safe_asset_hash(hash) {
        return String::new();
    }
    format!("{COMMUNITY_IMAGES_BASE}/{app_id}/{hash}.{ext}")
}

/// Read a `logo_position` block (percentages arrive as decimal strings).
fn parse_logo_position(node: Option<&KVNode>) -> Option<LogoPosition> {
    let node = node?;
    let width_pct = node.child("width_pct")?.as_string("").parse::<f64>().ok()?;
    let height_pct = node.child("height_pct")?.as_string("").parse::<f64>().ok()?;
    Some(LogoPosition {
        width_pct,
        height_pct,
        pinned_position: node
            .child("pinned_position")
            .map(|n| n.as_string(""))
            .unwrap_or_default(),
    })
}

/// Resolve every published art asset in an `appinfo.common` block to an absolute CDN URL.
fn parse_app_artwork(app_id: u32, common: &KVNode) -> AppArtwork {
    let mut art = AppArtwork {
        header: store_item_asset_url(app_id, &pick_localized(common.child("header_image"))),
        small_capsule: store_item_asset_url(app_id, &pick_localized(common.child("small_capsule"))),
        icon: community_image_url(app_id, &common.child("icon").map(|n| n.as_string("")).unwrap_or_default(), "jpg"),
        client_icon: community_image_url(
            app_id,
            &common.child("clienticon").map(|n| n.as_string("")).unwrap_or_default(),
            // clienticon is a Windows .ico, unlike every other hash-valued field.
            "ico",
        ),
        logo: community_image_url(app_id, &common.child("logo").map(|n| n.as_string("")).unwrap_or_default(), "jpg"),
        store_asset_mtime: common
            .child("store_asset_mtime")
            .map(|n| n.as_uint(0) as u32)
            .unwrap_or(0),
        ..AppArtwork::default()
    };

    if let Some(full) = common.child("library_assets_full") {
        let slot = |name: &str, sub: &str| -> String {
            store_item_asset_url(
                app_id,
                &pick_localized(full.child(name).and_then(|node| node.child(sub))),
            )
        };
        art.library_capsule = slot("library_capsule", "image");
        art.library_capsule_2x = slot("library_capsule", "image2x");
        art.library_header = slot("library_header", "image");
        art.library_hero = slot("library_hero", "image");
        art.library_logo = slot("library_logo", "image");
        art.logo_position = parse_logo_position(
            full.child("library_logo")
                .and_then(|logo| logo.child("logo_position")),
        );
    }
    // The compact `library_assets` block carries only language availability, EXCEPT for
    // logo_position — use it when the full block did not supply one.
    if art.logo_position.is_none() {
        art.logo_position = parse_logo_position(
            common
                .child("library_assets")
                .and_then(|assets| assets.child("logo_position")),
        );
    }
    art
}

fn artwork_json(art: &AppArtwork) -> serde_json::Value {
    json!({
        "header": art.header,
        "small_capsule": art.small_capsule,
        "library_capsule": art.library_capsule,
        "library_capsule_2x": art.library_capsule_2x,
        "library_header": art.library_header,
        "library_hero": art.library_hero,
        "library_logo": art.library_logo,
        "icon": art.icon,
        "client_icon": art.client_icon,
        "logo": art.logo,
        "store_asset_mtime": art.store_asset_mtime,
        "logo_position": art.logo_position.as_ref().map(|pos| json!({
            "width_pct": pos.width_pct,
            "height_pct": pos.height_pct,
            "pinned_position": pos.pinned_position,
        })),
    })
}

#[cfg(test)]
mod artwork_tests {
    use super::*;
    use crate::vdf::KVValue;

    fn insert_path(node: &mut KVNode, parts: &[&str], value: &str) {
        let Some((head, rest)) = parts.split_first() else {
            return;
        };
        let pos = match node.children.iter().position(|c| c.name == *head) {
            Some(pos) => pos,
            None => {
                node.children.push(KVNode::new(*head));
                node.children.len() - 1
            }
        };
        if rest.is_empty() {
            node.children[pos].value = KVValue::String(value.to_string());
        } else {
            insert_path(&mut node.children[pos], rest, value);
        }
    }

    /// Build a `common` subtree from `("a/b/c", "value")` paths.
    fn common_from(paths: &[(&str, &str)]) -> KVNode {
        let mut root = KVNode::new("common");
        for (path, value) in paths {
            let parts: Vec<&str> = path.split('/').collect();
            insert_path(&mut root, &parts, value);
        }
        root
    }

    // Every published value below is the REAL appinfo content for that app, and every URL these
    // tests assert was confirmed to return HTTP 200 from Steam's CDN.

    #[test]
    fn half_life_publishes_unhashed_asset_names() {
        let common = common_from(&[
            ("header_image/english", "header.jpg"),
            ("small_capsule/english", "25bee0c9572a4f0dc4de6c773c54d067a4204760/capsule_231x87.jpg"),
            ("library_assets_full/library_capsule/image/english", "library_600x900.jpg"),
            ("library_assets_full/library_capsule/image2x/english", "library_600x900_2x.jpg"),
            ("library_assets_full/library_hero/image/english", "library_hero.jpg"),
            ("library_assets_full/library_logo/image/english", "logo.png"),
            ("clienticon", "d991f95d96e1c76d2acb944bb09447628cd96caa"),
            ("icon", "95be6d131fc61f145797317ca437c9765f24b41c"),
            ("logo", "6bd76ff700a8c7a5460fbae3cf60cb930279897d"),
            ("store_asset_mtime", "1745368459"),
        ]);
        let art = parse_app_artwork(70, &common);
        assert_eq!(
            art.header,
            "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/70/header.jpg"
        );
        assert_eq!(
            art.small_capsule,
            "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/70/25bee0c9572a4f0dc4de6c773c54d067a4204760/capsule_231x87.jpg"
        );
        assert_eq!(
            art.library_capsule,
            "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/70/library_600x900.jpg"
        );
        // clienticon is the one hash-valued field served as .ico, not .jpg.
        assert_eq!(
            art.client_icon,
            "https://cdn.fastly.steamstatic.com/steamcommunity/public/images/apps/70/d991f95d96e1c76d2acb944bb09447628cd96caa.ico"
        );
        assert_eq!(
            art.icon,
            "https://cdn.fastly.steamstatic.com/steamcommunity/public/images/apps/70/95be6d131fc61f145797317ca437c9765f24b41c.jpg"
        );
        assert_eq!(art.store_asset_mtime, 1_745_368_459);
        // Half-Life publishes no library_header.
        assert!(art.library_header.is_empty());
    }

    #[test]
    fn half_life_2_keeps_the_content_hash_directory() {
        // The hash prefix is part of the published path and must survive verbatim.
        let common = common_from(&[
            ("library_assets_full/library_capsule/image/english", "ac2f074d790656a06ef8305bd54a6f64e9a70082/library_600x900.jpg"),
            ("library_assets_full/library_header/image/english", "b91f57c06260776c04648d061aba6e8de494ef59/library_header.jpg"),
            ("library_assets_full/library_logo/logo_position/width_pct", "57"),
            ("library_assets_full/library_logo/logo_position/height_pct", "34"),
            ("library_assets_full/library_logo/logo_position/pinned_position", "BottomLeft"),
        ]);
        let art = parse_app_artwork(220, &common);
        assert_eq!(
            art.library_capsule,
            "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/220/ac2f074d790656a06ef8305bd54a6f64e9a70082/library_600x900.jpg"
        );
        assert_eq!(
            art.library_header,
            "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/220/b91f57c06260776c04648d061aba6e8de494ef59/library_header.jpg"
        );
        let pos = art.logo_position.expect("logo_position");
        assert_eq!(pos.width_pct, 57.0);
        assert_eq!(pos.height_pct, 34.0);
        assert_eq!(pos.pinned_position, "BottomLeft");
    }

    #[test]
    fn dota_2_capsule_filename_is_not_library_600x900() {
        // The exact case a constructed filename gets wrong: Dota 2 names its portrait capsule
        // `library_capsule.jpg`. Only the published list knows that.
        let common = common_from(&[(
            "library_assets_full/library_capsule/image/english",
            "6843027380c3bfd0952449fd9174f492ef2e7b40/library_capsule.jpg",
        )]);
        let art = parse_app_artwork(570, &common);
        assert!(art.library_capsule.ends_with("/library_capsule.jpg"));
        assert!(!art.library_capsule.contains("library_600x900"));
    }

    #[test]
    fn counter_strike_2_header_is_hash_prefixed() {
        let common = common_from(&[(
            "header_image/english",
            "162664aa5da85f418105350c5d67ca565f6c3713/header.jpg",
        )]);
        assert_eq!(
            parse_app_artwork(730, &common).header,
            "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/730/162664aa5da85f418105350c5d67ca565f6c3713/header.jpg"
        );
    }

    #[test]
    fn falls_back_to_another_language_but_prefers_english() {
        let both = common_from(&[
            ("header_image/schinese", "header_schinese.jpg"),
            ("header_image/english", "header.jpg"),
        ]);
        assert!(parse_app_artwork(1, &both).header.ends_with("/header.jpg"));

        let only_chinese = common_from(&[("header_image/sc_schinese", "header_sc.jpg")]);
        assert!(parse_app_artwork(1, &only_chinese)
            .header
            .ends_with("/header_sc.jpg"));
    }

    #[test]
    fn missing_art_yields_empty_strings_not_guesses() {
        let art = parse_app_artwork(12345, &KVNode::new("common"));
        assert!(art.header.is_empty());
        assert!(art.library_capsule.is_empty());
        assert!(art.icon.is_empty());
        assert!(art.logo_position.is_none());
        assert_eq!(art.store_asset_mtime, 0);
    }

    #[test]
    fn rejects_wire_values_that_would_escape_or_redirect_the_url() {
        for hostile in [
            "../../etc/passwd",
            "/absolute.jpg",
            "https://evil.example/x.jpg",
            "a//b.jpg",
            "x.jpg?y=1",
            "x.jpg#f",
            "..\\win.jpg",
        ] {
            let common = common_from(&[("header_image/english", hostile)]);
            assert!(
                parse_app_artwork(1, &common).header.is_empty(),
                "must reject {hostile:?}"
            );
        }
        // A hash field with a slash is not a hash.
        let common = common_from(&[("icon", "abc/../../x")]);
        assert!(parse_app_artwork(1, &common).icon.is_empty());
    }

    #[test]
    fn a_value_without_a_filename_is_not_an_asset() {
        // Guards the scalar fallback in pick_localized: a bare language code must never
        // become ".../<appid>/en".
        for not_a_file in ["en", "en,zh-xc", "trailing."] {
            let common = common_from(&[("header_image/english", not_a_file)]);
            assert!(
                parse_app_artwork(1, &common).header.is_empty(),
                "must reject {not_a_file:?}"
            );
        }
    }

    #[test]
    fn logo_position_falls_back_to_the_compact_block() {
        let common = common_from(&[
            ("library_assets/logo_position/width_pct", "77.01516064953753"),
            ("library_assets/logo_position/height_pct", "43.685387882494695"),
            ("library_assets/logo_position/pinned_position", "BottomCenter"),
        ]);
        let pos = parse_app_artwork(70, &common)
            .logo_position
            .expect("logo_position from compact block");
        assert_eq!(pos.pinned_position, "BottomCenter");
        assert!((pos.width_pct - 77.015_160_649_537_53).abs() < 1e-9);
    }

    #[test]
    fn compact_library_assets_language_list_is_never_treated_as_a_filename() {
        // `library_assets.library_capsule` is "en" (availability), NOT a path. Only
        // `library_assets_full` carries real filenames.
        let common = common_from(&[("library_assets/library_capsule", "en")]);
        assert!(parse_app_artwork(70, &common).library_capsule.is_empty());
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pb::cmsg_client_license_list::{CMsgClientLicenseList, License};
    use crate::pb::cmsg_client_pics::{PicsAppInfoResp, PicsPackageInfoResp};

    #[test]
    fn ingests_license_and_emits_pending_package_request() {
        let store = LudashiLibraryStore::default();
        store.ingest_license_list(&CMsgClientLicenseList {
            eresult: 1,
            licenses: vec![License {
                package_id: 100,
                access_token: 55,
                change_number: 7,
                ..Default::default()
            }],
        });
        let pending = store.get_pending_package_pics_request(10);
        assert_eq!(pending[0].packageid, 100);
        assert_eq!(pending[0].access_token, 55);
    }

    #[test]
    fn ingests_text_app_pics_and_links_parent_dlc() {
        let store = LudashiLibraryStore::default();
        let text = br#""appinfo" {
            "common" { "name" "DLC" "type" "DLC" "parent" "480" }
            "extended" { "listofdlc" "481,482" }
            "depots" { "branches" { "public" { "buildid" "99" } } }
        }"#;
        store.ingest_app_pics_response(&CMsgClientPICSProductInfoResponse {
            apps: vec![PicsAppInfoResp {
                appid: 481,
                buffer: text.to_vec(),
                ..Default::default()
            }],
            ..Default::default()
        });
        let app = store.find_app(481).unwrap();
        assert_eq!(app.name, "DLC");
        assert_eq!(app.parent_app_id, 480);
        assert_eq!(app.build_id, 99);
        assert!(store.find_app(480).unwrap().dlc_app_ids.contains(&481));
    }

    #[test]
    fn marks_unknown_packages_as_fetched() {
        let store = LudashiLibraryStore::default();
        store.ingest_license_list(&CMsgClientLicenseList {
            eresult: 1,
            licenses: vec![License {
                package_id: 100,
                ..Default::default()
            }],
        });
        store.ingest_package_pics_response(&CMsgClientPICSProductInfoResponse {
            packages: vec![PicsPackageInfoResp {
                packageid: 101,
                change_number: 1,
                ..Default::default()
            }],
            unknown_packageids: vec![100],
            ..Default::default()
        });
        assert!(
            store
                .packages()
                .into_iter()
                .find(|p| p.package_id == 100)
                .unwrap()
                .pics_fetched
        );
    }

    fn cached_library() -> String {
        let store = LudashiLibraryStore::default();
        store.packages.lock().unwrap().insert(100, OwnedPackage {
            package_id: 100, change_number: 7, access_token: u64::MAX,
            pics_fetched: true, app_ids: vec![480], ..Default::default()
        });
        store.apps.lock().unwrap().insert(480, OwnedApp {
            app_id: 480, name: "Cached game".into(), pics_fetched: true,
            access_token: u64::MAX, source_package_ids: vec![100, 200], ..Default::default()
        });
        store.cache_json()
    }

    #[test]
    fn restores_metadata_only_for_matching_live_licenses() {
        let store = LudashiLibraryStore::default();
        store.ingest_license_list(&CMsgClientLicenseList {
            eresult: 1,
            licenses: vec![License { package_id: 100, change_number: 7, access_token: 55, ..Default::default() }],
        });
        store.restore_cache_json(&cached_library()).unwrap();
        assert!(store.get_pending_package_pics_request(100).is_empty());
        assert!(store.get_pending_app_pics_request(100).is_empty());
        assert_eq!(store.packages()[0].access_token, 55);
        let app = store.find_app(480).unwrap();
        assert_eq!(app.name, "Cached game");
        assert_eq!(app.access_token, u64::MAX);
        assert_eq!(app.source_package_ids, vec![100]);
    }

    #[test]
    fn cache_does_not_grant_ownership_or_reuse_changed_packages() {
        let store = LudashiLibraryStore::default();
        store.restore_cache_json(&cached_library()).unwrap();
        assert_eq!(store.package_count(), 0);
        assert_eq!(store.owned_app_count(), 0);
        store.ingest_license_list(&CMsgClientLicenseList {
            eresult: 1,
            licenses: vec![License { package_id: 100, change_number: 8, ..Default::default() }],
        });
        store.restore_cache_json(&cached_library()).unwrap();
        assert_eq!(store.get_pending_package_pics_request(100).len(), 1);
        assert_eq!(store.owned_app_count(), 0);
        assert!(store.restore_cache_json("truncated {").is_err());
    }

    #[test]
    fn refreshed_license_list_removes_revoked_games() {
        let store = LudashiLibraryStore::default();
        store.ingest_license_list(&CMsgClientLicenseList {
            eresult: 1,
            licenses: vec![License { package_id: 100, change_number: 7, ..Default::default() }],
        });
        store.restore_cache_json(&cached_library()).unwrap();
        assert_eq!(store.owned_app_count(), 1);
        store.ingest_license_list(&CMsgClientLicenseList { eresult: 1, licenses: vec![] });
        assert_eq!(store.owned_app_count(), 0);
        assert_eq!(store.package_count(), 0);
    }
}
