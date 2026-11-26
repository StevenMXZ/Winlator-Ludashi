package com.winlator.cmod.core

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/** The development channel never needs an embedded GitHub credential. */
internal object ActionsUpdates {
    const val REPO = "StevenMXZ/Winlator-Ludashi"
    const val BRANCH = "5.0"
    const val TEST_BRANCH = "feature/actions-updater"
    private const val REPO_ID = 1042203784L
    private const val METADATA = "com.winlator.cmod.ACTIONS_BUILD"
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    private const val RESERVE = 64L * 1024 * 1024
    private val shaPattern = Regex("[0-9a-f]{40}")
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).followSslRedirects(false).build()

    data class Build(val branch: String, val sha: String, val number: Long, val attempt: Long) {
        fun newerThan(other: Build): Boolean =
            number > other.number || (number == other.number && attempt > other.attempt)
        companion object {
            fun parse(value: String?): Build? {
                val parts = value?.split(':') ?: return null
                if (parts.size != 5 || parts[0] != "v1" ||
                    parts[1] !in listOf(BRANCH, TEST_BRANCH) || !shaPattern.matches(parts[2])) return null
                val number = parts[3].toLongOrNull()?.takeIf { it > 0 } ?: return null
                val attempt = parts[4].toLongOrNull()?.takeIf { it > 0 } ?: return null
                return Build(parts[1], parts[2], number, attempt)
            }
        }
    }

    data class Delta(val id: Long, val size: Long, val digest: String) {
        val url: String get() = "https://nightly.link/" + REPO + "/actions/artifacts/" + id + ".zip"
    }

    data class Artifact(val build: Build, val id: Long, val suiteId: Long, val size: Long, val digest: String, val title: String = "", val delta: Delta? = null) {
        val downloadSize get() = delta?.size ?: size
        val url: String get() = "https://nightly.link/" + REPO + "/actions/artifacts/" + id + ".zip"
    }

    fun installedBuild(info: PackageInfo): Build? =
        Build.parse(info.applicationInfo?.metaData?.getString(METADATA))

    @Suppress("DEPRECATION")
    fun installedBuild(activity: Context): Build? =
        installedBuild(activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_META_DATA))

    // Only APKs built on the explicit test branch follow test artifacts. Production follows 5.0.
    fun sourceBranch(current: Build?): String = if (current?.branch == TEST_BRANCH) TEST_BRANCH else BRANCH

    internal fun parseRun(run: JSONObject, branch: String): Build? {
        if (run.optString("head_branch") != branch ||
            run.optString("path") != ".github/workflows/build.yml" ||
            run.optString("status") != "completed" || run.optString("conclusion") != "success" ||
            run.optString("event") !in listOf("push", "workflow_dispatch") ||
            run.optJSONObject("repository")?.optLong("id") != REPO_ID ||
            run.optJSONObject("head_repository")?.optLong("id") != REPO_ID) return null
        val sha = run.optString("head_sha")
        val number = run.optLong("run_number")
        val attempt = run.optLong("run_attempt")
        if (!shaPattern.matches(sha) || number <= 0 || attempt <= 0 ||
            run.optLong("id") <= 0 || run.optLong("check_suite_id") <= 0) return null
        return Build(branch, sha, number, attempt)
    }

    internal fun parseArtifact(entry: JSONObject, run: JSONObject, build: Build): Artifact? {
        val expectedName = "Winlator-Ludashi-5.0-" + build.number + "-r" + build.attempt
        val origin = entry.optJSONObject("workflow_run") ?: return null
        if (entry.optString("name") != expectedName || entry.optBoolean("expired", true) ||
            origin.optLong("id") != run.optLong("id") ||
            origin.optLong("repository_id") != REPO_ID || origin.optLong("head_repository_id") != REPO_ID ||
            origin.optString("head_branch") != build.branch || origin.optString("head_sha") != build.sha) return null
        val digest = entry.optString("digest").removePrefix("sha256:")
        val size = entry.optLong("size_in_bytes")
        val id = entry.optLong("id")
        if (!entry.optString("digest").startsWith("sha256:") || !digestPattern.matches(digest) ||
            size !in 1..MAX_BYTES || id <= 0) return null
        return Artifact(build, id, run.getLong("check_suite_id"), size, digest,
            commitTitle(run.optJSONObject("head_commit")?.optString("message")))
    }

    internal fun commitTitle(message: String?): String =
        message.orEmpty().lineSequence().firstOrNull()?.trim()?.take(240).orEmpty()

    private fun json(path: String): JSONObject {
        val request = Request.Builder().url("https://api.github.com/repos/" + REPO + "/" + path)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Winlator-Ludashi-Updater").build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GitHub update check failed (HTTP " + response.code + ")" }
            JSONObject(response.body?.string() ?: error("Empty GitHub response"))
        }
    }

    fun latest(current: Build?, installedHash: String? = null): Artifact? {
        val branch = sourceBranch(current)
        val encodedBranch = java.net.URLEncoder.encode(branch, "UTF-8")
        val runs = json("actions/workflows/build.yml/runs?branch=" + encodedBranch +
            "&status=success&per_page=10").getJSONArray("workflow_runs")
        val candidates = (0 until runs.length()).mapNotNull { i ->
            val run = runs.getJSONObject(i)
            parseRun(run, branch)?.let { run to it }
        }.sortedWith(compareByDescending<Pair<JSONObject, Build>> { it.second.number }
            .thenByDescending { it.second.attempt })
        for ((run, build) in candidates) {
            if (current != null && !build.newerThan(current)) continue
            val entries = json("actions/runs/" + run.getLong("id") + "/artifacts?per_page=100").getJSONArray("artifacts")
            val matches = (0 until entries.length()).mapNotNull { parseArtifact(entries.getJSONObject(it), run, build) }
            if (matches.size == 1) {
                val full = matches.single()
                val name = "Ludashi-Delta-" + build.number + "-r" + build.attempt + "-" + installedHash
                val delta = if (installedHash != null && digestPattern.matches(installedHash))
                    (0 until entries.length()).mapNotNull { i ->
                        val e = entries.getJSONObject(i)
                        if (e.optString("name") != name) null else {
                            // Reuse the full artifact's strict provenance validation.
                            val copy = JSONObject(e.toString()).put("name", "Winlator-Ludashi-5.0-" + build.number + "-r" + build.attempt)
                            parseArtifact(copy, run, build)?.takeIf { it.size < full.size }?.let { Delta(it.id, it.size, it.digest) }
                        }
                    }.singleOrNull() else null
                return full.copy(delta = delta)
            }
        }
        return null
    }

    private fun checkCancelled(cancelled: AtomicBoolean) = check(!cancelled.get()) { "Cancelled" }

    internal fun extract(zip: File, out: File, limit: Long, cancelled: AtomicBoolean) {
        out.delete()
        try {
            ZipFile(zip).use { archive ->
                check(archive.size() == 1) { "Unexpected update archive contents" }
                val entry = archive.getEntry("app-release.apk") ?: error("APK missing from artifact")
                check(!entry.isDirectory && entry.size in 1..limit) { "Invalid APK size" }
                archive.getInputStream(entry).use { input ->
                    out.outputStream().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var copied = 0L
                        while (true) {
                            checkCancelled(cancelled)
                            val n = input.read(buffer)
                            if (n == -1) break
                            copied += n
                            check(copied <= entry.size && copied <= limit) { "APK exceeds expected size" }
                            output.write(buffer, 0, n)
                        }
                        check(copied == entry.size) { "Incomplete APK" }
                    }
                }
            }
        } catch (e: Exception) { out.delete(); throw e }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> =
        info.signingInfo?.apkContentsSigners.orEmpty().map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b ->
                "%02x".format(b.toInt() and 255)
            }
        }.toSet()

    internal fun acceptableVersion(installedCode: Long, nextCode: Long, current: Build?, next: Build): Boolean =
        nextCode >= installedCode && if (current == null) nextCode > installedCode else
            next.newerThan(current) && next.branch == sourceBranch(current)

    @Suppress("DEPRECATION")
    private fun validateApk(activity: Context, apk: File, artifact: Artifact) {
        val pm = activity.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA
        val installed = pm.getPackageInfo(activity.packageName, flags)
        val next = pm.getPackageArchiveInfo(apk.path, flags) ?: error("Invalid APK")
        check(next.packageName == installed.packageName) { "This APK is for a different app variant" }
        val signers = signerDigests(installed)
        check(signers.isNotEmpty() && signerDigests(next) == signers) { "This APK uses a different signing key" }
        check(installedBuild(next) == artifact.build) { "APK does not match the selected Actions build" }
        check(acceptableVersion(installed.longVersionCode, next.longVersionCode, installedBuild(installed), artifact.build)) {
            "This build is not newer than the installed APK"
        }
    }

    fun download(activity: Context, artifact: Artifact, cancelled: AtomicBoolean,
                 progress: (Long, Long) -> Unit, status: (String) -> Unit): File {
        // Only private APK staging files are changed; running Wine sessions are unaffected.
        val directory = File(activity.cacheDir, "upstream-update")
        val parts = File(directory, "actions-parts")
        val key = artifact.id.toString() + "-" + artifact.digest
        val work = File(parts, key)
        val zip = File(work, "artifact.zip")
        val apk = File(directory, "actions-update.apk")
        var success = false
        var invalid = false
        try {
            check(work.isDirectory || work.mkdirs()) { "Could not create update directory" }
            parts.listFiles()?.filter { it.name != key }?.forEach { it.deleteRecursively() }
            apk.delete()
            artifact.delta?.let { delta ->
                val deltaWork = File(work, "delta-" + delta.id)
                val archive = File(deltaWork, "delta.zip")
                val patch = File(deltaWork, "update.ldu")
                try {
                    check(deltaWork.isDirectory || deltaWork.mkdirs())
                    status("Downloading update…")
                    ActionsArtifactTransfer.download(client, delta.url, delta.size, deltaWork, archive, cancelled, progress)
                    check(ApkPatcher.sha256(archive).equals(delta.digest, true))
                    ZipFile(archive).use { z ->
                        check(z.size() == 1)
                        val entry = z.getEntry("update.ldu") ?: error("Delta missing")
                        check(!entry.isDirectory && entry.size in 1..MAX_BYTES && entry.size < artifact.size)
                        z.getInputStream(entry).use { input ->
                            patch.outputStream().use { output ->
                                val buf = ByteArray(128 * 1024)
                                var copied = 0L
                                while (true) {
                                    checkCancelled(cancelled)
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    copied += n
                                    check(copied <= entry.size)
                                    output.write(buf, 0, n)
                                }
                                check(copied == entry.size)
                            }
                        }
                    }
                    status("Rebuilding APK…")
                    check(ContentApkDelta.apply(File(activity.applicationInfo.sourceDir), patch, apk, cancelled) { _, _ -> checkCancelled(cancelled) })
                    validateApk(activity, apk, artifact)
                    checkCancelled(cancelled)
                    success = true
                    return apk
                } catch (e: Exception) {
                    checkCancelled(cancelled)
                    android.util.Log.w("ActionsUpdates", "Incremental update failed; downloading full APK", e)
                    deltaWork.deleteRecursively()
                    apk.delete()
                    status("Downloading full APK…")
                }
            }
            val existing = work.listFiles()?.sumOf { it.length() } ?: 0L
            check(directory.usableSpace + existing >= artifact.size * 2 + RESERVE) { "Not enough free space for the update" }
            status("Downloading build #" + artifact.build.number + "…")
            ActionsArtifactTransfer.download(client, artifact.url, artifact.size, work, zip, cancelled, progress)
            checkCancelled(cancelled)
            status("Verifying archive…")
            if (!ApkPatcher.sha256(zip).equals(artifact.digest, true)) {
                invalid = true
                error("Artifact checksum does not match GitHub")
            }
            status("Extracting APK…")
            val unpacked = ZipFile(zip).use { it.getEntry("app-release.apk")?.size ?: -1L }
            check(unpacked in 1..MAX_BYTES && directory.usableSpace >= unpacked + RESERVE) { "Invalid APK size or insufficient storage" }
            extract(zip, apk, MAX_BYTES, cancelled)
            status("Verifying APK…")
            validateApk(activity, apk, artifact)
            checkCancelled(cancelled)
            success = true
            return apk
        } finally {
            if (success || invalid) work.deleteRecursively()
            if (!success) apk.delete()
        }
    }
}
