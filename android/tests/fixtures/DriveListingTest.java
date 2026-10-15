package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline regression for unreadable committed generations hiding a valid older restore. */
public final class DriveListingTest extends Instrumentation {
    private static final String TOKEN = "fixture-public-listing-token";
    private static final String NAME = "VoiceFlow-backup-1700000000000-0123456789abcdef0123456789abcdef.vfb";
    private int checks;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            regression();
            report.putString("result", "PASS: " + checks + " offline rejected-generation discovery checks; no Google account, network, or local data changes");
        } catch (Throwable failure) { report.putString("failure", android.util.Log.getStackTraceString(failure)); }
        finish(report.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, report);
    }

    private void regression() throws Exception {
        JSONObject older = metadata("older-valid", "2023-11-14T22:13:20.000Z", "1", 128);
        JSONObject empty = metadata("newer-empty", "2023-11-15T22:13:20.000Z", "1", 0);
        JSONObject future = metadata("newer-version", "2023-11-15T22:13:20.000Z", "2", 128);
        for (JSONObject invalid : new JSONObject[]{empty, future}) {
            FakeTransport transport = new FakeTransport(page(new JSONArray().put(invalid).put(older)));
            DriveCloudClient client = new DriveCloudClient(TOKEN, transport);
            DriveCloudClient.Listing listing = client.listing();
            require(listing.files.size() == 1 && "older-valid".equals(listing.files.get(0).id), "Unreadable newer generation hid the older restore choice");
            require(listing.rejected.size() == 1 && listing.hasRejected(), "Rejected generation was silently treated as absent");
            DriveCloudClient.RejectedBackup rejected = listing.rejected.get(0);
            require(rejected.id.equals(invalid.getString("id")) && rejected.name.equals(NAME)
                    && rejected.modifiedTime.equals(invalid.getString("modifiedTime")), "Safe rejected-generation metadata was lost");
            require(rejected.size == Long.parseLong(invalid.getString("size")), "Rejected size did not preserve the zero-size reproduction");
            require(rejected.reason == (invalid == empty ? DriveCloudClient.RejectionReason.INVALID_SIZE
                    : DriveCloudClient.RejectionReason.UNSUPPORTED_VERSION), "Rejected generation has the wrong typed reason");
            require(!listing.warning().isEmpty() && listing.warning().contains("older valid backup")
                    && listing.warning().contains("paused"), "Discovery warning did not explain recovery and write blocking");
            Exception strict = reject(client::list, "Legacy list() silently filtered the rejected generation");
            require(strict instanceof DriveCloudClient.RejectedBackups, "Legacy list() lost its structured rejection result");
            DriveCloudClient.RejectedBackups failure = (DriveCloudClient.RejectedBackups) strict;
            require(failure.listing.files.size() == 1 && failure.listing.hasRejected(), "Strict caller cannot inspect valid older recovery choices");
            require(failure instanceof DriveCloudClient.ProtocolFailure, "Rejected listing is incorrectly retryable as a network failure");
            reject(() -> listing.files.clear(), "Valid restore choices are mutable");
            reject(() -> listing.rejected.clear(), "Rejected generation warnings are mutable");
            verifyRequests(transport);
        }

        FakeTransport control = new FakeTransport(page(new JSONArray().put(older)));
        DriveCloudClient client = new DriveCloudClient(TOKEN, control);
        DriveCloudClient.Listing clean = client.listing();
        require(clean.files.size() == 1 && !clean.hasRejected() && clean.warning().isEmpty(), "Control listing gained an unnecessary warning");
        require(client.list().size() == 1, "Legacy list() rejected an entirely valid backup set");
        verifyRequests(control);

        FakeTransport onlyRejected = new FakeTransport(page(new JSONArray().put(empty)));
        DriveCloudClient.Listing blocked = new DriveCloudClient(TOKEN, onlyRejected).listing();
        require(blocked.files.isEmpty() && blocked.hasRejected() && !blocked.warning().isEmpty(), "Only rejected files looked like a new empty account");
        require(!blocked.warning().contains("older valid"), "Warning offered an older backup when none exists");
        require(reject(() -> new DriveCloudClient(TOKEN, onlyRejected).list(), "Rejected-only account was returned as empty")
                instanceof DriveCloudClient.RejectedBackups, "Rejected-only account lost its permanent typed warning");

        FakeTransport malformed = new FakeTransport("{\"files\":[");
        require(reject(() -> new DriveCloudClient(TOKEN, malformed).listing(), "Malformed discovery JSON was tolerated")
                instanceof DriveCloudClient.ProtocolFailure, "Malformed discovery JSON lost permanent failure classification");
        FakeTransport nonObject = new FakeTransport(page(new JSONArray().put(older).put("not-metadata")));
        reject(() -> new DriveCloudClient(TOKEN, nonObject).listing(), "Malformed list envelope was tolerated per generation");
        FakeTransport incomplete = new FakeTransport(new JSONObject().put("files", new JSONArray().put(older))
                .put("incompleteSearch", true).toString());
        reject(() -> new DriveCloudClient(TOKEN, incomplete).listing(), "Incomplete discovery returned a partial restore set");
        FakeTransport repeating = new FakeTransport(new JSONObject().put("files", new JSONArray().put(older))
                .put("nextPageToken", "repeat-token").toString());
        reject(() -> new DriveCloudClient(TOKEN, repeating).listing(), "Repeating pagination returned a partial restore set");
        require(repeating.connections.size() == 2, "Repeated pagination was not bounded promptly");

        for (String drift : new String[]{"foreign", "pending", "mime", "trashed"}) {
            JSONObject value = metadata("query-drift", "2023-11-15T22:13:20.000Z", "1", 128);
            if ("foreign".equals(drift)) value.getJSONObject("appProperties").put("app", "other");
            if ("pending".equals(drift)) value.getJSONObject("appProperties").put("state", "pending");
            if ("mime".equals(drift)) value.put("mimeType", "text/plain");
            if ("trashed".equals(drift)) value.put("trashed", true);
            FakeTransport response = new FakeTransport(page(new JSONArray().put(value).put(older)));
            require(reject(() -> new DriveCloudClient(TOKEN, response).listing(), "Query scope drift was accepted as a file warning")
                    instanceof DriveCloudClient.ProtocolFailure, "Query scope drift lost strict discovery failure");
        }

        JSONObject malformedDetails = metadata("bad/name", "private unsafe date", "1", 128).put("name", "private remote text");
        DriveCloudClient.RejectedBackup sanitized = new DriveCloudClient(TOKEN,
                new FakeTransport(page(new JSONArray().put(malformedDetails).put(older)))).listing().rejected.get(0);
        require(sanitized.id.isEmpty() && sanitized.name.isEmpty() && sanitized.modifiedTime.isEmpty(), "Malformed remote details reached warning metadata");
        require(!sanitized.message.contains("private remote text") && !sanitized.message.contains("private unsafe date"), "Warning included raw malformed server text");
    }

    private void verifyRequests(FakeTransport transport) throws Exception {
        for (MockConnection request : transport.connections) {
            require("https".equals(request.getURL().getProtocol()) && "www.googleapis.com".equals(request.getURL().getHost()), "Discovery escaped Google's HTTPS endpoint");
            require("GET".equals(request.getRequestMethod()) && request.getURL().getPath().equals("/drive/v3/files"), "Discovery mutated a remote generation");
            require(!request.getInstanceFollowRedirects() && request.disconnected, "Discovery followed redirects or leaked connections");
            require(("Bearer " + TOKEN).equals(request.getRequestProperty("Authorization"))
                    && !request.getURL().toExternalForm().contains(TOKEN), "Authorization was absent or leaked into a URL");
            require("appDataFolder".equals(query(request.getURL(), "spaces")), "Discovery queried outside hidden app data");
            String filter = query(request.getURL(), "q");
            require(filter.contains("voiceflow") && filter.contains("committed") && filter.contains("application/octet-stream")
                    && !filter.toLowerCase(java.util.Locale.ROOT).contains("schema"), "Discovery query silently excluded newer-schema generations");
        }
    }

    private static JSONObject metadata(String id, String modified, String schema, long size) throws Exception {
        return new JSONObject().put("id", id).put("name", NAME).put("modifiedTime", modified).put("size", Long.toString(size))
                .put("mimeType", "application/octet-stream").put("trashed", false)
                .put("appProperties", new JSONObject().put("app", "voiceflow").put("state", "committed")
                        .put("schema", schema).put("encrypted", "vfb"));
    }
    private static String page(JSONArray files) throws Exception { return new JSONObject().put("files", files).toString(); }
    private static String query(URL url, String name) throws Exception {
        for (String pair : url.getQuery().split("&")) {
            String[] pieces = pair.split("=", 2);
            if (URLDecoder.decode(pieces[0], "UTF-8").equals(name)) return pieces.length == 2 ? URLDecoder.decode(pieces[1], "UTF-8") : "";
        }
        return "";
    }
    private interface Action { void run() throws Exception; }
    private Exception reject(Action action, String message) throws Exception {
        try { action.run(); } catch (Exception failure) { checks++; return failure; }
        throw new AssertionError(message);
    }
    private void require(boolean okay, String message) { checks++; if (!okay) throw new AssertionError(message); }

    private static final class FakeTransport implements DriveCloudClient.Transport {
        final String json;
        final List<MockConnection> connections = new ArrayList<>();
        FakeTransport(String json) { this.json = json; }
        public HttpsURLConnection open(URL url) throws IOException {
            MockConnection connection = new MockConnection(url, json.getBytes(StandardCharsets.UTF_8));
            connections.add(connection); return connection;
        }
    }
    private static final class MockConnection extends HttpsURLConnection {
        final byte[] bytes;
        boolean disconnected;
        MockConnection(URL url, byte[] bytes) { super(url); this.bytes = bytes; }
        @Override public int getResponseCode() { return 200; }
        @Override public long getContentLengthLong() { return bytes.length; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void disconnect() { disconnected = true; }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public String getCipherSuite() { return "fixture"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return new Certificate[0]; }
    }
}
