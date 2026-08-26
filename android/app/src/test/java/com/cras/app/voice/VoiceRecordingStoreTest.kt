package com.cras.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceRecordingStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(
        maxCount: Int = DEFAULT_MAX_RETAINED_RECORDINGS,
        maxTotalBytes: Long = DEFAULT_MAX_RETAINED_TOTAL_BYTES,
    ): DirectoryVoiceRecordingStore =
        DirectoryVoiceRecordingStore(tmp.newFolder("recordings"), maxCount, maxTotalBytes)


    private fun exists(store: DirectoryVoiceRecordingStore, id: String): Boolean =
        store.fileOf(id)?.exists() == true

    private fun wavOfSize(sizeBytes: Int): ByteArray {
        val dataBytes = (sizeBytes - 44).coerceAtLeast(0)
        return createWavHeader(dataBytes) + ByteArray(dataBytes)
    }

    @Test
    fun `save persists the WAV and lists newest first`() {
        val store = newStore()
        val first = store.save(wavOfSize(1000), createdAtEpochMs = 1_000L)
        val second = store.save(wavOfSize(2_000), createdAtEpochMs = 2_000L)

        assertEquals(listOf(second.id, first.id), store.list().map { it.id })
        assertTrue(exists(store, second.id))
        assertEquals(2_000L, store.list().first().createdAtEpochMs)
    }

    @Test
    fun `latest returns the most recent retained recording`() {
        val store = newStore()
        assertNull(store.latest())

        val first = store.save(wavOfSize(500), createdAtEpochMs = 1_000L)
        val second = store.save(wavOfSize(600), createdAtEpochMs = 2_000L)

        assertEquals(second.id, store.latest()?.id)
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `retention evicts oldest recordings beyond the count bound`() {
        val store = newStore(maxCount = 3)
        val ids = (1..5).map { i ->
            store.save(wavOfSize(1_000), createdAtEpochMs = i.toLong()).id
        }

        val remaining = store.list().map { it.id }
        // Oldest two evicted, newest three retained newest-first.
        assertEquals(listOf(ids[4], ids[3], ids[2]), remaining)
        assertFalse(exists(store, ids[0]))
        assertFalse(exists(store, ids[1]))
    }

    @Test
    fun `retention evicts oldest recordings beyond the total size bound`() {
        val store = newStore(maxCount = 10, maxTotalBytes = 4_000)
        val a = store.save(wavOfSize(1_000), createdAtEpochMs = 1_000L)
        val b = store.save(wavOfSize(1_000), createdAtEpochMs = 2_000L)
        val c = store.save(wavOfSize(1_000), createdAtEpochMs = 3_000L)
        val d = store.save(wavOfSize(1_500), createdAtEpochMs = 4_000L) // pushes over 4000 bytes

        val remainingIds = store.list().map { it.id }
        // Only the oldest (a) is evicted to fit b + c + d under the bound.
        assertEquals(listOf(d.id, c.id, b.id), remainingIds)
        assertFalse(exists(store, a.id))
        assertTrue(exists(store, b.id))
        assertTrue(exists(store, c.id))
        assertTrue(exists(store, d.id))
        assertTrue(store.totalBytes() <= 4_000)
    }

    @Test
    fun `a single oversized recording is still retained on its own`() {
        val store = newStore(maxCount = 5, maxTotalBytes = 1_000)
        val big = store.save(wavOfSize(3_000), createdAtEpochMs = 1_000L)

        assertEquals(listOf(big.id), store.list().map { it.id })
        assertTrue(store.totalBytes() <= 1_000 || store.list().size == 1)
    }

    @Test
    fun `delete removes exactly one retained recording`() {
        val store = newStore()
        val a = store.save(wavOfSize(500), createdAtEpochMs = 1_000L)
        val b = store.save(wavOfSize(500), createdAtEpochMs = 2_000L)

        store.delete(a.id)

        assertEquals(listOf(b.id), store.list().map { it.id })
        assertFalse(exists(store, a.id))
        assertTrue(exists(store, b.id))

        // Deleting an unknown id is a no-op.
        store.delete("missing")
        assertEquals(1, store.list().size)
    }

    @Test
    fun `clearAll removes every retained recording`() {
        val store = newStore()
        store.save(wavOfSize(500), createdAtEpochMs = 1_000L)
        store.save(wavOfSize(500), createdAtEpochMs = 2_000L)
        store.save(wavOfSize(500), createdAtEpochMs = 3_000L)

        assertTrue(store.clearAll())

        assertTrue(store.list().isEmpty())
        assertNull(store.latest())
        store.list().forEach { assertFalse(exists(store, it.id)) }
    }

    @Test
    fun `recording owner is persisted across instances and cleared on clearAll`() {
        val folder = tmp.newFolder("persisted_owner")
        val store1 = DirectoryVoiceRecordingStore(folder)
        assertNull(store1.getRecordingOwner())

        store1.setRecordingOwner("op-1234")
        assertEquals("op-1234", store1.getRecordingOwner())

        // New instance targeting the same directory recovers the persisted owner
        val store2 = DirectoryVoiceRecordingStore(folder)
        assertEquals("op-1234", store2.getRecordingOwner())

        store2.save(wavOfSize(500), createdAtEpochMs = 1_000L)
        assertTrue(store2.clearAll())

        // Clearing all recordings also clears the persisted owner
        assertNull(store2.getRecordingOwner())

        val store3 = DirectoryVoiceRecordingStore(folder)
        assertNull(store3.getRecordingOwner())
    }

    @Test
    fun `readBytes returns null when file is empty or corrupted`() {
        val store = newStore()
        val rec = store.save(wavOfSize(500), createdAtEpochMs = 1_000L)
        val file = store.fileOf(rec.id)
        assertTrue(file != null && file.exists())

        // Truncate file to 0 bytes
        file!!.writeBytes(ByteArray(0))

        assertNull(store.readBytes(rec.id))
    }
}
