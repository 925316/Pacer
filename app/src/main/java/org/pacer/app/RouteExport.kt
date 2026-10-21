package org.pacer.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Writes route files somewhere the user can find them without this app.
 *
 * The app's own list lives in preferences, which is right for the app and useless for the
 * user: those files are inside the app's private directory, invisible to a file manager,
 * gone when the app is uninstalled, and impossible to hand to another device. This puts a
 * copy in the system's Downloads collection, in a Pacer folder, which is the standard place
 * for a file the user asked to keep.
 *
 * No permission is involved. Since Android 10 an app may write its own files to the shared
 * Downloads collection through MediaStore without asking for anything; the older
 * `WRITE_EXTERNAL_STORAGE` path is only needed below that, and [minSdk] is 26, so the
 * legacy branch exists for API 26-28 only.
 *
 * Read-only in the other direction on purpose: MediaStore answers "give me the file this app
 * created", not "list every file in this folder", so the list of saved routes stays in the
 * app's own store. This is an export, not the storage layer.
 */
object RouteExport {

    /** The folder shown in a file manager under Downloads. */
    const val FOLDER = "Pacer"

    /** What went wrong, or the display path of what was written. */
    sealed interface Result {
        data class Written(val displayPath: String) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Writes [content] as [fileName] into Downloads/Pacer.
     *
     * A name that already exists gets a numeric suffix rather than replacing the file: an
     * overwrite the user did not ask for is data loss, and MediaStore will happily accept
     * the same display name twice, leaving two files with the same name.
     */
    fun write(context: Context, fileName: String, content: String): Result {
        val safeName = sanitise(fileName)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(context, safeName, content)
            } else {
                writeLegacy(safeName, content)
            }
        } catch (t: Throwable) {
            Result.Failed(t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
    }

    private fun writeViaMediaStore(context: Context, fileName: String, content: String): Result {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            // Pending until the bytes are in, so a file manager cannot open a half-written file.
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: return Result.Failed("the downloads collection refused the entry")
        val chosen = displayNameOf(context, uri) ?: fileName
        val wrote = try {
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            return Result.Failed(t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
        if (wrote == null) {
            resolver.delete(uri, null, null)
            return Result.Failed("could not open the new file for writing")
        }
        // Clearing IS_PENDING is what makes the file visible; until then it does not exist
        // as far as any other app is concerned.
        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return Result.Written("Download/$FOLDER/$chosen")
    }

    /**
     * The API 26-28 path: a real directory under the shared Downloads folder.
     *
     * Those releases predate scoped storage, so the app writes the file directly. It needs
     * WRITE_EXTERNAL_STORAGE, which the caller must have been granted; a refusal surfaces
     * here as the write failing rather than as an invisible no-op.
     */
    private fun writeLegacy(fileName: String, content: String): Result {
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloads, FOLDER)
        if (!dir.exists() && !dir.mkdirs()) return Result.Failed("could not create Download/$FOLDER")
        var target = File(dir, fileName)
        var attempt = 1
        while (target.exists()) {
            if (attempt >= MAX_ATTEMPTS) return Result.Failed("too many files with that name")
            target = File(dir, withSuffix(fileName, ++attempt))
        }
        target.writeText(content)
        return Result.Written("Download/$FOLDER/${target.name}")
    }

    private fun displayNameOf(context: Context, uri: android.net.Uri): String? =
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Downloads.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    /** `route.json` at attempt 2 becomes `route (2).json`, keeping the extension last. */
    private fun withSuffix(fileName: String, attempt: Int): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot <= 0) {
            "$fileName ($attempt)"
        } else {
            fileName.substring(0, dot) + " ($attempt)" + fileName.substring(dot)
        }
    }

    /**
     * Keeps the name to what every filesystem accepts.
     *
     * The route name comes from the user, and a name is not a filename: slashes, colons and
     * control characters are legal in one and not the other.
     */
    private fun sanitise(raw: String): String {
        val cleaned = raw
            .map { if (it in ILLEGAL || it.isISOControl()) '_' else it }
            .joinToString("")
            .trim()
            .trim('.')
        val base = cleaned.ifEmpty { "route" }.take(MAX_NAME)
        return if (base.endsWith(".json")) base else "$base.json"
    }

    private const val MAX_ATTEMPTS = 100

    private const val MAX_NAME = 80

    private val ILLEGAL = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\n', '\r', '\t')
}
