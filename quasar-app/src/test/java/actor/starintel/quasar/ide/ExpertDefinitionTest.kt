package actor.starintel.quasar.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExpertDefinitionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val expert = ExpertDefinition(
        id = "geo-risk",
        name = "Geo risk",
        description = "Rank matching geo documents and emit a reviewed local alert.",
        schemas = listOf("geo_document(id, latitude, longitude, confidence)"),
        facts = listOf("minimum_confidence(0.8)."),
        rules = listOf("candidate(Id) :- geo_document(Id, _, _, C), minimum_confidence(M), C >= M."),
        arguments = listOf(ExpertArgument("document_id", "document-id", required = true)),
        activation = listOf("check geo risk", "rank nearby documents"),
        query = ExpertQuery("documents", "dtype:geo AND confidence:[0.8 TO *]", limit = 50),
        alert = ExpertAlert(enabled = true, condition = "candidate(DocumentId)", severity = "high", message = "High-confidence geo match"),
    )

    @Test
    fun validatesAndAdaptsToNeutralFbpNodeWithoutScheduling() {
        assertTrue(ExpertValidator.validate(expert).isEmpty())

        val node = ExpertFbpAdapter.toNode(expert)

        assertEquals("starintel.expert", node.component)
        assertEquals(listOf("document", "trigger"), node.inputPorts.map { it.name })
        assertEquals(listOf("result", "alert", "error"), node.outputPorts.map { it.name })
        assertEquals("geo-risk", node.configuration["expert_id"])
        assertFalse(node.configuration.keys.any { it.contains("schedule", ignoreCase = true) })
    }

    @Test
    fun codecAndPrivateStoreRoundTripEveryExpertField() {
        val store = ExpertDefinitionStore(temporary.newFolder("experts"))

        store.save(expert)
        val loaded = store.load("geo-risk")

        assertEquals(expert, loaded)
        assertEquals(listOf("geo-risk"), store.list().map { it.id })
    }

    @Test
    fun rejectsUnsafeQueryAndAmbiguousActivation() {
        val unsafe = expert.copy(query = expert.query.copy(expression = "dtype:geo\nAuthorization: Bearer nope"))
        assertTrue(ExpertValidator.validate(unsafe).any { it.code == "expert.query.multiline" })

        val duplicateActivation = expert.copy(activation = listOf("Check Geo Risk", "check geo risk"))
        assertTrue(ExpertValidator.validate(duplicateActivation).any { it.code == "expert.activation.duplicate" })

        val secretFact = expert.copy(facts = listOf("credential('star_sk_v1_not_for_workspace')."))
        assertTrue(ExpertValidator.validate(secretFact).any { it.code == "expert.secret" })
    }
}
