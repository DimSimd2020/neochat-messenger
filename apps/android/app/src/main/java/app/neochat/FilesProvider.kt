package app.neochat

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only access for other apps to files the core decrypted into its tmp folder and to
 * downloaded app updates, granted per intent. Nothing outside those folders is reachable.
 */
class FilesProvider : ContentProvider() {
    override fun onCreate() = true

    private fun resolve(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val rel = uri.path?.removePrefix("/") ?: throw FileNotFoundException()
        val f = File(core(ctx), rel).canonicalFile
        if (ROOTS.none { f.path.startsWith(File(core(ctx), it).canonicalPath + File.separator) } || !f.isFile) throw FileNotFoundException()
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = resolve(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else if (it == OpenableColumns.DISPLAY_NAME) f.name else null })
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        private val ROOTS = listOf("tmp", "updates")
        private fun core(ctx: Context) = File(ctx.filesDir, "core").canonicalFile

        fun uriFor(ctx: Context, f: File): Uri {
            val rel = f.canonicalFile.relativeTo(core(ctx)).invariantSeparatorsPath
            return Uri.Builder().scheme("content").authority("app.neochat.files").path(rel).build()
        }
    }
}
