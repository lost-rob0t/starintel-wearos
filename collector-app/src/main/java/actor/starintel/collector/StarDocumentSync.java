package actor.starintel.collector;

import actor.starintel.android.api.StarHttpFailure;
import actor.starintel.android.api.StarIntelClient;
import actor.starintel.android.model.Endpoint;
import actor.starintel.android.model.StarSession;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Uploads queued StarIntel documents through the canonical bulk API.
 *
 * Capability-first: bulk ingest must be advertised by the server before any upload.
 * Duplicates (HTTP 409 per document) count as accepted because document ids are
 * content-derived, so a conflict proves an earlier identical upload.
 */
public final class StarDocumentSync {
    public static final String KEY_SERVER_URL = "server_url";
    public static final String SLOT_API_KEY = "star_api_key";
    public static final String KEY_DATASET = "dataset";
    static final int BULK_LIMIT = 500;

    private StarDocumentSync() {}

    public static String sync(StarWirelessStore store, String serverUrl, String apiKey) {
        StarIntelClient client = new StarIntelClient(
                () -> new StarSession(serverUrl, apiKey), "star-wireless/0.4", false);

        List<Endpoint> endpoints = client.capabilities();
        if (!advertises(endpoints, "document_bulk_create_v1")) {
            return "Server does not advertise bulk document ingest; nothing uploaded";
        }

        int accepted = 0;
        int retried = 0;
        int dead = 0;
        while (true) {
            List<StarWirelessStore.QueuedDocument> batch = store.queuedDocuments(BULK_LIMIT);
            if (batch.isEmpty()) break;
            JSONArray documents = new JSONArray();
            for (StarWirelessStore.QueuedDocument document : batch) {
                try {
                    documents.put(new JSONObject(document.docJson));
                } catch (JSONException malformed) {
                    store.markDocument(document.docKey, "dead", "queued json malformed", false);
                    dead++;
                }
            }
            if (documents.length() == 0) break;

            try {
                client.bulkCreate(documents);
                for (StarWirelessStore.QueuedDocument document : batch) {
                    store.markDocument(document.docKey, "accepted", "", false);
                    accepted++;
                }
                if (documents.length() < BULK_LIMIT) break;
            } catch (StarHttpFailure failure) {
                for (StarWirelessStore.QueuedDocument document : batch) {
                    if (failure.getStatus() == 409) {
                        store.markDocument(document.docKey, "accepted", "already present", false);
                        accepted++;
                    } else if (failure.getStatus() >= 400 && failure.getStatus() < 500) {
                        store.markDocument(document.docKey, "dead", failure.getMessage(), false);
                        dead++;
                    } else {
                        store.markDocument(document.docKey, "retry", failure.getMessage(), true);
                        retried++;
                    }
                }
                break;
            } catch (RuntimeException failure) {
                String message = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                for (StarWirelessStore.QueuedDocument document : batch) {
                    store.markDocument(document.docKey, "retry", message, true);
                    retried++;
                }
                break;
            }
        }
        return String.format(Locale.US, "Sync · accepted %d · retry %d · dead %d", accepted, retried, dead);
    }

    static boolean advertises(List<Endpoint> endpoints, String id) {
        for (Endpoint endpoint : endpoints) {
            if (id.equals(endpoint.getId()) && !endpoint.getLegacy()) return true;
        }
        return false;
    }
}
