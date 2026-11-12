package com.winlator.cmod.steam

import org.json.JSONObject

/** Advances one bounded PICS request at a time. Reading snapshots alone does not populate PICS. */
class SteamLibrarySync {
    interface Gateway {
        fun snapshot(): String
        fun packages(ids: List<Int>, tokens: List<Long>): Boolean
        fun apps(ids: List<Int>, tokens: List<Long>): Boolean
        fun tokens(ids: List<Int>): Map<Int, Long>
    }
    var loading = true
        private set
    var error = ""
        private set
    var progress = 0
        private set
    var message = "Loading Steam library… 0%"
        private set
    private var started: Long? = null
    private val packagesDone = mutableSetOf<Int>()
    private val appsDone = mutableSetOf<Int>()
    private var forceRefresh = false
    fun reset(forceRefresh: Boolean = false) {
        this.forceRefresh = forceRefresh
        loading = true; error = ""; progress = 0; message = "Loading Steam library… 0%"
        started = null; packagesDone.clear(); appsDone.clear()
    }
    fun step(gateway: Gateway, licensesReceived: Boolean, now: Long) {
        if (!loading) return
        val since = started ?: now.also { started = it }
        try {
            val snapshot = JSONObject(gateway.snapshot())
            val packages = snapshot.optJSONArray("packages")
            if (!licensesReceived) {
                check(now - since < 45_000) { "Steam did not return your licenses. Try loading the library again." }
                message = "Loading Steam library… $progress%"
                return
            }
            val pendingPackages = (0 until (packages?.length() ?: 0)).map { packages!!.getJSONObject(it) }
                .filter { it.getInt("id") !in packagesDone && (forceRefresh || !it.optBoolean("pics_fetched")) }.take(100)
            if (pendingPackages.isNotEmpty()) {
                val ids = pendingPackages.map { it.getInt("id") }
                message = "Loading Steam library… $progress%"
                check(gateway.packages(ids, pendingPackages.map { it.optString("access_token", "0").toULong().toLong() })) {
                    "Steam package metadata request failed. Try loading the library again."
                }
                packagesDone.addAll(ids)
                progress = maxOf(progress, packagesDone.size * 50 / packages!!.length())
                message = "Loading Steam library… $progress%"
                return
            }
            val owned = snapshot.optJSONArray("owned_apps")
            val pendingApps = (0 until (owned?.length() ?: 0)).map { owned!!.getJSONObject(it) }
                .filter { it.getInt("id") !in appsDone && (forceRefresh || !it.optBoolean("pics_fetched")) }.take(100)
            if (pendingApps.isNotEmpty()) {
                val ids = pendingApps.map { it.getInt("id") }
                message = "Loading Steam library… $progress%"
                val tokens = gateway.tokens(ids)
                check(gateway.apps(ids, pendingApps.map { tokens[it.getInt("id")] ?: it.optString("access_token", "0").toULong().toLong() })) {
                    "Steam game metadata request failed. Try loading the library again."
                }
                appsDone.addAll(ids)
                progress = maxOf(progress, (50 + appsDone.size * 50 / owned!!.length()).coerceAtMost(99))
                message = "Loading Steam library… $progress%"
                return
            }
            loading = false; progress = 100
            message = "Steam library is up to date"
        } catch (e: Exception) {
            loading = false; error = e.message ?: "Steam library could not be loaded"
            message = error
        }
    }
}
