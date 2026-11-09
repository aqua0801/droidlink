package io.github.aqua0801.droidlink.desktop

import io.github.aqua0801.droidlink.desktop.transfer.DesktopIncomingFiles
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopIncomingFilesTest {
    private fun tempDir(): File = Files.createTempDirectory("droidlink-test").toFile().apply { deleteOnExit() }

    @Test
    fun commitRenamesAndNeverOverwrites() {
        val dir = tempDir()
        File(dir, "report.pdf").writeText("existing")
        val incoming = DesktopIncomingFiles(dir)

        val first = incoming.open("report.pdf", 3, null)
        first.output.write("new".toByteArray())
        val path = first.commit()

        assertEquals(File(dir, "report (1).pdf").absolutePath, path)
        assertEquals("new", File(path).readText())
        assertEquals("existing", File(dir, "report.pdf").readText())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun abortLeavesNothingBehind() {
        val dir = tempDir()
        val sink = DesktopIncomingFiles(dir).open("half.bin", 10, null)
        sink.output.write(ByteArray(5))
        sink.abort()
        assertFalse(dir.listFiles()!!.any())
    }
}
