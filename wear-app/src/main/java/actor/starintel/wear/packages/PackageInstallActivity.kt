package actor.starintel.wear.packages

import actor.starintel.update.PackageTransferProtocol
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import java.io.File
import java.io.FileInputStream

class PackageInstallActivity : Activity() {
    private var waitingForUnknownSource = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        waitingForUnknownSource = savedInstanceState?.getBoolean(STATE_WAITING_PERMISSION, false) ?: false
        if (intent.action == ACTION_RESULT) {
            handleResult(intent)
        } else if (!waitingForUnknownSource) {
            beginInstall()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_WAITING_PERMISSION, waitingForUnknownSource)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (!waitingForUnknownSource) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
            waitingForUnknownSource = false
            beginInstall()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        waitingForUnknownSource = false
        if (intent.action == ACTION_RESULT) handleResult(intent) else beginInstall()
    }

    private fun beginInstall() {
        val apk = File(intent.getStringExtra(EXTRA_APK) ?: return fail("Missing package file"))
        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: return fail("Missing package name")
        val nodeId = intent.getStringExtra(EXTRA_NODE) ?: return fail("Missing phone node")
        val transferId = intent.getStringExtra(EXTRA_TRANSFER) ?: return fail("Missing transfer id")
        val artifactId = intent.getStringExtra(EXTRA_ARTIFACT) ?: return fail("Missing artifact id")

        if (!apk.isFile || !apk.canRead()) return fail("Package file disappeared")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            waitingForUnknownSource = true
            sendStatus(
                nodeId,
                transferId,
                artifactId,
                PackageTransferProtocol.Status.WAITING_USER,
                "On the watch, allow StarIntel to install unknown apps; installation resumes when you return.",
            )
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${this.packageName}"),
                )
            )
            return
        }

        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
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
                session.openWrite("starintel.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val callback = Intent(this, PackageInstallActivity::class.java)
                .setAction(ACTION_RESULT)
                .putExtra(EXTRA_APK, apk.absolutePath)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_NODE, nodeId)
                .putExtra(EXTRA_TRANSFER, transferId)
                .putExtra(EXTRA_ARTIFACT, artifactId)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getActivity(this, sessionId, callback, flags)
            sendStatus(nodeId, transferId, artifactId, PackageTransferProtocol.Status.INSTALLING, null)
            session.commit(pending.intentSender)
        }
        finish()
    }

    private fun handleResult(callback: Intent) {
        val nodeId = callback.getStringExtra(EXTRA_NODE) ?: return finish()
        val transferId = callback.getStringExtra(EXTRA_TRANSFER) ?: return finish()
        val artifactId = callback.getStringExtra(EXTRA_ARTIFACT) ?: return finish()
        val apkPath = callback.getStringExtra(EXTRA_APK)
        when (callback.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                sendStatus(
                    nodeId,
                    transferId,
                    artifactId,
                    PackageTransferProtocol.Status.WAITING_USER,
                    "Confirm the package install on the watch.",
                )
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
                sendStatus(nodeId, transferId, artifactId, PackageTransferProtocol.Status.SUCCESS, "Installed")
                apkPath?.let { File(it).delete() }
                Toast.makeText(this, "$artifactId installed", Toast.LENGTH_SHORT).show()
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
                sendStatus(nodeId, transferId, artifactId, PackageTransferProtocol.Status.FAILURE, detail)
                Toast.makeText(this, detail, Toast.LENGTH_LONG).show()
                finish()
            }
            else -> finish()
        }
    }

    private fun fail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun sendStatus(nodeId: String, transferId: String, artifactId: String, state: String, detail: String?) {
        PackageStatusMessenger.send(
            this,
            nodeId,
            PackageTransferProtocol.Status(transferId, artifactId, state, detail),
        )
    }

    companion object {
        const val EXTRA_APK = "actor.starintel.wear.packages.APK"
        const val EXTRA_PACKAGE = "actor.starintel.wear.packages.PACKAGE"
        const val EXTRA_NODE = "actor.starintel.wear.packages.NODE"
        const val EXTRA_TRANSFER = "actor.starintel.wear.packages.TRANSFER"
        const val EXTRA_ARTIFACT = "actor.starintel.wear.packages.ARTIFACT"
        private const val ACTION_RESULT = "actor.starintel.wear.packages.INSTALL_RESULT"
        private const val STATE_WAITING_PERMISSION = "waiting_unknown_source"
    }
}
