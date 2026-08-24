package com.example.pixcam.lut

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** One LUT available to the app, either bundled in assets or imported by the user. */
data class LutEntry(val id: String, val name: String, val builtin: Boolean)

private const val ASSET_DIR = "luts"
private const val IMPORT_DIR = "luts"

/** Lists, loads, and imports .cube 3D LUTs from app assets and app-private storage. */
object LutRepository {

    fun list(context: Context): List<LutEntry> {
        val builtins = (context.assets.list(ASSET_DIR) ?: emptyArray())
            .filter { it.endsWith(".cube", ignoreCase = true) }
            .map { LutEntry(id = "asset:$it", name = prettyName(it), builtin = true) }

        val importDir = importDir(context)
        val imported = (importDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".cube", ignoreCase = true) }
            .map { LutEntry(id = "file:${it.name}", name = prettyName(it.name), builtin = false) }

        return (builtins + imported).sortedBy { it.name.lowercase() }
    }

    fun load(context: Context, entry: LutEntry): CubeLut {
        val text = when {
            entry.id.startsWith("asset:") -> {
                val filename = entry.id.removePrefix("asset:")
                context.assets.open("$ASSET_DIR/$filename").use { it.readBytes().decodeToString() }
            }
            entry.id.startsWith("file:") -> {
                val filename = entry.id.removePrefix("file:")
                File(importDir(context), filename).readText()
            }
            else -> throw IllegalArgumentException("unknown LUT id: ${entry.id}")
        }
        return CubeParser.parse(entry.name, text)
    }

    fun import(context: Context, uri: Uri): LutEntry {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw IllegalArgumentException("could not open $uri")

        val displayName = queryDisplayName(context, uri)
        val baseName = sanitizeFilename(
            displayName?.removeSuffix(".cube")?.removeSuffix(".CUBE")
                ?: "imported_${System.currentTimeMillis()}"
        )

        // Validate before touching disk: an invalid LUT should leave no file behind.
        CubeParser.parse(baseName, text)

        val dir = importDir(context).apply { mkdirs() }
        val file = uniqueFile(dir, baseName)
        file.writeText(text)

        return LutEntry(id = "file:${file.name}", name = prettyName(file.name), builtin = false)
    }

    private fun importDir(context: Context): File = File(context.filesDir, IMPORT_DIR)

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (col >= 0) cursor.getString(col) else null
                    } else {
                        null
                    }
                }
        }.getOrNull()
    }

    private fun uniqueFile(dir: File, baseName: String): File {
        var candidate = File(dir, "$baseName.cube")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(dir, "${baseName}_$suffix.cube")
            suffix++
        }
        return candidate
    }

    private fun sanitizeFilename(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9_.\\- ]"), "_").trim()
        return cleaned.ifEmpty { "imported_${System.currentTimeMillis()}" }
    }

    private fun prettyName(filename: String): String =
        filename.substringBeforeLast(".").replace('_', ' ').replace('-', ' ').trim()
}
