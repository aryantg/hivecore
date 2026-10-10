package com.hivecore.engine

import com.hivecore.core.Hashing
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64
import kotlin.random.Random

class BlobStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val bytes = Random(7).nextBytes(2_500_000)

    @Test fun chunksAtMost900KbAndReassembles() {
        val owner = BlobStore(tmp.newFolder("owner"))
        val worker = BlobStore(tmp.newFolder("worker"))
        val id = owner.put(bytes)
        assertEquals(Hashing.sha256Hex(bytes), id)
        val chunks = owner.chunks(id, "accounts", "job1")
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { Base64.getDecoder().decode(it.bytesB64).size <= BlobStore.MAX_CHUNK })
        // Out of order is fine.
        assertNull(worker.onChunk(chunks[2]).getOrThrow())
        assertNull(worker.onChunk(chunks[0]).getOrThrow())
        assertEquals(2, worker.pendingChunks(id))
        assertEquals(id, worker.onChunk(chunks[1]).getOrThrow())
        assertArrayEquals(bytes, worker.file(id)!!.readBytes())
    }

    @Test fun corruptedChunkLosesTheBlob() {
        val owner = BlobStore(tmp.newFolder("owner"), chunkBytes = 1_000_000.coerceAtMost(BlobStore.MAX_CHUNK))
        val worker = BlobStore(tmp.newFolder("worker"))
        val id = owner.put(bytes)
        val chunks = owner.chunks(id, "accounts", "job1").toMutableList()
        val raw = Base64.getDecoder().decode(chunks[1].bytesB64)
        raw[17] = (raw[17].toInt() xor 0x55).toByte()
        chunks[1] = chunks[1].copy(bytesB64 = Base64.getEncoder().encodeToString(raw))
        assertNull(worker.onChunk(chunks[0]).getOrThrow())
        assertNull(worker.onChunk(chunks[1]).getOrThrow())
        assertTrue(worker.onChunk(chunks[2]).isFailure)
        assertFalse(worker.has(id))
        assertEquals(0, worker.pendingChunks(id))
    }

    @Test fun malformedChunksAreRejected() {
        val worker = BlobStore(tmp.newFolder("worker"))
        val id = Hashing.sha256Hex("x")
        assertTrue(worker.onChunk(com.hivecore.mesh.BlobBody("nothex", 0, 1, "", "a", "j")).isFailure)
        assertTrue(worker.onChunk(com.hivecore.mesh.BlobBody(id, 3, 2, "", "a", "j")).isFailure)
        assertTrue(worker.onChunk(com.hivecore.mesh.BlobBody(id, 0, 1, "%%%", "a", "j")).isFailure)
        // A chunk claiming another total than its siblings drops the partial blob.
        assertNull(worker.onChunk(com.hivecore.mesh.BlobBody(id, 0, 2, "eA==", "a", "j")).getOrThrow())
        assertTrue(worker.onChunk(com.hivecore.mesh.BlobBody(id, 1, 3, "eA==", "a", "j")).isFailure)
        // A single-chunk blob that hashes right is stored.
        assertEquals(id, worker.onChunk(com.hivecore.mesh.BlobBody(id, 0, 1, "eA==", "a", "j")).getOrThrow())
    }

    @Test fun deleteAfterCountersignReportsWhatWasHeld() {
        val store = BlobStore(tmp.newFolder("w"))
        val id = store.put(bytes)
        val other = Hashing.sha256Hex("never stored")
        val body = store.deleteAfterCountersign("job1", "hr", listOf(id, other))
        assertEquals(listOf(id), body.blobIds)
        assertEquals("hr", body.node)
        assertFalse(store.has(id))
    }
}
