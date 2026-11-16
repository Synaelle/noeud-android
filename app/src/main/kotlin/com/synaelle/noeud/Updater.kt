// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONException
import org.json.JSONObject

/**
 * Looks for a newer release on GitHub when the app starts, and installs it when asked, only if it
 * is signed with the same key as the installed app.
 */
object Updater {
    private const val TIMEOUT_MS = 15_000

    class Release(val version: String, val apk: String)

    private var checked = false
    private val main = Handler(Looper.getMainLooper())

    /** "0.10.2" as numbers, so that it sorts after "0.9.0". */
    fun parts(version: String): List<Int>? =
        version.removePrefix("v").split(".").map { it.toIntOrNull() ?: return null }.takeIf { it.size == 3 }

    fun newer(candidate: String, installed: String): Boolean {
        val a = parts(candidate) ?: return false
        val b = parts(installed) ?: return false
        for (i in 0 until 3) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        setRequestProperty("User-Agent", "Noeud")
    }

    /** What a look for a new version found. */
    sealed interface Check {
        data class Found(val release: Release) : Check
        data object Current : Check
        data object Failed : Check
    }

    private fun fetch(): Check = try {
        val release = latest()
        if (release != null && newer(release.version, BuildConfig.VERSION_NAME)) Check.Found(release) else Check.Current
    } catch (_: IOException) {
        Check.Failed
    } catch (_: JSONException) {
        Check.Failed
    }

    /** Once per start of the app, unless turned off. Offline, or no newer release: nothing is shown. */
    fun checkOnce(activity: Activity, found: (Release) -> Unit) {
        if (checked) return
        checked = true
        if (!Noeud.read(activity).checkUpdates) return
        Thread {
            val result = fetch()
            if (result is Check.Found) main.post { found(result.release) }
        }.start()
    }

    /** Looks now, from the settings, and says what it found. */
    fun checkNow(done: (Check) -> Unit) {
        Thread {
            val result = fetch()
            main.post { done(result) }
        }.start()
    }

    /** The latest release, or null when it has no APK for Noeud. */
    private fun latest(): Release? {
        val connection = open(BuildConfig.RELEASES)
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode != 200) throw IOException("GitHub answered ${connection.responseCode}")
            val body = JSONObject(connection.inputStream.use { it.readBytes().decodeToString() })
            val version = body.getString("tag_name").removePrefix("v")
            val assets = body.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name") == "noeud-$version.apk") {
                    return Release(version, asset.getString("browser_download_url"))
                }
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads the release, checks it, and hands it to Android, which asks to confirm. */
    fun install(activity: Activity, release: Release, failed: (String) -> Unit) {
        val packages = activity.packageManager
        if (!packages.canRequestPackageInstalls()) {
            // asked once: Android lets the person allow installs from Noeud, then Install again
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
            return
        }
        val app = activity.applicationContext
        Toast.makeText(app, "Downloading Noeud ${release.version}…", Toast.LENGTH_SHORT).show()
        Thread {
            val problem = try {
                val file = File(app.cacheDir, "update.apk")
                download(release.apk, file)
                refusal(app, file) ?: run {
                    commit(app, file)
                    null
                }
            } catch (_: IOException) {
                "The update could not be downloaded. Try again later."
            }
            if (problem != null) main.post { failed(problem) }
        }.start()
    }

    private fun download(url: String, file: File) {
        val connection = open(url)
        try {
            if (connection.responseCode != 200) throw IOException("the download answered ${connection.responseCode}")
            connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        } finally {
            connection.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        } ?: return emptySet()
        val sha256 = MessageDigest.getInstance("SHA-256")
        return certificates.map { cert -> sha256.digest(cert.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    @Suppress("DEPRECATION")
    private val signatureFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }

    /** Why this file must not be installed, or null when it is the same app, newer, same key. */
    fun refusal(context: Context, file: File): String? {
        val packages = context.packageManager
        val downloaded = packages.getPackageArchiveInfo(file.path, signatureFlag)
            ?: return "The download is not an app Noeud can install."
        val installed = packages.getPackageInfo(context.packageName, signatureFlag)
        if (downloaded.packageName != context.packageName) return "The download is not Noeud."
        @Suppress("DEPRECATION")
        val newerCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            downloaded.longVersionCode > installed.longVersionCode
        } else {
            downloaded.versionCode > installed.versionCode
        }
        if (!newerCode) return "The download is not newer than this Noeud."
        val mine = signers(installed)
        if (mine.isEmpty() || signers(downloaded) != mine) {
            return "The download is not signed like this Noeud, so it was not installed."
        }
        return null
    }

    private fun commit(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("noeud.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, UpdateReceiver::class.java),
                // Android writes the outcome into this intent
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(status.intentSender)
        }
    }
}

/** What Android says about the install: asks the person to confirm, or why it failed. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Toast.makeText(context, "The update was not installed.", Toast.LENGTH_LONG).show()
        }
    }
}
