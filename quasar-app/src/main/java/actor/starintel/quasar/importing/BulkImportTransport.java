package actor.starintel.quasar.importing;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Compatibility adapter for the existing StarServerClient/StarIntelClient bulkCreate method.
 * That method already caps HTTP response input at 2 MiB characters and does not retry writes.
 * A future shared-client raw-byte method can replace this adapter without changing checkpoints.
 */
public final class BulkImportTransport implements StreamingImport.Transport {
    public interface BulkCreate { JSONObject send(JSONArray documents) throws Exception; }
    private final BulkCreate client;
    public BulkImportTransport(BulkCreate client) { this.client = client; }
    @Override public byte[] send(byte[] array) throws Exception {
        if (array.length > StreamingImport.MAX_BATCH_BYTES) throw new IOException("Batch exceeds byte bound");
        JSONArray records = new JSONArray(new String(array, StandardCharsets.UTF_8));
        if (records.length() < 1 || records.length() > StreamingImport.MAX_BATCH_RECORDS) throw new IOException("Batch exceeds record bound");
        for (int i = 0; i < records.length(); i++) records.getJSONObject(i);
        JSONObject response = client.send(records); // Exactly one call. Never retry a failed/ambiguous write.
        // Avoid serializing an arbitrarily shaped response tree a second time. Unknown fields do
        // not establish acceptance; only the inspected inline contract's fields can do so.
        JSONObject receipt = new JSONObject();
        for (String key : new String[]{"total", "succeeded", "failed", "correlation_id", "job_id", "status", "status_url"}) {
            if (!response.has(key)) continue;
            Object value = response.get(key);
            if (!(value instanceof Integer || value instanceof Long || value instanceof String) ||
                    value instanceof String && ((String)value).length() > 512) throw new IOException("Unusable bulk receipt");
            receipt.put(key, value);
        }
        byte[] bytes = receipt.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > StreamingImport.MAX_RECEIPT_BYTES) throw new IOException("Receipt exceeds bound");
        return bytes;
    }
}
