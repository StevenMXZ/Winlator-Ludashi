package com.winlator.cmod.stores

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

internal object AmazonConnector {
    private const val DEVICE = "A2UMVHOX7UP4V7"
    private const val AGENT = "com.amazon.agslauncher.win/3.0.9202.1"
    private const val DISTRIBUTION = "https://gaming.amazon.com/api/distribution/v2/public"
    data class Login(val serial: String, val verifier: String) {
        val clientId get() = "$serial#$DEVICE".toByteArray().joinToString("") { "%02x".format(it) }
        val challenge get() = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        fun url(): String = Uri.parse("https://www.amazon.com/ap/signin").buildUpon().apply {
            mapOf("openid.ns" to "http://specs.openid.net/auth/2.0", "openid.claimed_id" to "http://specs.openid.net/auth/2.0/identifier_select",
                "openid.identity" to "http://specs.openid.net/auth/2.0/identifier_select", "openid.mode" to "checkid_setup",
                "openid.oa2.scope" to "device_auth_access", "openid.ns.oa2" to "http://www.amazon.com/ap/ext/oauth/2",
                "openid.oa2.response_type" to "code", "openid.oa2.code_challenge_method" to "S256", "openid.oa2.client_id" to "device:$clientId",
                "language" to "en_US", "marketPlaceId" to "ATVPDKIKX0DER", "openid.return_to" to "https://www.amazon.com",
                "openid.pape.max_auth_age" to "0", "openid.ns.pape" to "http://specs.openid.net/extensions/pape/1.0",
                "openid.assoc_handle" to "amzn_sonic_games_launcher", "pageId" to "amzn_sonic_games_launcher", "openid.oa2.code_challenge" to challenge
            ).forEach { (name, value) -> appendQueryParameter(name, value) }
        }.build().toString()
    }
    fun login() = Login(UUID.randomUUID().toString().replace("-", "").uppercase(), Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
    fun callback(url: String): String? {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host !in listOf("www.amazon.com", "amazon.com") || uri.path.orEmpty() !in listOf("", "/")) return null
        return uri.getQueryParameter("openid.oa2.authorization_code")?.takeIf { it.isNotBlank() }
    }
    fun register(context: Context, login: Login, code: String) {
        val body = JSONObject().put("auth_data", JSONObject().put("authorization_code", code).put("client_domain", "DeviceLegacy")
            .put("client_id", login.clientId).put("code_algorithm", "SHA-256").put("code_verifier", login.verifier).put("use_global_authentication", false))
            .put("registration_data", JSONObject().put("app_name", "AGSLauncher for Windows").put("app_version", "1.0.0").put("device_model", "Windows")
                .put("device_name", JSONObject.NULL).put("device_serial", login.serial).put("device_type", DEVICE).put("domain", "Device").put("os_version", "10.0.19044.0"))
            .put("requested_extensions", JSONArray(listOf("customer_info", "device_info"))).put("requested_token_type", JSONArray(listOf("bearer", "mac_dms"))).put("user_context_map", JSONObject())
        val success = StoreNetwork.json(context, LudashiStore.AMAZON, "https://api.amazon.com/auth/register", body).getJSONObject("response").getJSONObject("success")
        val tokens = success.getJSONObject("tokens").getJSONObject("bearer")
        check(tokens.optString("access_token").isNotBlank() && tokens.optString("refresh_token").isNotBlank()) { "Amazon returned incomplete sign-in details" }
        tokens.put("serial", login.serial).put("client_id", login.clientId).put("account", login.serial)
            .put("expires", System.currentTimeMillis() + tokens.optLong("expires_in", 3600) * 1000)
        StoreVault.save(context, LudashiStore.AMAZON, tokens)
    }
    @Synchronized private fun token(context: Context): JSONObject {
        val account = StoreVault.load(context, LudashiStore.AMAZON) ?: error("Sign in to Amazon first")
        if (account.optLong("expires") > System.currentTimeMillis() + 60000) return account
        val renewed = StoreNetwork.json(context, LudashiStore.AMAZON, "https://api.amazon.com/auth/token", JSONObject()
            .put("source_token", account.getString("refresh_token")).put("source_token_type", "refresh_token")
            .put("requested_token_type", "access_token").put("app_name", "AGSLauncher for Windows").put("app_version", "1.0.0"), mapOf("x-amzn-identity-auth-domain" to "api.amazon.com"))
        require(renewed.optString("access_token").isNotBlank()) { "Amazon did not renew the session" }
        account.put("access_token", renewed.getString("access_token")).put("expires", System.currentTimeMillis() + renewed.optLong("expires_in", 3600) * 1000)
        StoreVault.save(context, LudashiStore.AMAZON, account)
        return account
    }
    private fun api(context: Context, endpoint: String, operation: String, body: JSONObject): JSONObject {
        val account = token(context)
        val target = if (operation == "GetEntitlements") "com.amazon.animusdistributionservice.entitlement.AnimusEntitlementsService.$operation"
            else "com.amazon.animusdistributionservice.external.AnimusDistributionService.$operation"
        return StoreNetwork.json(context, LudashiStore.AMAZON, endpoint, body.put("Operation", operation),
            mapOf("X-Amz-Target" to target, "x-amzn-token" to account.getString("access_token"), "User-Agent" to AGENT, "Content-Encoding" to "amz-1.0"))
    }
    fun library(context: Context): List<StoreGame> {
        val account = token(context)
        val hardware = storeHash(account.getString("serial")).uppercase()
        return pages { next -> api(context, "https://gaming.amazon.com/api/distribution/entitlements", "GetEntitlements", JSONObject()
            .put("clientId", "Sonic").put("syncPoint", JSONObject.NULL).put("nextToken", next ?: JSONObject.NULL).put("maxResults", 50)
            .put("productIdFilter", JSONObject.NULL).put("keyId", "d5dc8b8b-86c8-4fc4-ae93-18c0def5314d").put("hardwareHash", hardware)) }
    }
    fun pages(fetch: (String?) -> JSONObject): List<StoreGame> {
        val games = linkedMapOf<String, StoreGame>()
        val visited = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = fetch(cursor)
            val rows = page.getJSONArray("entitlements")
            for (i in 0 until rows.length()) {
                val entitlement = rows.getJSONObject(i)
                val product = entitlement.optJSONObject("product") ?: continue
                val id = product.optString("id"); val entitlementId = entitlement.optString("id")
                if (id.isBlank() || entitlementId.isBlank()) error("Amazon returned an incomplete entitlement")
                val detail = product.optJSONObject("productDetail")
                val image = detail?.optString("iconUrl").orEmpty().ifBlank { detail?.optJSONObject("details")?.optString("logoUrl").orEmpty() }
                games[id] = StoreGame(LudashiStore.AMAZON, id, product.getString("title"), image.replaceFirst("http://", "https://").takeIf { it.startsWith("https://") }.orEmpty(),
                    JSONObject().put("entitlement", entitlementId).put("product", product))
            }
            cursor = page.optString("nextToken").takeIf { it.isNotBlank() && it != "null" }
            if (cursor != null) check(visited.add(cursor) && visited.size <= 10000) { "Amazon library pagination repeated a page" }
        } while (cursor != null)
        return games.values.toList()
    }
    fun download(context: Context, game: StoreGame): String {
        val response = api(context, DISTRIBUTION, "GetGameDownload", JSONObject().put("entitlementId", game.metadata.getString("entitlement")))
        return response.getString("downloadUrl").also { require(it.startsWith("https://")) }
    }
    fun append(base: String, path: String): String {
        val root = base.substringBefore('?').trimEnd('/')
        val query = base.substringAfter('?', "")
        return "$root/$path" + if (query.isNotEmpty()) "?$query" else ""
    }
}
