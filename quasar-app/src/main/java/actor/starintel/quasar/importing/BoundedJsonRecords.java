package actor.starintel.quasar.importing;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Strict JSON grammar over a bounded record buffer. Never reads an unbounded line/string. */
final class BoundedJsonRecords implements Closeable {
    static final class Record {
        final byte[] bytes;
        final long end;
        Record(byte[] bytes, long end) { this.bytes = bytes; this.end = end; }
    }
    private InputStream input;
    private final int limit;
    private final boolean array;
    private long position;
    private int look = -2;
    private boolean afterRecord;
    private boolean done;
    private ByteArrayOutputStream record;

    BoundedJsonRecords(File file, int limit, long offset, long accepted) throws IOException {
        FileInputStream stream = new FileInputStream(file);
        this.input = new BufferedInputStream(stream, 8192);
        this.limit = limit;
        try {
            whitespace();
            array = peek() == '[';
            if (array) take();
            if (accepted > 0) {
                input.close();
                stream = new FileInputStream(file);
                stream.getChannel().position(offset);
                input = new BufferedInputStream(stream, 8192);
                position = offset;
                look = -2;
                afterRecord = true;
            }
        } catch (IOException | RuntimeException e) { input.close(); throw e; }
    }
    static BoundedJsonRecords open(File file, int limit, long offset, long accepted) throws IOException {
        return new BoundedJsonRecords(file, limit, offset, accepted);
    }

    private BoundedJsonRecords(byte[] bytes) {
        input = new ByteArrayInputStream(bytes); limit = bytes.length; array = true;
    }
    static void validateObject(byte[] bytes) throws IOException {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
        try (BoundedJsonRecords reader = new BoundedJsonRecords(bytes)) {
            reader.whitespace();
            if (reader.peek() != '{') throw reader.invalid();
            reader.value(0); reader.whitespace();
            if (reader.peek() != -1) throw reader.invalid();
        }
    }

    Record next() throws IOException {
        if (done) return null;
        if (array) {
            whitespace();
            if (peek() == ']') { take(); finish(); return null; }
            if (afterRecord) { expect(','); whitespace(); }
            if (peek() != '{') throw invalid();
        } else {
            if (afterRecord) {
                while (peek() == ' ' || peek() == '\t' || peek() == '\r') take();
                if (peek() != '\n' && peek() != -1) throw invalid();
            }
            whitespace();
            if (peek() == -1) { done = true; return null; }
            if (peek() != '{') throw invalid();
        }
        record = new ByteArrayOutputStream(Math.min(limit, 8192));
        value(0);
        byte[] bytes = record.toByteArray();
        record = null;
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
        afterRecord = true;
        return new Record(bytes, position);
    }
    private void finish() throws IOException {
        whitespace();
        if (peek() != -1) throw invalid();
        done = true;
    }
    private void value(int depth) throws IOException {
        if (depth > 64) throw new IOException("JSON nesting exceeds 64");
        int c = peek();
        if (c == '{' || c == '[') {
            int close = c == '{' ? '}' : ']';
            boolean object = c == '{';
            take(); whitespace();
            if (peek() == close) { take(); return; }
            while (true) {
                if (object) { string(); whitespace(); expect(':'); whitespace(); }
                value(depth + 1); whitespace();
                if (peek() == close) { take(); return; }
                expect(','); whitespace();
            }
        } else if (c == '"') string();
        else if (c == 't') literal("true");
        else if (c == 'f') literal("false");
        else if (c == 'n') literal("null");
        else number();
    }
    private void string() throws IOException {
        expect('"');
        while (true) {
            int c = take();
            if (c == '"') return;
            if (c < 32) throw invalid();
            if (c == '\\') {
                c = take();
                if (c == 'u') {
                    for (int i = 0; i < 4; i++) {
                        int hex = take();
                        if (!(hex >= '0' && hex <= '9' || hex >= 'a' && hex <= 'f' || hex >= 'A' && hex <= 'F')) throw invalid();
                    }
                } else if (c < 0 || "\"\\/bfnrt".indexOf(c) < 0) throw invalid();
            }
        }
    }
    private void number() throws IOException {
        if (peek() == '-') take();
        if (peek() == '0') take();
        else { if (peek() < '1' || peek() > '9') throw invalid(); digits(); }
        if (peek() == '.') { take(); requireDigit(); digits(); }
        if (peek() == 'e' || peek() == 'E') {
            take(); if (peek() == '+' || peek() == '-') take(); requireDigit(); digits();
        }
    }
    private void requireDigit() throws IOException { if (peek() < '0' || peek() > '9') throw invalid(); }
    private void digits() throws IOException { while (peek() >= '0' && peek() <= '9') take(); }
    private void literal(String text) throws IOException { for (int i = 0; i < text.length(); i++) expect(text.charAt(i)); }
    private void whitespace() throws IOException {
        while (peek() == ' ' || peek() == '\t' || peek() == '\r' || peek() == '\n') {
            // A JSON object in NDJSON must occupy one physical line.
            if (!array && record != null && (peek() == '\r' || peek() == '\n')) throw invalid();
            take();
        }
    }
    private int peek() throws IOException { if (look == -2) look = input.read(); return look; }
    private int take() throws IOException {
        int c = peek(); look = -2;
        if (c >= 0) {
            position++;
            if (record != null) {
                if (record.size() == limit) throw new IOException("Record exceeds UTF-8 byte limit");
                record.write(c);
            }
        }
        return c;
    }
    private void expect(int c) throws IOException { if (take() != c) throw invalid(); }
    private IOException invalid() { return new IOException("Invalid JSON at byte " + position); }
    @Override public void close() throws IOException { input.close(); }
}
