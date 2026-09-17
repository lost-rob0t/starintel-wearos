package actor.starintel.quasar.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivateWorkspaceStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun seedsWritableInitAndRoundTripsBoundedBuffers() {
        val root = temporary.newFolder("workspace")
        val store = PrivateWorkspaceStore(root, maxSourceBytes = 1024, maxWorkspaceBytes = 4096)

        store.seedDefaults()
        assertTrue(store.list(IdeLanguage.LISP).any { it.name == "init.lisp" })

        store.save(SourceBuffer("rules.pl", IdeLanguage.PROLOG, "trusted(alice)."))
        assertEquals("trusted(alice).", store.load(IdeLanguage.PROLOG, "rules.pl").source)
        assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun rejectsTraversalOversizeAndLikelySecretsWithoutEchoingThem() {
        val store = PrivateWorkspaceStore(temporary.newFolder("safe"), maxSourceBytes = 128, maxWorkspaceBytes = 512)

        val traversal = runCatching { store.save(SourceBuffer("../bad.pl", IdeLanguage.PROLOG, "x.")) }.exceptionOrNull()
        assertTrue(traversal is WorkspaceException)

        val oversize = runCatching { store.save(SourceBuffer("big.pl", IdeLanguage.PROLOG, "x".repeat(129))) }.exceptionOrNull()
        assertTrue(oversize is WorkspaceException)

        val secret = "star_sk_v1_this_must_never_be_echoed"
        val secretError = runCatching { store.save(SourceBuffer("secret.lisp", IdeLanguage.LISP, secret)) }.exceptionOrNull()
        assertTrue(secretError is WorkspaceException)
        assertFalse(secretError?.message.orEmpty().contains(secret))
    }

    @Test
    fun validationHooksCanBlockSaveWithoutMutatingExistingFile() {
        val root = temporary.newFolder("hooks")
        val hook = ValidationHook { buffer ->
            if ("deny" in buffer.source) listOf(Diagnostic.error("policy.denied", "Policy denied source")) else emptyList()
        }
        val store = PrivateWorkspaceStore(root, validationHooks = listOf(hook))
        store.save(SourceBuffer("policy.lisp", IdeLanguage.LISP, "(allow)"))

        val failed = runCatching { store.save(SourceBuffer("policy.lisp", IdeLanguage.LISP, "(deny)")) }

        assertTrue(failed.isFailure)
        assertEquals("(allow)", store.load(IdeLanguage.LISP, "policy.lisp").source)
    }
}
