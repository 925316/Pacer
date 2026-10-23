package org.pacer.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * The runtime permissions this app itself needs, asked for once at startup.
 *
 * Asking at the door, rather than from the self-check, covers the same list for both the
 * probes and the map marker. A probe that runs before the grant would silently answer
 * "grant the permission" while the app looks fully configured, and a rooted device can be
 * granted them without a dialog.
 *
 * `READ_PHONE_STATE` is declared in the manifest but deliberately absent here: nothing
 * reads the phone state, and asking for a permission the code never uses is how an app
 * teaches its users to refuse the next one.
 */
object AppPermissions {

    private const val TAG = "Pacer"

    /** The request code the startup screen answers. */
    const val REQUEST_CODE = 2001

    /** Version-guarded, so a constant missing on an old device cannot break the list. */
    fun required(): List<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        // Only the API 26-28 export path writes to shared storage; the manifest caps the
        // declaration there, so asking on a newer device would be asking for a permission
        // the app cannot even hold.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            list += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        return list
    }

    /** The permissions from [required] that are still missing. */
    fun missing(activity: Activity): List<String> =
        required().filter {
            activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

    /**
     * Whether the activity still owns a window a dialog can be attached to.
     *
     * Neither flag implies the other: a configuration change destroys the activity with
     * `isFinishing` still false, and `finish()` alone leaves the window valid until
     * `onDestroy`. Takes booleans rather than the activity so the rule stays testable on a
     * plain JVM.
     */
    internal fun canModifyUi(isFinishing: Boolean, isDestroyed: Boolean): Boolean =
        !isFinishing && !isDestroyed

    /**
     * Grants what it can without a dialog, then asks for what is left.
     *
     * `pm grant` through `su` is the only way to satisfy a runtime permission without the
     * user's tap, so a rooted device becomes silent. Root being absent is the normal case,
     * not an error: it only decides whether a dialog follows.
     *
     * A device where `adb root` works but no `su` binary is exposed to apps (an emulator,
     * typically) cannot take this path. That is why the dialog is the fallback rather than
     * an error path.
     *
     * [onDone] receives the permissions that are still missing; empty means nothing was
     * asked of the user.
     */
    fun request(activity: Activity, onDone: (List<String>) -> Unit = {}) {
        val missing = missing(activity)
        if (missing.isEmpty()) {
            onDone(emptyList())
            return
        }
        Thread {
            val remaining = missing.filterNot { grantWithRoot(activity.packageName, it) }
            activity.runOnUiThread {
                if (remaining.isEmpty()) onDone(emptyList()) else ask(activity, remaining, onDone)
            }
        }.apply {
            name = "pacer-permission-grant"
            isDaemon = true
        }.start()
    }

    /** True only when `pm grant` really exited zero; every other outcome falls back to a dialog. */
    private fun grantWithRoot(packageName: String, permission: String): Boolean = try {
        val process = ProcessBuilder(
            "su", "-c", "pm grant $packageName $permission",
        ).redirectErrorStream(true).start()
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            false
        } else {
            // Drained so the process cannot block on a full pipe, and logged because a
            // denied permission here is otherwise indistinguishable from a missing `su`.
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val ok = process.exitValue() == 0
            if (!ok) Log.d(TAG, "pm grant $permission: $output")
            ok
        }
    } catch (t: Throwable) {
        Log.d(TAG, "pm grant $permission needs root that is not there", t)
        false
    }

    private fun ask(activity: Activity, wanted: List<String>, onDone: (List<String>) -> Unit) {
        // A dialog needs the activity's window token, which is gone once it is finishing or
        // destroyed: show() would throw BadTokenException. The answer still has to travel
        // back, because the caller redraws from it, and an empty list would claim these
        // permissions were granted when nobody was asked.
        if (!canModifyUi(activity.isFinishing, activity.isDestroyed)) {
            onDone(wanted)
            return
        }
        val strings = stringsFor(AppSettings(activity).language)
        AlertDialog.Builder(activity)
            .setTitle(strings.permissionTitle)
            .setMessage(strings.permissionBody)
            .setPositiveButton(strings.permissionGrant) { _, _ ->
                // The answer to this dialog is the request, not the grant: [onDone] carries
                // what is still missing, and reporting an empty list here claimed success
                // before the user had answered the system prompt.
                activity.requestPermissions(wanted.toTypedArray(), REQUEST_CODE)
                onDone(wanted)
            }
            .setNegativeButton(strings.permissionLater) { _, _ -> onDone(wanted) }
            .show()
    }
}
