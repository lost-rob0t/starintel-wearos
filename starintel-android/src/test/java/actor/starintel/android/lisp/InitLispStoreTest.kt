package actor.starintel.android.lisp

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InitLispStoreTest {
    @Test
    fun `first open seeds init and later opens preserve user edits`() {
        val root = Files.createTempDirectory("init-lisp-test").toFile()
        val store = InitLispStore(root) { "(quasar-config :theme :dark)\n".byteInputStream() }

        val file = store.ensureSeeded()
        assertEquals("(quasar-config :theme :dark)\n", file.readText())
        file.writeText("; user edit\n(quasar-config :theme :red)\n")

        assertEquals(file, store.ensureSeeded())
        assertTrue(file.readText().contains(":red"))
    }

    @Test
    fun `save replaces atomically and preserves one backup`() {
        val root = Files.createTempDirectory("init-lisp-test").toFile()
        val store = InitLispStore(root) { "first\n".byteInputStream() }
        store.ensureSeeded()

        store.save("second\n")

        assertEquals("second\n", store.file.readText())
        assertEquals("first\n", store.backupFile.readText())
        assertFalse(root.resolve("init.lisp.tmp").exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `save rejects oversized init`() {
        val root = Files.createTempDirectory("init-lisp-test").toFile()
        InitLispStore(root) { "seed".byteInputStream() }.save("x".repeat(InitLispStore.MAX_INIT_BYTES + 1))
    }
}
