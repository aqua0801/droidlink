package io.github.aqua0801.droidlink.shared

import io.github.aqua0801.droidlink.shared.protocol.SyncEntry
import io.github.aqua0801.droidlink.shared.sync.SyncPaths
import io.github.aqua0801.droidlink.shared.sync.SyncPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncPlanTest {
    private fun e(path: String, sha: String, size: Long = 1) = SyncEntry(path, size, sha)

    @Test
    fun sendsMissingAndChangedOnly() {
        val pc = listOf(e("a.txt", "aa"), e("dir/b.png", "bb"), e("c.md", "cc"))
        val phone = listOf(e("A.TXT", "AA"), e("dir/b.png", "old"), e("phone-only.txt", "pp"))
        val plan = SyncPlan.of(pc, phone, mirror = false)
        assertEquals(listOf("dir/b.png", "c.md"), plan.send.map { it.path })
        assertEquals(1, plan.unchanged)
        assertEquals(emptyList(), plan.delete)
    }

    @Test
    fun mirrorDeletesPhoneOnlyFiles() {
        val plan = SyncPlan.of(listOf(e("keep.txt", "k")), listOf(e("Keep.txt", "k"), e("gone/x.bin", "x")), mirror = true)
        assertEquals(emptyList(), plan.send)
        assertEquals(listOf("gone/x.bin"), plan.delete)
    }

    @Test
    fun sizeMismatchCountsAsChanged() {
        val plan = SyncPlan.of(listOf(e("f", "same", size = 2)), listOf(e("f", "same", size = 1)), mirror = false)
        assertEquals(1, plan.send.size)
    }

    @Test
    fun pathRules() {
        assertEquals("a/b/c.txt", SyncPaths.normalize("a\\b/./c.txt"))
        assertEquals("a/b", SyncPaths.normalize("a//b/"))
        assertNull(SyncPaths.normalize("../x"))
        assertNull(SyncPaths.normalize("a/../../x"))
        assertNull(SyncPaths.normalize("/etc/passwd"))
        assertNull(SyncPaths.normalize("C:/x"))
        assertNull(SyncPaths.normalize("a/b?c"))
        assertNull(SyncPaths.normalize(""))
        assertTrue(SyncPaths.isIgnored(".nomedia"))
        assertTrue(SyncPaths.isIgnored("dir/.git/config"))
        assertTrue(SyncPaths.isIgnored("photos/Thumbs.db"))
        assertFalse(SyncPaths.isIgnored("docs/readme.md"))
    }
}
