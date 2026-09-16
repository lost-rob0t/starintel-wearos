package actor.starintel.update

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object PackageUpdateInstaller {
    fun canRequestInstalls(activity: Activity): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        activity.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}"),
            )
        )
    }

    fun install(activity: Activity, apk: File, expectedPackage: String) {
        require(apk.isFile && apk.canRead()) { "APK is not readable: $apk" }
        verify(activity, apk, expectedPackage)?.let { error(it) }
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
            val callback = Intent(activity, UpdateInstallResultActivity::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getActivity(activity, sessionId, callback, flags)
            session.commit(pending.intentSender)
        }
    }

    fun verify(activity: Activity, apk: File, expectedPackage: String): String? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val archive = activity.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return "Downloaded file is not a readable APK"
        if (archive.packageName != expectedPackage) {
            return "Package mismatch: expected $expectedPackage, got ${archive.packageName}"
        }

        val installed = try {
            activity.packageManager.getPackageInfo(expectedPackage, flags)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        if (installed != null) {
            if (versionCode(archive) < versionCode(installed)) return "Refusing package downgrade"
            val archiveSigners = signerDigests(archive)
            val installedSigners = signerDigests(installed)
            if (archiveSigners.isEmpty() || installedSigners.isEmpty() || archiveSigners != installedSigners) {
                return "Package signing certificate does not match the installed app"
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signerDigests(info: android.content.pm.PackageInfo): Set<String> {
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            info.signatures ?: return emptySet()
        }
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}

class UpdateInstallResultActivity : Activity() {
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
                if (confirmation != null) startActivity(confirmation)
                finish()
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(this, "Package installed", Toast.LENGTH_SHORT).show()
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
