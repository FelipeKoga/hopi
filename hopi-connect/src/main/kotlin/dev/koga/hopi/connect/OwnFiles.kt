package dev.koga.hopi.connect

import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission

/**
 * Whether a bridge file (or a directory on the way to one) is this user's own. On Linux the JVM
 * bridges live under the shared `/tmp`, where another user could plant a `bridge.json` naming a
 * server of theirs: the host would send it rule bodies and show its made-up records. So a file or
 * directory Hopi trusts is not a link, is owned by the current user and is not writable by others.
 * Without POSIX attributes (Windows) there is no check.
 */
internal object OwnFiles {
    /** Null when [path] passes; else why not, for an error that names the path. */
    fun problem(path: Path): String? {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return null
        val attributes = try {
            Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: IOException) {
            return "cannot be read (${e.message})"
        }
        if (attributes.isSymbolicLink) return "is a symbolic link"
        val user = System.getProperty("user.name")
        if (user != null && attributes.owner().name != user) return "is owned by another user"
        val permissions = attributes.permissions()
        if (PosixFilePermission.GROUP_WRITE in permissions || PosixFilePermission.OTHERS_WRITE in permissions) {
            return "is writable by other users"
        }
        return null
    }

    /** The text of [file] when it passes [problem] and is at most [maxBytes]; read without following links. */
    fun readSmall(file: Path, maxBytes: Long): String {
        // A missing file stays a NoSuchFileException: callers report it as "app not running".
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) throw NoSuchFileException(file.toString())
        problem(file)?.let { throw IOException("$file $it; Hopi reads only your own bridge files") }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw IOException("$file is not a regular file")
        if (Files.size(file) > maxBytes) throw IOException("$file is larger than $maxBytes bytes")
        return Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(maxBytes.toInt() + 1) }.decodeToString()
    }
}
