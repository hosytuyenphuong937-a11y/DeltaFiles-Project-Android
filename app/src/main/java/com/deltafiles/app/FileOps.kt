package com.deltafiles.app

import android.webkit.MimeTypeMap
import java.io.File
import java.nio.file.Files

/** A snapshot of a file taken once while listing, so sorting never re-reads the disk. */
data class Entry(
    val file: File,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val childCount: Int
) {
    val path: String get() = file.absolutePath
}

enum class SortMode { NAME, DATE, SIZE, TYPE }

enum class Kind { FOLDER, IMAGE, VIDEO, AUDIO, ARCHIVE, OTHER }

object FileOps {

    private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg")
    private val videoExt = setOf("mp4", "mkv", "webm", "3gp", "avi", "mov", "m4v", "flv")
    private val audioExt = setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "amr")
    private val archiveExt = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "apk", "jar")

    fun snapshot(file: File): Entry {
        val dir = file.isDirectory
        return Entry(
            file = file,
            name = file.name,
            isDir = dir,
            size = if (dir) 0L else file.length(),
            modified = file.lastModified(),
            childCount = if (dir) (file.list()?.size ?: 0) else 0
        )
    }

    fun comparator(mode: SortMode): Comparator<Entry> {
        val byName = compareBy<Entry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val secondary: Comparator<Entry> = when (mode) {
            SortMode.NAME -> byName
            SortMode.DATE -> compareByDescending<Entry> { it.modified }.then(byName)
            SortMode.SIZE -> compareByDescending<Entry> { it.size }.then(byName)
            SortMode.TYPE -> compareBy<Entry, String>(String.CASE_INSENSITIVE_ORDER) { it.file.extension }.then(byName)
        }
        return compareByDescending<Entry> { it.isDir }.then(secondary)
    }

    fun kindOf(e: Entry): Kind {
        if (e.isDir) return Kind.FOLDER
        val ext = e.file.extension.lowercase()
        return when {
            ext in imageExt -> Kind.IMAGE
            ext in videoExt -> Kind.VIDEO
            ext in audioExt -> Kind.AUDIO
            ext in archiveExt -> Kind.ARCHIVE
            else -> Kind.OTHER
        }
    }

    fun mimeOf(file: File): String {
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    /** Returns a path in [dir] that doesn't exist yet: "name", then "name (1)", "name (2)", ... */
    fun uniqueTarget(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (target.exists()) {
            target = File(dir, "$base ($i)$ext")
            i++
        }
        return target
    }

    fun isSameOrInside(candidate: File, parent: File): Boolean {
        val c = candidate.canonicalPath
        val p = parent.canonicalPath
        return c == p || c.startsWith(p + File.separator)
    }

    /** Copies [sources] into [destDir]. Returns how many items failed. */
    fun copyAll(sources: List<File>, destDir: File): Int {
        var failed = 0
        for (src in sources) {
            if (src.isDirectory && isSameOrInside(destDir, src)) {
                failed++
                continue
            }
            val target = uniqueTarget(destDir, src.name)
            val ok = try {
                if (src.isDirectory) {
                    src.copyRecursively(target, overwrite = false)
                } else {
                    src.copyTo(target, overwrite = false)
                    true
                }
            } catch (e: Exception) {
                false
            }
            if (!ok) failed++
        }
        return failed
    }

    /** Moves [sources] into [destDir]. Returns how many items failed. */
    fun moveAll(sources: List<File>, destDir: File): Int {
        var failed = 0
        for (src in sources) {
            if (src.parentFile?.canonicalPath == destDir.canonicalPath) continue
            if (src.isDirectory && isSameOrInside(destDir, src)) {
                failed++
                continue
            }
            val target = uniqueTarget(destDir, src.name)
            val ok = try {
                if (src.renameTo(target)) {
                    true
                } else {
                    val copied = if (src.isDirectory) {
                        src.copyRecursively(target, overwrite = false)
                    } else {
                        src.copyTo(target, overwrite = false)
                        true
                    }
                    copied && deleteTree(src)
                }
            } catch (e: Exception) {
                false
            }
            if (!ok) failed++
        }
        return failed
    }

    /** Deletes a file or folder. Symbolic links are removed, never followed. */
    fun deleteTree(file: File): Boolean {
        if (Files.isSymbolicLink(file.toPath())) return file.delete()
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) deleteTree(child)
            }
        }
        return file.delete()
    }

    fun treeSize(file: File): Long {
        if (Files.isSymbolicLink(file.toPath())) return 0L
        if (!file.isDirectory) return file.length()
        var total = 0L
        file.listFiles()?.forEach { total += treeSize(it) }
        return total
    }
}
