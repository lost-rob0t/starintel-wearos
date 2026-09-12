package actor.starintel.update

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import java.io.File
import java.io.FileInputStream

object PackageUpdateInstaller {
    const val EXTRA_NEXT_APK = "actor.starintel.update.NEXT_APK"
    const val EXTRA_NEXT_PACKAGE = "actor.starintel.update.NEXT_PACKAGE"

    fun canRequestInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        activity.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}"),
            )
        )
    }

    fun install(
        activity: Activity,
        apk: File,
        expectedPackage: String,
        nextApk: File? = null,
        nextPackage: String? = null,
    ) {
        require(apk.isFile && apk.canRead()) { "APK is not readable: $apk" }
        require(nextApk == null || !nextPackage.isNullOrBlank()) { "Next package name is required" }

        if (!canRequestInstalls(activity)) {
            openInstallPermission(activity)
            return
        }

        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(expectedPackage)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            FileInputStream(apk).use { input ->
                session.openWrite("update.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }

            val callback = Intent(activity, UpdateInstallActivity::class.java).apply {
                putExtra(EXTRA_NEXT_APK, nextApk?.absolutePath)
                putExtra(EXTRA_NEXT_PACKAGE, nextPackage)
            }
            val pending = PendingIntent.getActivity(
                activity,
                sessionId,
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }
}

class UpdateInstallActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(callback: Intent) {
        when (callback.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    callback.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    callback.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                }
                if (confirmation == null) {
                    Toast.makeText(this, "Installer confirmation was unavailable", Toast.LENGTH_LONG).show()
                    finish()
                    return
                }
                startActivity(confirmation)
                finish()
            }

            PackageInstaller.STATUS_SUCCESS -> {
                val nextPath = callback.getStringExtra(PackageUpdateInstaller.EXTRA_NEXT_APK)
                val nextPackage = callback.getStringExtra(PackageUpdateInstaller.EXTRA_NEXT_PACKAGE)
                if (!nextPath.isNullOrBlank() && !nextPackage.isNullOrBlank()) {
                    val next = File(nextPath)
                    if (next.isFile) {
                        PackageUpdateInstaller.install(this, next, nextPackage)
                        finish()
                        return
                    }
                }
                Toast.makeText(this, "StarIntel update installed", Toast.LENGTH_SHORT).show()
                finish()
            }

            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE,
            PackageInstaller.STATUS_FAILURE_TIMEOUT,
            -> {
                val detail = callback.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "Package installation failed"
                Toast.makeText(this, detail, Toast.LENGTH_LONG).show()
                finish()
            }

            else -> finish()
        }
    }
}
