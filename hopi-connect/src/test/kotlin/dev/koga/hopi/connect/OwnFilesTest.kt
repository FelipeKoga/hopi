package dev.koga.hopi.connect

import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnFilesTest {
    private val dir = Files.createTempDirectory("hopi-own-files")

    @Test
    fun ownFileIsRead() {
        val file = Files.writeString(dir.resolve("bridge.json"), """{"port":1,"token":"x"}""")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        assertNull(OwnFiles.problem(file))
        assertEquals("""{"port":1,"token":"x"}""", OwnFiles.readSmall(file, 1024))
    }

    @Test
    fun aFileOthersCanWriteIsRefused() {
        val file = Files.writeString(dir.resolve("bridge.json"), "{}")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"))
        assertEquals("is writable by other users", OwnFiles.problem(file))
        assertFailsWith<IOException> { OwnFiles.readSmall(file, 1024) }
    }

    @Test
    fun aLinkIsRefusedAndNotFollowed() {
        val target = Files.writeString(dir.resolve("real.json"), "{}")
        val link = Files.createSymbolicLink(dir.resolve("bridge.json"), target)
        assertEquals("is a symbolic link", OwnFiles.problem(link))
        assertFailsWith<IOException> { OwnFiles.readSmall(link, 1024) }
    }

    @Test
    fun aLargeFileIsRefused() {
        val file = Files.write(dir.resolve("bridge.json"), ByteArray(2048))
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        val error = assertFailsWith<IOException> { OwnFiles.readSmall(file, 1024) }
        assertTrue(error.message.orEmpty().contains("larger"), error.message)
    }

    @Test
    fun aMissingFileStaysMissing() {
        assertFailsWith<java.nio.file.NoSuchFileException> { OwnFiles.readSmall(dir.resolve("absent.json"), 1024) }
    }
}
