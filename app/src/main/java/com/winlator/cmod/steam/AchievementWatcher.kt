package com.winlator.cmod.steam

import android.content.Context
import android.os.FileObserver
import android.util.Log
import com.winlator.cmod.xenvironment.ImageFs
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Event-driven watcher for a running Steam game's Goldberg/GSE achievement store. When the emulator
 * writes an unlock into its `achievements.json`, this re-reads the file, diffs it against a snapshot
 * of what was already earned when the game launched, and for each NEWLY earned achievement fires the
 * in-game gold pill ([XServerDialogState.showAchievementToast]) and records it for later sync-back
 * ([SteamAchievements.queueUnlock]).
 *
 * The GSE store lives at, per the running container's Wine prefix:
 *   `<container>/.wine/drive_c/users/<user>/AppData/Roaming/GSE Saves/<appId>/achievements.json`
 * (gbe_fork's default, used by the Regular / Experimental / Cold Client Loader modes). We ALSO watch
 * the legacy Goldberg spelling `Goldberg SteamEmu Saves/<appId>/` as the cold-client userdata mirror,
 * so an older emulator build is covered too — whichever exists.
 *
 * Robustness: the achievements.json (and even its parent dirs) may not exist yet at launch — a game
 * only creates them once it first writes an unlock/config. So instead of watching a fixed path we arm
 * a FileObserver on the DEEPEST existing ancestor of each candidate file and RE-ARM one level deeper
 * as the missing directories appear, re-checking the file on every re-arm. All writes are debounced.
 *
 * Best-effort + fully guarded (logs to "BH_STEAM_ACHV"): nothing here may crash or block the game.
 */
class AchievementWatcher {

    companion object {
        internal fun affectedFiles(touched: File, targets: List<File>): List<File> = targets.filter {
            it.absolutePath == touched.absolutePath || it.absolutePath.startsWith(touched.absolutePath + File.separator)
        }
        private const val TAG = "BH_STEAM_ACHV"
        private const val ACHIEVEMENTS_FILE = "achievements.json"
        // Coalesce a burst of writes (the emulator rewrites the whole file) into one diff pass.
        private const val DEBOUNCE_MS = 400L
        // Mask covering both "file finished being written" and "file moved into place" (atomic writes
        // via a temp + rename land as MOVED_TO), plus the directory-lifecycle events we re-arm on. Not
        // a `const` — it's a bitwise-or of Java static fields, not a compile-time constant expression.
        private val MASK =
            FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE or
                FileObserver.DELETE or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF

        /**
         * The primary GSE (gbe_fork) `achievements.json` for [appId] under [containerRootDir]'s Wine
         * prefix:
         *   `<container>/.wine/drive_c/users/<user>/AppData/Roaming/GSE Saves/<appId>/achievements.json`
         *
         * The single source of truth for this path, shared by [start] (what to WATCH) and the
         * launch-time seeder ([SteamAchievements.seedGse], what to WRITE) so the watch target and
         * the seed target can never drift apart.
         */
        @JvmStatic
        fun gseAchievementsFile(containerRootDir: File, appId: Int): File {
            val roaming = File(
                containerRootDir,
                ".wine/drive_c/users/${ImageFs.USER}/AppData/Roaming",
            )
            return File(roaming, "GSE Saves/$appId/$ACHIEVEMENTS_FILE")
        }
    }

    private var appContext: Context? = null
    private var appId: Int = 0
    // apiNames already earned when the game launched — never re-toasted (grows as we surface new ones).
    private val earned = HashSet<String>()
    // Absolute achievements.json files we track (across both GSE spellings).
    private var candidates: List<File> = emptyList()
    // Live observers, one per re-arm level; kept referenced so they aren't GC'd (which stops them).
    private val observers = ArrayList<FileObserver>()
    // Directories currently watched — dedupes re-arms so repeated CREATE events don't pile up observers.
    private val watchedDirs = HashSet<String>()
    private var scheduler: ScheduledExecutorService? = null
    private var pending: ScheduledFuture<*>? = null
    @Volatile private var started = false

    /**
     * Begin watching the running game's achievement store. Call once container + appId are known, only
     * for genuine-Steam shortcuts. Idempotent-ish: a second call restarts fresh. Never throws.
     */
    @Synchronized
    fun start(ctx: Context, appId: Int, containerRootDir: File?) {
        try {
            stop()
            if (appId <= 0 || containerRootDir == null) return
            this.appContext = ctx.applicationContext
            this.appId = appId

            val roaming = File(
                containerRootDir,
                ".wine/drive_c/users/${ImageFs.USER}/AppData/Roaming",
            )
            candidates = listOf(
                // Primary path shared with the launch-time seeder (defined once in gseAchievementsFile).
                gseAchievementsFile(containerRootDir, appId),
                File(roaming, "Goldberg SteamEmu Saves/$appId/$ACHIEVEMENTS_FILE"),
            )

            // Snapshot what's already earned so relaunching a game doesn't re-toast owned achievements.
            earned.clear()
            for (f in candidates) earned.addAll(readEarned(f))

            scheduler = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "BH-AchvWatch").apply { isDaemon = true }
            }
            started = true
            for (f in candidates) armForFile(f)
            Log.i(TAG, "watching appId=$appId (${earned.size} already earned) under $roaming")
        } catch (t: Throwable) {
            Log.w(TAG, "start failed", t)
            try { stop() } catch (_: Throwable) {}
        }
    }

    /** Stop all observers and clear state. Safe to call repeatedly / when never started. */
    @Synchronized
    fun stop() {
        if (started) processChanges()
        started = false
        for (o in observers) { try { o.stopWatching() } catch (_: Throwable) {} }
        observers.clear()
        watchedDirs.clear()
        try { pending?.cancel(false) } catch (_: Throwable) {}
        pending = null
        try { scheduler?.shutdownNow() } catch (_: Throwable) {}
        scheduler = null
        earned.clear()
        candidates = emptyList()
        appContext = null
        appId = 0
    }

    // ── Watching ──────────────────────────────────────────────────────────────

    /**
     * Arm a FileObserver on the deepest EXISTING ancestor directory of [jsonFile]. When a child on the
     * path toward [jsonFile] appears (a missing dir gets created), re-arm one level deeper; when the
     * file itself is written/moved, schedule a diff. All events also nudge a diff so a file that
     * appears in the same tick as its dir isn't missed.
     */
    @Synchronized
    private fun armForFile(jsonFile: File) {
        if (!started || scheduler == null) return // stopped (or a late re-arm after stop)
        val watchDir = deepestExistingDir(jsonFile.parentFile) ?: return
        val watchPath = watchDir.absolutePath
        if (!watchedDirs.add(watchPath)) return // already watching this dir — nothing deeper appeared yet
        val obs = object : FileObserver(watchPath, MASK) {
            override fun onEvent(event: Int, path: String?) {
                try {
                    // A shared ancestor can cover both GSE spellings. Ignore unrelated
                    // stats/config writes without losing creation events for either store.
                    val touched = if (path == null) watchDir else File(watchDir, path)
                    val affected = affectedFiles(touched, candidates)
                    if (affected.isEmpty()) return
                    scheduleDiff()
                    affected.forEach { armForFile(it) }
                } catch (_: Throwable) { /* never let a callback crash the observer thread */ }
            }
        }
        try {
            obs.startWatching()
            observers.add(obs)
        } catch (t: Throwable) {
            watchedDirs.remove(watchPath)
            Log.w(TAG, "arm failed for $watchPath", t)
        }
    }

    /** Walk up from [dir] to the first directory that actually exists (null if none/above root). */
    private fun deepestExistingDir(dir: File?): File? {
        var d = dir
        var guard = 0
        while (d != null && guard++ < 64) {
            if (d.isDirectory) return d
            d = d.parentFile
        }
        return null
    }

    // ── Diff + surface ──────────────────────────────────────────────────────────

    private fun scheduleDiff() {
        val sch = scheduler ?: return
        try {
            pending?.cancel(false)
            pending = sch.schedule({ processChanges() }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) { /* scheduler shutting down */ }
    }

    @Synchronized
    private fun processChanges() {
        if (!started) return
        val ctx = appContext ?: return
        val id = appId
        try {
            // Union of every candidate store (a game uses one, but be forgiving).
            val now = HashSet<String>()
            for (f in candidates) now.addAll(readEarned(f))

            val fresh = now.filter { it !in earned }
            if (fresh.isEmpty()) return

            for (api in fresh) {
                earned.add(api) // record first so a duplicate write never double-toasts
                try {
                    // Shared resolve+toast so the Goldberg and SteamLudashi pill paths can't drift; the
                    // queueUnlock below is the Goldberg path's own record hook (unchanged).
                    val name = SteamAchievements.surfaceUnlockPill(ctx, id, api)
                    SteamAchievements.queueUnlock(ctx, id, api)
                    Log.i(TAG, "unlocked: $api ($name)")
                } catch (t: Throwable) {
                    Log.w(TAG, "surface failed for $api", t)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "processChanges failed", t)
        }
    }

    /**
     * Parse a GSE achievements.json — a JSON object `{ apiName -> { earned: bool, earned_time: long } }`
     * — and return the set of apiNames currently earned. Returns empty on any error / missing file.
     */
    private fun readEarned(file: File): Set<String> {
        if (!file.isFile || file.length() > 4 * 1024 * 1024) return emptySet()
        return try {
            val root = JSONObject(file.readText())
            val out = HashSet<String>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val api = keys.next()
                val entry = root.optJSONObject(api) ?: continue
                // gbe_fork writes a boolean `earned`; tolerate a 0/1 int form too.
                val isEarned = when {
                    entry.has("earned") -> entry.optBoolean("earned", false) || entry.optInt("earned", 0) != 0
                    else -> false
                }
                if (isEarned) out.add(api)
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "read failed: ${file.absolutePath}", t)
            emptySet()
        }
    }
}
