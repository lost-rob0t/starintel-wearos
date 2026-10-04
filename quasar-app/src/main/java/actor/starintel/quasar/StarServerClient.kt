package actor.starintel.quasar

import actor.starintel.android.api.StarIntelClient
import actor.starintel.android.model.AgentTurnRequest
import actor.starintel.android.model.AgentTurnResult
import actor.starintel.android.model.Endpoint
import actor.starintel.android.model.LoginResult
import actor.starintel.android.model.StarSession
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal class StarServerClient(context: Context) {
    private val config = QuasarConfig(context.applicationContext)
    private val client = StarIntelClient(
        sessionProvider = {
            val token = config.apiKey()
            if (config.serverUrl().isBlank() || token.isNullOrBlank()) null
            else StarSession(config.serverUrl(), token)
        },
        clientVersion = BuildConfig.VERSION_NAME,
        allowCleartext = BuildConfig.DEBUG,
    )

    fun health(): JSONObject = client.health()

    fun stats(): JSONObject = client.stats()

    fun login(serverUrl: String, username: String, password: String): LoginResult =
        client.login(serverUrl, username, password)

    fun authContext(serverUrl: String, apiKey: String): JSONObject = client.authContext(serverUrl, apiKey)

    fun capabilities(): List<Endpoint> = client.capabilities()

    fun search(query: String, limit: Int = 40): JSONArray = client.search(query, limit)

    fun document(id: String): JSONObject = client.document(id)

    fun createDocument(document: JSONObject): JSONObject = client.createDocument(document)

    fun bulkCreate(documents: JSONArray): JSONObject = client.bulkCreate(documents)

    fun createTarget(actor: String, target: String, dataset: String): JSONObject =
        client.createTarget(actor, target, dataset)

    fun prologRlmTurn(request: AgentTurnRequest): AgentTurnResult = client.prologRlmTurn(request)
}

internal fun parseLoginResponse(root: JSONObject): LoginResult = actor.starintel.android.api.parseLoginResponse(root)

internal fun resultDocument(row: JSONObject): JSONObject = actor.starintel.android.api.resultDocument(row)

internal fun resultId(row: JSONObject): String = actor.starintel.android.api.resultId(row)
