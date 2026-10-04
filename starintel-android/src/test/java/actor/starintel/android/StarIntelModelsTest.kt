package actor.starintel.android

import actor.starintel.android.model.ActorCapability
import actor.starintel.android.model.ActorManifest
import actor.starintel.android.model.AgentBudget
import actor.starintel.android.model.AgentTurnResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StarIntelModelsTest {
    @Test
    fun actorManifestRequiresKnownCapabilitiesAndQualifiedEntrypoint() {
        val manifest = ActorManifest.fromJson(
            JSONObject(
                """{
                    "id":"local.person-normalizer",
                    "entrypoint":"STARINTEL.MOBILE.ACTORS:NORMALIZE-PERSON",
                    "accepts":["person"],
                    "capabilities":["document.read","document.write"]
                }""",
            ),
        )

        assertEquals(setOf("person"), manifest.accepts)
        assertTrue(ActorCapability.WRITE_DOCUMENT in manifest.capabilities)
    }

    @Test(expected = IllegalArgumentException::class)
    fun actorManifestRejectsUnknownCapability() {
        ActorManifest.fromJson(
            JSONObject(
                """{
                    "id":"local.unsafe",
                    "entrypoint":"LOCAL:UNSAFE",
                    "capabilities":["ambient.shell"]
                }""",
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun prologRlmDepthAboveOneRequiresExperimentalGate() {
        AgentBudget(maxDepth = 2)
    }

    @Test
    fun agentTurnPreservesTypedReviewOperations() {
        val result = AgentTurnResult.fromJson(
            JSONObject(
                """{
                    "status":"completed",
                    "answer":"Found two related records.",
                    "trace_id":"trace-7",
                    "operations":[{
                        "name":"actor.dispatch",
                        "arguments":{"actor":"local.person-normalizer"},
                        "authority":"approve_diff"
                    }]
                }""",
            ),
        )

        assertEquals("trace-7", result.traceId)
        assertEquals("actor.dispatch", result.operations.single().name)
        assertEquals("approve_diff", result.operations.single().authority)
    }
}
