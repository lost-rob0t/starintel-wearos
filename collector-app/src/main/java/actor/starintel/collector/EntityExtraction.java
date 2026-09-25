package actor.starintel.collector;

import actor.starintel.android.api.StarIntelClient;
import actor.starintel.android.model.AgentBudget;
import actor.starintel.android.model.AgentTurnRequest;
import actor.starintel.android.model.AgentTurnResult;
import actor.starintel.android.model.Endpoint;
import actor.starintel.android.model.StarSession;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Entity extraction over transcribed captures.
 *
 * The primary path is the deterministic on-device heuristic pass; its candidates are
 * queued with low confidence and explicit provenance. When the Star server advertises
 * the prolog-rlm agent endpoint, a bounded agent turn can refine the same transcript;
 * agent output is projected into documents with its own provenance and is the only
 * place LLM-derived entities come from.
 */
public final class EntityExtraction {
    public static final String AGENT_PATH = "/api/v1/agents/prolog-rlm/turn";
    static final int MAX_AGENT_PROMPT_CHARS = 16_000;

    private EntityExtraction() {}

    public static class HeuristicResult {
        public final int captures;
        public final int documents;

        HeuristicResult(int captures, int documents) {
            this.captures = captures;
            this.documents = documents;
        }
    }

    /** Runs the heuristic pass over every transcribed capture not yet projected. */
    public static HeuristicResult extractHeuristic(StarWirelessStore store) {
        List<StarWirelessStore.CaptureRow> transcribed = store.capturesInState("transcribed", 64);
        int documents = 0;
        int captures = 0;
        for (StarWirelessStore.CaptureRow capture : transcribed) {
            String[] detail = store.captureDetail(capture.id);
            if (detail.length < 3 || detail[1].isEmpty()) continue;
            if (store.captureProjected(capture.id)) continue;
            DocumentProjection.Result result = DocumentProjection.projectCapture(store, capture);
            store.markCaptureProjected(capture.id);
            captures++;
            documents += result.documents;
        }
        return new HeuristicResult(captures, documents);
    }

    /**
     * Runs a prolog-rlm agent turn over one transcript. Requires the server to advertise
     * the agent endpoint; never fabricates results when it does not.
     */
    public static String interpretWithAgent(
            StarWirelessStore store,
            StarWirelessStore.CaptureRow capture,
            String serverUrl,
            String apiKey) {
        StarIntelClient client = new StarIntelClient(
                () -> new StarSession(serverUrl, apiKey), "star-wireless/0.4", false);
        List<Endpoint> endpoints = client.capabilities();
        if (!advertisesAgent(endpoints)) {
            return "Server does not advertise the prolog-rlm agent endpoint";
        }
        String[] detail = store.captureDetail(capture.id);
        if (detail.length < 3 || detail[1].isEmpty() || !"transcribed".equals(detail[0])) {
            return "Capture has no transcript to interpret";
        }
        TranscriptModels.Transcript transcript;
        try {
            transcript = TranscriptModels.fromJson(detail[1]);
        } catch (org.json.JSONException failure) {
            return "Stored transcript is unreadable";
        }
        String text = transcript.fullText();
        if (text.isEmpty()) return "Transcript is empty";

        String prompt = "Extract StarIntel OSINT entities from this transcript. Respond ONLY with a JSON object "
                + "{\"people\":[\"...\"],\"orgs\":[\"...\"],\"relations\":[{\"subject\":\"...\","
                + "\"predicate\":\"...\",\"object\":\"...\"}]} using only names present in the transcript.\n\n"
                + text.substring(0, Math.min(text.length(), MAX_AGENT_PROMPT_CHARS));

        AgentTurnResult result = client.prologRlmTurn(new AgentTurnRequest(
                prompt,
                List.of(),
                new java.util.HashSet<>(Arrays.asList("document.search", "document.read", "actor.list")),
                new AgentBudget()));
        int queued = queueAgentEntities(store, capture, result.getAnswer());
        return String.format(Locale.US, "Agent turn %s · %d candidate documents queued",
                result.getStatus(), queued);
    }

    static boolean advertisesAgent(List<Endpoint> endpoints) {
        for (Endpoint endpoint : endpoints) {
            if (AGENT_PATH.equals(endpoint.getPath())) return true;
        }
        return false;
    }

    private static int queueAgentEntities(
            StarWirelessStore store, StarWirelessStore.CaptureRow capture, String answer) {
        JSONObject parsed;
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("Agent answer contained no JSON object");
        try {
            parsed = new JSONObject(answer.substring(start, end + 1));
        } catch (org.json.JSONException failure) {
            throw new IllegalArgumentException("Agent answer JSON was malformed", failure);
        }

        String dataset = DocumentProjection.datasetForCapture(capture.createdAtMs);
        String analysisId = StarDocumentFactory.deterministicId(
                dataset, "analysis", fileIdFor(capture), "agent-refinement:" + capture.eventKey);
        int queued = 0;
        JSONArray people = parsed.optJSONArray("people");
        if (people != null) {
            for (int index = 0; index < Math.min(people.length(), 64); index++) {
                String name = people.optString(index, "").trim();
                if (name.isEmpty()) continue;
                String personDoc = StarDocumentFactory.personDocument(dataset, name, analysisId);
                String personRelation = relationDoc(dataset, personDoc, analysisId, name, 0.6d, "prolog-rlm agent");
                store.enqueueDocument(idOf(personDoc), "person", personDoc);
                store.enqueueDocument(idOf(personRelation), "relation", personRelation);
                queued += 2;
            }
        }
        JSONArray orgs = parsed.optJSONArray("orgs");
        if (orgs != null) {
            for (int index = 0; index < Math.min(orgs.length(), 64); index++) {
                String name = orgs.optString(index, "").trim();
                if (name.isEmpty()) continue;
                String orgDoc = StarDocumentFactory.orgDocument(dataset, name, analysisId);
                String orgRelation = relationDoc(dataset, orgDoc, analysisId, name, 0.6d, "prolog-rlm agent");
                store.enqueueDocument(idOf(orgDoc), "org", orgDoc);
                store.enqueueDocument(idOf(orgRelation), "relation", orgRelation);
                queued += 2;
            }
        }
        return queued;
    }

    private static String idOf(String documentJson) {
        try {
            return new JSONObject(documentJson).optString("_id");
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Could not read document id", failure);
        }
    }

    private static String relationDoc(String dataset, String entityJson,
            String analysisId, String label, double confidence, String note) {
        return StarDocumentFactory.relationDocument(
                dataset, idOf(entityJson), "mentioned-in", analysisId, confidence, note + " · " + label);
    }

    private static String fileIdFor(StarWirelessStore.CaptureRow capture) {
        return StarDocumentFactory.deterministicId(
                DocumentProjection.datasetForCapture(capture.createdAtMs),
                "file", capture.eventKey + ".wav", capture.sha256);
    }
}
