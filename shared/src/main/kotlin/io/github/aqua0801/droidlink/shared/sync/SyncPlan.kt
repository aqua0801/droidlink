package io.github.aqua0801.droidlink.shared.sync

import io.github.aqua0801.droidlink.shared.protocol.SyncEntry

/**
 * What one sync run will do. The PC listing is the source of truth: a file is sent when the
 * phone lacks it or has different content; phone-only files are left alone unless [mirror]
 * is on, in which case they are deleted.
 */
data class SyncPlan(val send: List<SyncEntry>, val unchanged: Int, val delete: List<String>) {
    companion object {
        fun of(pc: List<SyncEntry>, phone: List<SyncEntry>, mirror: Boolean): SyncPlan {
            val phoneByKey = phone.associateBy { SyncPaths.key(it.path) }
            val pcKeys = HashSet<String>()
            val send = ArrayList<SyncEntry>()
            var unchanged = 0
            for (entry in pc) {
                val key = SyncPaths.key(entry.path)
                pcKeys += key
                val there = phoneByKey[key]
                if (there != null && there.size == entry.size && there.sha256.equals(entry.sha256, ignoreCase = true)) {
                    unchanged++
                } else {
                    send += entry
                }
            }
            val delete = if (mirror) phone.filter { SyncPaths.key(it.path) !in pcKeys }.map { it.path } else emptyList()
            return SyncPlan(send, unchanged, delete)
        }
    }
}
