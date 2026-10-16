package actor.starintel.quasar

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Android Keystore/AtomicFile tests. Run on a disposable emulator, not the host JVM. */
class WorkspaceDraftsTest {
    private fun withStore(test: (WorkspaceDrafts, File) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, "draft-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getFilesDir(): File = root
            override fun getApplicationContext(): Context = this
        }
        try { test(WorkspaceDrafts(context), File(root, "workspace-drafts-v1")) }
        finally { root.deleteRecursively() }
    }
    @Test fun encryptedRoundTripAndInterruptedReplacementPreserveAcknowledgement() = withStore { store, dir ->
        val source = "private draft " + "€".repeat(20_000)
        val key = store.save("route:0", source)
        assertEquals(source, store.read(key))
        assertFalse(File(dir, key).readBytes().toString(Charsets.UTF_8).contains("private draft"))
        val atomic = AtomicFile(File(dir, key))
        val interrupted = atomic.startWrite()
        interrupted.write(byteArrayOf(12, 1, 2, 3)); interrupted.close()
        assertEquals(source, store.read(key))
    }
    @Test fun oversizedAndCredentialWritesPreserveOldDraft() = withStore { store, _ ->
        val key = store.save("route:0", "acknowledged")
        assertThrows(IllegalArgumentException::class.java) { store.save("route:0", "€".repeat(90_000)) }
        assertThrows(IllegalArgumentException::class.java) { store.save("route:0", "star_sk_v1_neverpersistme") }
        assertEquals("acknowledged", store.read(key))
    }
    @Test fun corruptMissingAndOversizedEnvelopesFailClosed() = withStore { store, dir ->
        val key = store.save("route:0", "text")
        val bytes = File(dir, key).readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        File(dir, key).writeBytes(bytes)
        assertThrows(Exception::class.java) { store.read(key) }
        File(dir, key).writeBytes(byteArrayOf(12) + ByteArray(12) + ByteArray(DraftLimits.MAX_BYTES + 17))
        assertThrows(IllegalArgumentException::class.java) { store.read(key) }
        File(dir, key).delete()
        assertThrows(Exception::class.java) { store.read(key) }
    }
    @Test fun quotaAndExplicitRetentionNeverEvictLiveDraft() = withStore { store, dir ->
        val retained = store.save("keep", "keep")
        repeat(63) { store.save("other:$it", "other") }
        assertThrows(IllegalArgumentException::class.java) { store.save("overflow", "no") }
        assertEquals("keep", store.read(retained))
        File(dir, "${"a".repeat(64)}.new").writeBytes(byteArrayOf(0))
        store.pruneUnreferenced(setOf(retained))
        assertEquals(listOf(retained), dir.list()!!.toList())
        assertEquals("keep", store.read(retained))
    }
}
