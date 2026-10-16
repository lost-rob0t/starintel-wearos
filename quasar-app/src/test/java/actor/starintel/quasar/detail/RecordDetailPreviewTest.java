package actor.starintel.quasar.detail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RecordDetailPreviewTest {
    static class NoSerialization extends JSONObject {
        @Override public String toString() { throw new AssertionError("Whole object serialized"); }
        @Override public String toString(int indent) { throw new AssertionError("Whole object serialized"); }
    }

    @Test public void largeStringHasBoundedOutputAndScanWork() {
        JSONObject root = new NoSerialization().put("huge", "x".repeat(2_097_152));
        RecordDetailPreview.Result result = RecordDetailPreview.raw(root, () -> false);
        assertTrue(result.truncated);
        assertEquals(RecordDetailPreview.MAX_CHARS, result.text.length());
        assertTrue(result.scannedCharacters <= RecordDetailPreview.MAX_CHARS);
        assertTrue(result.visitedNodes <= RecordDetailPreview.MAX_NODES);
    }

    @Test public void deepInputDoesNotRecursePastBudget() {
        JSONObject root = new NoSerialization();
        JSONObject cursor = root;
        for (int i = 0; i < 10000; i++) {
            JSONObject child = new NoSerialization();
            cursor.put("child", child); cursor = child;
        }
        RecordDetailPreview.Result result = RecordDetailPreview.raw(root, () -> false);
        assertTrue(result.truncated);
        assertEquals(RecordDetailPreview.MAX_DEPTH + 1, result.visitedNodes);
    }

    @Test public void arrayTraversalStopsAtNodeLimit() {
        AtomicInteger reads = new AtomicInteger();
        JSONArray virtualMillion = new JSONArray() {
            @Override public int length() { return 1_000_000; }
            @Override public Object opt(int index) { reads.incrementAndGet(); return 0; }
            @Override public String toString() { throw new AssertionError(); }
        };
        RecordDetailPreview.Result result = RecordDetailPreview.raw(
            new NoSerialization().put("rows", virtualMillion), () -> false);
        assertTrue(result.truncated);
        assertEquals(RecordDetailPreview.MAX_NODES, result.visitedNodes);
        assertTrue(reads.get() <= RecordDetailPreview.MAX_NODES);
    }

    @Test public void fieldsShareOneOutputBudget() {
        JSONObject root = new NoSerialization();
        for (int i = 0; i < 1000; i++) root.put("field" + i, new NoSerialization().put("large", "x".repeat(4096)));
        RecordDetailPreview.Result result = RecordDetailPreview.fields(root, () -> false);
        assertTrue(result.truncated);
        assertTrue(result.text.length() <= RecordDetailPreview.MAX_CHARS);
        assertTrue(result.scannedCharacters <= RecordDetailPreview.MAX_CHARS);
    }

    @Test public void fieldsAlsoHaveCountLimit() {
        JSONObject root = new NoSerialization();
        for (int i = 0; i < 1000; i++) root.put("f" + i, i);
        RecordDetailPreview.Result result = RecordDetailPreview.fields(root, () -> false);
        assertTrue(result.truncated);
        assertEquals(RecordDetailPreview.MAX_FIELDS, result.visitedNodes);
    }

    @Test public void completeSmallJsonEscapesAndRoundTrips() {
        JSONObject root = new NoSerialization().put("text", "\"\\\n\r\t\u0000😀").put("null", JSONObject.NULL)
            .put("number", 12).put("array", new JSONArray().put(true));
        RecordDetailPreview.Result result = RecordDetailPreview.raw(root, () -> false);
        assertFalse(result.truncated);
        JSONObject parsed = new JSONObject(result.text);
        assertEquals(root.getString("text"), parsed.getString("text"));
        assertTrue(parsed.isNull("null"));
        assertTrue(parsed.getJSONArray("array").getBoolean(0));
    }

    @Test public void absentAndContainerSummaryNeverSerialize() {
        JSONObject root = new NoSerialization().put("summary", new NoSerialization()).put("title", "abcdef");
        assertEquals("", RecordDetailPreview.scalar(root, "absent", 100));
        assertEquals("", RecordDetailPreview.scalar(root, "summary", 100));
        assertEquals("abc", RecordDetailPreview.scalar(root, "title", 3));
        assertEquals("No structured fields supplied.", RecordDetailPreview.fields(new JSONObject(), () -> false).text);
    }

    @Test(expected = CancellationException.class) public void cancellationInterruptsStringWork() {
        AtomicInteger checks = new AtomicInteger();
        RecordDetailPreview.raw(new JSONObject().put("large", "x".repeat(100000)), () -> checks.incrementAndGet() > 100);
    }

    @Test public void arbitraryScalarToStringIsNeverCalled() {
        JSONObject root = new JSONObject().put("value", new Object() {
            @Override public String toString() { throw new AssertionError(); }
        });
        assertTrue(RecordDetailPreview.raw(root, () -> false).truncated);
    }
}
