package com.friendlamp.app

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Keeps the app up to date from the GitHub releases of this project.
 *
 * Android only accepts an update signed with the same key as the installed app,
 * so nothing but a build from this repository can replace it. On Android 12 and
 * newer the update installs without asking, once the user has allowed FriendLamp
 * to install apps; older versions show a confirmation.
 */
object Updater {
    private const val RELEASES = "https://github.com/JustynaDorsz/friendlamp/releases/latest/download/"
    private const val MIN_GAP_MS = 20 * 60 * 1000L

    val io: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var running = false

    fun installedBuild(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    fun allowed(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Checks in the background, unless a check ran recently. */
    fun checkSoon(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val store = Store(app)
        if (!force && System.currentTimeMillis() - store.lastUpdateCheck < MIN_GAP_MS) return
        io.execute {
            try {
                check(app)
            } catch (e: Exception) {
                Store(app).updateStatus = "Couldn\u2019t check for updates: " + (e.message ?: e.javaClass.simpleName)
            }
            Lamp.onChange?.invoke()
        }
    }

    /** Looks for a newer build and installs it. Call from a background thread. */
    fun check(context: Context) {
        if (running) return
        running = true
        try {
            val store = Store(context)
            store.lastUpdateCheck = System.currentTimeMillis()
            val installed = installedBuild(context)
            val latest = text(RELEASES + "version.txt").trim().toLongOrNull()
            if (latest == null || latest <= installed) {
                store.updateStatus = "Build $installed, up to date."
                return
            }
            if (!allowed(context)) {
                store.updateStatus = "Build $latest is available (you have $installed)."
                return
            }
            store.updateStatus = "Downloading build $latest\u2026"
            Lamp.onChange?.invoke()
            val apk = File(context.cacheDir, "update.apk")
            download(RELEASES + "FriendLamp.apk", apk)
            store.updateStatus = "Installing build $latest\u2026"
            Lamp.onChange?.invoke()
            install(context, apk)
        } finally {
            running = false
        }
    }

    private fun connect(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        if (connection.responseCode !in 200..299) {
            val status = connection.responseCode
            connection.disconnect()
            throw java.io.IOException("download failed ($status)")
        }
        return connection
    }

    private fun text(url: String): String {
        val connection = connect(url)
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, target: File) {
        val connection = connect(url)
        try {
            connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } finally {
            connection.disconnect()
        }
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("friendlamp", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = Intent(context, UpdateReceiver::class.java)
            // The system fills in the result, so this PendingIntent must be mutable.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, 3, result, flags).intentSender)
        }
    }
}

/** Hears how an update went. On success the app is simply restarted by Android. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = Store(context)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_SUCCESS -> store.updateStatus = null
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants a confirmation this time.
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                store.updateStatus = "An update is ready. Tap the notification to install it."
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    var shownDirectly = false
                    if (Lamp.onChange != null) {
                        // The app is open: show Android's confirmation right away.
                        try {
                            context.startActivity(confirm)
                            shownDirectly = true
                        } catch (e: Exception) {
                            // Fall back to the notification.
                        }
                    }
                    if (!shownDirectly) Notifier.updateReady(context, confirm)
                }
            }
            else -> store.updateStatus = "Update failed (" + status + "): " +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "")
        }
        Lamp.onChange?.invoke()
    }
}

/** A background check every few hours, run by Android when the network is available. */
class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Updater.io.execute {
            try {
                Updater.check(applicationContext)
            } catch (e: Exception) {
                // Try again next time.
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val JOB_ID = 7

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(2 * 60 * 60 * 1000L)
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
        }
    }
}
