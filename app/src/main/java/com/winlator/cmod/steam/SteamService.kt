package com.winlator.cmod.steam

import android.app.*
import android.content.*
import android.os.*
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.steam.ludashisteam.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Owns the native session so navigation and screen rotation do not interrupt a download. */
class SteamService : Service() {
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        if (!SteamSettings.enabled(this)) { stopSelf(); return }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("steam", "Steam", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, SteamActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(310, Notification.Builder(this, "steam").setSmallIcon(R.drawable.icon_shortcut)
            .setContentTitle("Steam").setContentText("Steam session active").setContentIntent(open).build())
        SteamSession.open(applicationContext)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onDestroy() { SteamSession.close(); super.onDestroy() }
}

object SteamSession {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile var session: LudashiSteamSession? = null
        private set
    @Volatile var status = "Connecting…"
        private set
    @Volatile var qrUrl = ""
        private set
    @Volatile var library = "{}"
        private set
    @Volatile var downloading = false
        private set
    val guard = SteamGuardPrompt()
    @Volatile var connected = false
        private set
    @Volatile var signedIn = false
        private set
    @Volatile var signingIn = false
        private set
    @Volatile var downloadId = 0
        private set
    @Volatile var downloadName = ""
        private set
    @Volatile var downloadDone = 0L
        private set
    @Volatile var downloadTotal = 0L
        private set
    @Volatile private var authAttempt = 0
    private var ca = ""
    private var app: Context? = null
    @Volatile var gameActive = false
        private set
    private var generation = 0
    @Volatile private var closing = true
    private val recovery = SteamSessionRecovery()
    @Volatile var savedAccount = false
        private set
    @Volatile var restoring = false
        private set
    @Volatile var authError = ""
        private set
    @Volatile private var recoveryBlocked = false
    private val librarySync = SteamLibrarySync()
    private val polling = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var licensesReceived = false
    @Volatile var libraryLoading = true
        private set
    @Volatile var libraryError = ""
        private set
    private val poll = object : Runnable {
        override fun run() {
            if (closing || gameActive || app?.let { !SteamSettings.enabled(it) } == true) return
            if (polling.compareAndSet(false, true)) work {
                try {
                    var s = session
                    if (s != null && savedAccount && !recoveryBlocked) {
                        val now = SystemClock.elapsedRealtime()
                        recovery.observe(if (signedIn) 3 else if (connected) 2 else 0, now)
                        if (!signedIn && recovery.shouldRetry(now)) {
                            replaceSession(app ?: return@work)
                            s = session
                        } else if (!signedIn && recovery.exhausted(now)) {
                            restoring = false
                            status = "Steam session could not be restored. Reconnect to try again."
                        }
                    }
                    if (s != null && signedIn) {
                        val active = s
                        librarySync.step(object : SteamLibrarySync.Gateway {
                            override fun snapshot() = active.getLibrarySnapshotJson()
                            override fun packages(ids: List<Int>, tokens: List<Long>): Boolean {
                                if (!downloading) status = librarySync.message
                                return active.getPicsPackageInfo(ids, tokens)?.let { org.json.JSONArray(it).length() > 0 } ?: false
                            }
                            override fun apps(ids: List<Int>, tokens: List<Long>): Boolean {
                                if (!downloading) status = librarySync.message
                                return active.getPicsAppProductInfo(ids, tokens)?.let { org.json.JSONArray(it).length() > 0 } ?: false
                            }
                            override fun tokens(ids: List<Int>): Map<Int, Long> {
                                val json = active.getPicsAccessTokens(ids, emptyList()) ?: error("Steam access-token request timed out. Try again.")
                                val tokens = JSONObject(json).optJSONObject("appTokens") ?: return emptyMap()
                                return ids.mapNotNull { id -> tokens.optString(id.toString()).toULongOrNull()?.toLong()?.let { id to it } }.toMap()
                            }
                        }, licensesReceived, SystemClock.elapsedRealtime())
                        libraryLoading = librarySync.loading; libraryError = librarySync.error
                        if (!downloading) status = librarySync.message
                        val snapshot = active.getLibrarySnapshotJson()
                        if (snapshot != library && (SteamLibrary.items(app ?: return@work, snapshot).isNotEmpty() || (!libraryLoading && libraryError.isEmpty()))) {
                            library = snapshot
                            app?.let { File(it.filesDir, "steam-library-cache.json").writeText(snapshot) }
                        }
                    }
                } finally { polling.set(false) }
            }
            main.postDelayed(this, 3000)
        }
    }
    fun message(text: String) { status = text }
    fun work(action: () -> Unit) { worker.execute { try { action() } catch (e: Exception) { status = e.message ?: "Steam operation failed" } catch (e: LinkageError) { status = "Steam native library unavailable" } } }
    fun open(context: Context) {
        if (!SteamSettings.enabled(context) || gameActive) return
        app = context
        closing = false
        val current = ++generation
        work {
            if (session != null || current != generation || closing) return@work
            recovery.reset(); recoveryBlocked = false
            replaceSession(context)
            main.removeCallbacks(poll); main.post(poll)
        }
    }
    // Always destroy the previous transport before another auth attempt. Keep the library cache.
    private fun replaceSession(context: Context, restoreSaved: Boolean = true) {
        val previous = session
        session = null
        ++authAttempt; guard.cancel(); signingIn = false; connected = false; signedIn = false
        previous?.let { it.cancelDownload(); it.cancelLogin(); it.disconnect(); it.close() }
        if (downloading) status = "Pausing download after session loss…"
        librarySync.reset(); licensesReceived = false; libraryLoading = true; libraryError = ""
        val saved = if (restoreSaved) SteamCredentials.load(context) else null
        savedAccount = saved != null
        restoring = savedAccount
        if (saved != null && library == "{}") library = runCatching { File(context.filesDir, "steam-library-cache.json").readText() }.getOrDefault("{}")
        ca = CaBundleExtractor.ensureBundle(context)
        check(ca.isNotEmpty()) { "No trusted CA bundle available" }
        val s = LudashiSteamSession()
        session = s
        if (saved != null) recovery.began(SystemClock.elapsedRealtime())
        s.setAutoPersonaOnline(false)
        s.setCaBundlePath(ca)
        s.setStateObserver(object : LudashiSteamStateObserver {
            override fun onStateChanged(state: Int) {
                if (session !== s || closing) return
                connected = state >= 2; signedIn = state == 3
                if (signedIn) { signingIn = false; restoring = false; authError = "" }
                else if (savedAccount && !recoveryBlocked) restoring = true
                status = if (!signedIn && savedAccount) "Restoring your Steam session…" else
                    arrayOf("Disconnected", "Connecting…", "Connected — sign in to Steam", "Signed in — loading library…").getOrElse(state) { "Steam" }
            }
            override fun onClientMessage(emsg: Int, eresult: Int, body: ByteArray) {
                if (session !== s || closing) return
                if (emsg == 780) licensesReceived = true
                if (emsg == 751 || emsg == 757 || emsg == 5500) {
                    val code = SteamLogonResult.code(body, eresult)
                    if (code != null && code != 1) {
                        status = "Steam ended/refused the session (code $code)."
                        if (SteamLogonResult.requiresNewLogin(code)) {
                            recoveryBlocked = true; restoring = false; savedAccount = false; signedIn = false
                            authError = "Steam rejected the saved login (code $code). Sign in again."
                            work { if (session === s) { SteamCredentials.clear(context); replaceSession(context, false) } }
                        } else if (code == 84) {
                            recoveryBlocked = true; restoring = false
                            status = "Steam is limiting sign-in attempts. Wait before reconnecting."
                        }
                    }
                }
            }
        })
        val url = LudashiSteamSession.pickCmUrl(ca)
        check(url.startsWith("wss://")) { "Could not locate a Steam server" }
        check(s.connect(url)) { "Steam connection failed" }
        if (saved != null) check(s.logonWithRefreshToken(saved.getString("token"), saved.getString("account"), saved.getLong("id"))) { "Saved Steam login could not start. Reconnect to retry." }
    }
    fun login(username: String, password: String) {
        if (gameActive || app?.let { !SteamSettings.enabled(it) } == true) return
        if (signingIn || username.isBlank() || password.isEmpty()) return
        signingIn = true; authError = ""; recoveryBlocked = false
        work {
            try {
                val context = app ?: error("Steam is starting")
                val s = session ?: error("Steam is starting")
                check(!downloading && s.state() == 2) { "Wait for the Steam connection before signing in" }
                guard.cancel(); s.cancelLogin()
                val attempt = ++authAttempt
                status = "Signing in…"
                s.startLoginWithCredentials(username.trim(), password, true, object : LudashiAuthenticator {
                    override fun acceptDeviceConfirmation(): java.util.concurrent.CompletableFuture<Boolean> {
                        if (session !== s || attempt != authAttempt) return java.util.concurrent.CompletableFuture.completedFuture(false)
                        status = "Approve this sign-in in your Steam mobile app"
                        return java.util.concurrent.CompletableFuture.completedFuture(true)
                    }
                    override fun getDeviceCode(previousCodeWasIncorrect: Boolean) = if (session !== s || attempt != authAttempt) java.util.concurrent.CompletableFuture.completedFuture("") else guard.request(
                        if (previousCodeWasIncorrect) "Incorrect code. Enter a new Steam Guard code" else "Enter your Steam Guard code")
                    override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean) = if (session !== s || attempt != authAttempt) java.util.concurrent.CompletableFuture.completedFuture("") else guard.request(
                        if (previousCodeWasIncorrect) "Incorrect code. Enter the new email code" else "Enter the Steam Guard code sent to ${email ?: "your email"}")
                }, LudashiAuthCallback { result ->
                    if (session === s && attempt == authAttempt) {
                        if (result.success && result.refreshToken.isNotBlank()) work {
                            if (session !== s || attempt != authAttempt) return@work
                            try {
                                check(result.steamId > 0 && result.accountName.isNotBlank()) { "Steam returned incomplete account details" }
                                SteamCredentials.save(context, result.accountName, result.refreshToken, result.steamId)
                                guard.cancel(); savedAccount = true; restoring = true
                                recovery.reset(); recovery.began(SystemClock.elapsedRealtime())
                                check(s.logonWithRefreshToken(result.refreshToken, result.accountName, result.steamId)) { "Steam logon failed" }
                            } finally { signingIn = false }
                        } else if (!result.hadRemoteInteraction) {
                            signingIn = false; guard.cancel()
                            authError = result.errorMessage.ifBlank { "Steam sign-in failed" }
                            status = authError
                            // Auth jobs that timed out must not be retried on the same CM transport.
                            work { if (session === s) replaceSession(context, false) }
                        }
                    }
                })
            } catch (e: Exception) { signingIn = false; throw e }
        }
    }
    fun retryLibrary() = work {
        check(!gameActive && app?.let { SteamSettings.enabled(it) } == true) { "Steam is disabled or paused for a running game" }
        librarySync.reset(); libraryLoading = true; libraryError = ""
        status = "Reloading Steam library…"
    }
    fun reconnect() = work {
        check(!gameActive) { "Exit the running game before reconnecting the app's Steam session" }
        val context = app ?: return@work
        check(SteamSettings.enabled(context)) { "Steam is disabled" }
        recovery.reset(); recoveryBlocked = false
        replaceSession(context)
    }
    fun cancelLogin() {
        ++authAttempt; guard.cancel(); signingIn = false
        work { session?.cancelLogin(); status = "Sign-in cancelled" }
    }
    fun info(id: Int): JSONObject {
        val raw = session?.getPicsAppInfo(id) ?: error("No Steam metadata for $id")
        return JSONObject(raw).getJSONObject("appinfo")
    }
    fun install(id: Int) = work {
        check(app?.let { SteamSettings.enabled(it) } == true && !gameActive) { "Steam is disabled or paused for a game" }
        check(!downloading) { "A Steam download is already active" }
        val context = app ?: error("Steam is starting")
        val s = session ?: error("Steam is starting")
        check(s.state() == 3) { "Sign in first" }
        val info = info(id)
        val name = SteamCatalog.installName(info)
        val depots = SteamCatalog.depots(info)
        val dir = File(context.filesDir, "steam-library/common/$name").apply { mkdirs() }
        SteamGoldberg.restore(dir)
        downloading = true
        downloadId = id; downloadName = name; downloadDone = 0; downloadTotal = 0
        status = "Downloading $name…"
        try {
            s.downloadApp(id, depots.map { it.id }.toIntArray(), depots.map { it.manifest }.toLongArray(),
                "public", dir.absolutePath, false, ca, 4, object : LudashiDownloadListener {
                    override fun onProgress(depotId: Int, depotDone: Long, depotTotal: Long, depotsDone: Int, depotsTotal: Int, verifying: Boolean) {
                        if (session !== s || !signedIn) return
                        downloadDone = depotDone; downloadTotal = depotTotal
                        status = "$name: depot ${depotsDone + 1}/$depotsTotal — ${if (depotTotal > 0) depotDone * 100 / depotTotal else 0}%" + if (verifying) " (verifying)" else ""
                    }
                    override fun onComplete(success: Boolean, error: String, bytesWritten: Long, depotsCompleted: Int, depotsSkipped: Int) {
                        work {
                            if (success) {
                                dir.walkTopDown().filter { it.isFile && it.name.endsWith(".ludashi-original") }.forEach { it.delete() }
                                File(context.filesDir, "steam-library/$id.json").writeText(info.toString())
                            }
                            downloading = false
                            status = if (success) "$name installed" else "Download failed: $error"
                        }
                    }
                })
        } catch (e: Exception) { downloading = false; throw e }
    }
    fun cancel() = work { session?.cancelDownload() }
    @JvmStatic fun beginGame(context: Context) {
        check(SteamSettings.enabled(context)) { "Steam is disabled in Steam / Stores settings" }
        check(!downloading && !gameActive) { "Pause the download and close any running Steam game first" }
        gameActive = true
        val ready = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        work {
            try {
                if (!gameActive) return@work
                recoveryBlocked = true; restoring = false; signingIn = false
                main.removeCallbacks(poll)
                val old = session; session = null
                old?.let { it.setStateObserver(null); it.cancelLogin(); it.logOffAndDisconnect(); it.close() }
                connected = false; signedIn = false
                status = "Steam app session paused while the game runs"
            } catch (e: Exception) { failure.set(e) }
            finally { ready.countDown() }
        }
        if (!ready.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
            gameActive = false
            error("Could not pause the Steam session in time. Retry after the current request finishes.")
        }
        failure.get()?.let { endGame(context); error("Could not hand off the Steam session: ${it.message}") }
    }
    @JvmStatic fun endGame(context: Context) {
        if (!gameActive) return
        gameActive = false
        if (SteamSettings.enabled(context) && SteamCredentials.load(context) != null) {
            app = context.applicationContext; closing = false
            work {
                recovery.reset(); recoveryBlocked = false
                replaceSession(context.applicationContext)
                main.removeCallbacks(poll); main.post(poll)
            }
        }
    }
    fun close() {
        closing = true; savedAccount = false; restoring = false; recoveryBlocked = true
        ++generation; ++authAttempt; guard.cancel(); signingIn = false; signedIn = false; connected = false
        main.removeCallbacks(poll)
        work {
            session?.let { it.cancelDownload(); it.cancelLogin(); it.logOffAndDisconnect(); it.close() }
            session = null
            librarySync.reset(); licensesReceived = false; libraryLoading = true; libraryError = ""
            library = "{}"; qrUrl = ""; downloading = false; status = "Disconnected"
        }
    }
}
