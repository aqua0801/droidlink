package io.github.aqua0801.droidlink.desktop

import io.github.aqua0801.droidlink.desktop.sync.SyncFolderScanner
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SyncFolderScannerTest {
    private fun tempDir(): File = Files.createTempDirectory("droidlink-sync").toFile().apply { deleteOnExit() }

    @Test
    fun listsFilesWithRelativePathsAndSkipsHidden() {
        val root = tempDir()
        File(root, "docs/notes").mkdirs()
        File(root, "docs/notes/a.md").writeText("hello")
        File(root, "top.txt").writeText("x")
        File(root, ".git").mkdirs()
        File(root, ".git/config").writeText("hidden")
        File(root, ".nomedia").writeText("")
        File(root, "Thumbs.db").writeText("clutter")

        val scanner = SyncFolderScanner(File(tempDir(), "cache.json"))
        val entries = scanner.scan(root).map { it.entry }
        assertEquals(listOf("docs/notes/a.md", "top.txt"), entries.map { it.path })
        // sha256("hello")
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", entries[0].sha256)
    }

    @Test
    fun rehashesOnlyChangedFiles() {
        val root = tempDir()
        val file = File(root, "f.txt").apply { writeText("one") }
        val scanner = SyncFolderScanner(File(tempDir(), "cache.json"))
        val first = scanner.scan(root).single().entry.sha256

        file.writeText("two!")
        file.setLastModified(file.lastModified() + 2_000)
        val second = scanner.scan(root).single().entry.sha256
        assertNotEquals(first, second)
        assertEquals(second, scanner.scan(root).single().entry.sha256)
    }
}
