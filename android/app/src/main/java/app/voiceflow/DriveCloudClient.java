package app.voiceflow;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Short-lived, appDataFolder-only Drive v3 transport. Access tokens stay in memory. */
final class DriveCloudClient {
    private static final String ORIGIN = "https://www.googleapis.com";
    private static final String FILE_FIELDS = "id,name,modifiedTime,size,mimeType,appProperties,trashed";
    private static final String MIME = "application/octet-stream";
    private static final String QUERY = "trashed = false and mimeType = 'application/octet-stream'"
            + " and appProperties has { key='app' and value='voiceflow' }"
            + " and appProperties has { key='state' and value='committed' }";
    private static final int JSON_LIMIT = 512 * 1024;
    private static final int ERROR_LIMIT = 8192;
    private static final int PAGE_LIMIT = 100;
    private static final int FILE_LIMIT = 10000;
    private static final int MIN_ARCHIVE_BYTES = 64;
    private static final byte[] ARCHIVE_MAGIC = {'V', 'F', 'L', 'O', 'W', 'B', 'K', 'P'};

    static final class Profile {
        final String email, name, permissionId;
        Profile(String email, String name, String permissionId) {
            this.email = email; this.name = name; this.permissionId = permissionId;
        }
    }

    static final class BackupFile {
        final String id, name, modifiedTime;
        final long size;
        BackupFile(String id, String name, String modifiedTime, long size) {
            this.id = id; this.name = name; this.modifiedTime = modifiedTime; this.size = size;
        }
    }

    enum RejectionReason { UNSUPPORTED_VERSION, INVALID_SIZE, INVALID_METADATA }

    /** Contains only safe metadata; malformed remote fields are never forwarded into the UI. */
    static final class RejectedBackup {
        final String id, name, modifiedTime, message;
        final long size;
        final RejectionReason reason;
        private RejectedBackup(JSONObject value, RejectionReason reason) {
            String possibleId = value.opt("id") instanceof String ? (String) value.opt("id") : "";
            String possibleName = value.opt("name") instanceof String ? (String) value.opt("name") : "";
            String possibleDate = value.opt("modifiedTime") instanceof String ? (String) value.opt("modifiedTime") : "";
            this.id = validId(possibleId) ? possibleId : "";
            this.name = validName(possibleName) ? possibleName : "";
            this.modifiedTime = validDate(possibleDate) ? possibleDate : "";
            this.size = safeSize(value);
            this.reason = reason;
            this.message = reason == RejectionReason.UNSUPPORTED_VERSION
                    ? "This backup uses a version that this app cannot read."
                    : reason == RejectionReason.INVALID_SIZE
                    ? "This backup is incomplete or too large to restore."
                    : "This backup has invalid file details and cannot be restored.";
        }
    }

    /** Discovery is complete only when every managed generation has readable supported metadata. */
    static final class Listing {
        final List<BackupFile> files;
        final List<RejectedBackup> rejected;
        private Listing(List<BackupFile> files, List<RejectedBackup> rejected) {
            this.files = Collections.unmodifiableList(new ArrayList<>(files));
            this.rejected = Collections.unmodifiableList(new ArrayList<>(rejected));
        }
        boolean hasRejected() { return !rejected.isEmpty(); }
        String warning() {
            if (!hasRejected()) return "";
            String count = rejected.size() == 1 ? "1 Google Drive backup cannot" : rejected.size() + " Google Drive backups cannot";
            return count + " be read by this version of VonoTation. "
                    + (files.isEmpty() ? "" : "You can restore an older valid backup. ")
                    + "New backups are paused to protect existing data.";
        }
    }

    /** HTTP diagnostics contain only a status and an app-owned message, never server bodies. */
    static final class HttpFailure extends IOException {
        private static final long serialVersionUID = 1L;
        final int status;
        final boolean retryable, requiresReconnect;
        HttpFailure(int status, boolean retryable) {
            this(status, retryable, status == 401 ? FailureKind.PERMISSION : FailureKind.GENERAL);
        }
        private HttpFailure(int status, boolean retryable, FailureKind kind) {
            super(httpMessage(status, retryable, kind));
            this.status = status; this.retryable = retryable;
            this.requiresReconnect = status == 401 || (status == 403 && !retryable && kind == FailureKind.PERMISSION);
        }
    }

    private enum FailureKind { GENERAL, PERMISSION, STORAGE, SETUP, DAILY_LIMIT, ACCOUNT_POLICY }

    /** Invalid metadata/archive responses need user review rather than automatic retries. */
    static class ProtocolFailure extends IOException {
        private static final long serialVersionUID = 1L;
        ProtocolFailure(String message) { super(message); }
    }

    /** Strict legacy callers must acknowledge rejected generations rather than infer an empty account. */
    static final class RejectedBackups extends ProtocolFailure {
        private static final long serialVersionUID = 1L;
        final Listing listing;
        RejectedBackups(Listing listing) { super(listing.warning()); this.listing = listing; }
    }

    /** Package-private injection keeps offline tests on the actual request implementation. */
    interface Transport { HttpsURLConnection open(URL url) throws IOException; }
    private final String token;
    private final Transport transport;

    DriveCloudClient(String accessToken) {
        this(accessToken, url -> (HttpsURLConnection) url.openConnection());
    }

    DriveCloudClient(String accessToken, Transport transport) {
        if (accessToken == null || accessToken.isEmpty() || accessToken.length() > 8192 || transport == null)
            throw new IllegalArgumentException("Google Drive authorization is missing or invalid.");
        for (int i = 0; i < accessToken.length(); i++) {
            char value = accessToken.charAt(i);
            if (value <= 32 || value >= 127)
                throw new IllegalArgumentException("Google Drive authorization is missing or invalid.");
        }
        this.token = accessToken;
        this.transport = transport;
    }

    Profile profile() throws IOException {
        JSONObject user = json(request("GET", "/drive/v3/about?fields="
                + encode("user(emailAddress,displayName,permissionId)"), null, null, JSON_LIMIT)).optJSONObject("user");
        if (user == null) throw invalid("Google Drive did not identify the connected account.");
        String email = text(user, "emailAddress", 320, false);
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1 || !email.equals(email.trim()))
            throw invalid("Google Drive did not identify the connected account.");
        for (int i = 0; i < email.length(); i++)
            if (Character.isWhitespace(email.charAt(i))) throw invalid("Google Drive did not identify the connected account.");
        String permissionId = text(user, "permissionId", 256, false);
        if (!validId(permissionId)) throw invalid("Google Drive did not identify the connected account.");
        String name = text(user, "displayName", 512, true);
        return new Profile(email, name.isEmpty() ? email : name, permissionId);
    }

    /** Compatibility API remains strict; use listing() to show older restore choices with a warning. */
    List<BackupFile> list() throws IOException {
        Listing result = listing();
        if (result.hasRejected()) throw new RejectedBackups(result);
        return result.files;
    }

    /** A rejected generation blocks writes, but does not hide otherwise valid older restore choices. */
    Listing listing() throws IOException {
        List<BackupFile> files = new ArrayList<>();
        List<RejectedBackup> rejected = new ArrayList<>();
        Set<String> ids = new HashSet<>(), rejectedIds = new HashSet<>(), tokens = new HashSet<>();
        int inspected = 0;
        String pageToken = "";
        for (int page = 0; page < PAGE_LIMIT; page++) {
            checkInterrupted();
            String path = "/drive/v3/files?spaces=appDataFolder&pageSize=100&orderBy=" + encode("modifiedTime desc")
                    + "&q=" + encode(QUERY) + "&fields=" + encode("nextPageToken,incompleteSearch,files(" + FILE_FIELDS + ")");
            if (!pageToken.isEmpty()) path += "&pageToken=" + encode(pageToken);
            JSONObject response = json(request("GET", path, null, null, JSON_LIMIT));
            if (response.optBoolean("incompleteSearch", false))
                throw invalid("Google Drive could not finish checking existing backups. Please try again.");
            JSONArray values = response.optJSONArray("files");
            if (response.has("files") && values == null) throw invalid("Google Drive returned invalid backup details.");
            if (values != null) {
                if (values.length() > 1000) throw invalid("Google Drive returned too many backup details.");
                for (int i = 0; i < values.length(); i++) {
                    checkInterrupted();
                    if (++inspected > FILE_LIMIT) throw invalid("There are too many VonoTation backups. Please try again.");
                    JSONObject value = values.optJSONObject(i);
                    if (value == null) throw invalid("Google Drive returned invalid backup details.");
                    requireCommittedCandidate(value);
                    try {
                        Managed managed = managed(value);
                        if (!rejectedIds.contains(managed.file.id) && ids.add(managed.file.id)) files.add(managed.file);
                    } catch (ProtocolFailure failure) {
                        RejectedBackup item = rejected(value);
                        if (item.id.isEmpty() || rejectedIds.add(item.id)) rejected.add(item);
                        if (!item.id.isEmpty()) {
                            ids.remove(item.id);
                            files.removeIf(file -> item.id.equals(file.id));
                        }
                    }
                }
            }
            pageToken = text(response, "nextPageToken", 4096, true);
            if (pageToken.isEmpty()) {
                Collections.sort(files, (first, second) -> {
                    int compared = Instant.parse(second.modifiedTime).compareTo(Instant.parse(first.modifiedTime));
                    return compared != 0 ? compared : first.id.compareTo(second.id);
                });
                return new Listing(files, rejected);
            }
            if (!tokens.add(pageToken)) throw invalid("Google Drive repeated a backup page. Please try again.");
        }
        throw invalid("Google Drive returned too many backup pages. Please try again.");
    }

    byte[] download(BackupFile file) throws IOException {
        validateFile(file);
        byte[] bytes = request("GET", "/drive/v3/files/" + file.id + "?alt=media", null, null, BackupArchive.MAX_BYTES);
        if (bytes.length != file.size) throw invalid("The Google Drive backup is incomplete or was changed. Please try again.");
        validateArchive(bytes);
        return bytes;
    }

    /** Creates new encrypted bytes; it never replaces or removes an existing generation. */
    BackupFile upload(byte[] encrypted) throws IOException {
        validateArchive(encrypted);
        checkInterrupted();
        String name = "VonoTation-backup-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().replace("-", "") + ".vfb";
        String boundary = "voiceflow_" + UUID.randomUUID().toString().replace("-", "");
        JSONObject metadata = new JSONObject();
        try {
            metadata.put("name", name).put("mimeType", MIME).put("parents", new JSONArray().put("appDataFolder"))
                    .put("appProperties", properties("pending"));
        } catch (JSONException impossible) { throw invalid("Could not prepare the Google Drive backup."); }
        byte[] prefix = ("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                + metadata + "\r\n--" + boundary + "\r\nContent-Type: " + MIME + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        Managed result = managed(json(request("POST", "/upload/drive/v3/files?uploadType=multipart&fields=" + encode(FILE_FIELDS),
                "multipart/related; boundary=" + boundary, new byte[][]{prefix, encrypted, suffix}, JSON_LIMIT)));
        if (!name.equals(result.file.name) || result.file.size != encrypted.length || !"pending".equals(result.state))
            throw invalid("Google Drive did not acknowledge the new backup correctly. Previous backups are safe.");
        return result.file;
    }

    /** Call only after download and encrypted-byte verification by the backup orchestrator. */
    BackupFile commit(String id) throws IOException {
        Managed previous = metadata(id);
        if ("committed".equals(previous.state)) return previous.file;
        JSONObject body = new JSONObject();
        try { body.put("appProperties", properties("committed")); }
        catch (JSONException impossible) { throw invalid("Could not acknowledge the Google Drive backup."); }
        Managed updated = managed(json(request("PATCH", "/drive/v3/files/" + id + "?fields=" + encode(FILE_FIELDS),
                "application/json; charset=UTF-8", new byte[][]{body.toString().getBytes(StandardCharsets.UTF_8)}, JSON_LIMIT)));
        if (!"committed".equals(updated.state) || !id.equals(updated.file.id)
                || !previous.file.name.equals(updated.file.name) || previous.file.size != updated.file.size)
            throw invalid("Google Drive did not confirm the saved backup. Previous backups are safe.");
        return updated.file;
    }

    /** Explicit retention or abandoned-upload cleanup only, after validating app-owned metadata. */
    void delete(String id) throws IOException {
        metadata(id);
        request("DELETE", "/drive/v3/files/" + id, null, null, JSON_LIMIT);
    }

    private Managed metadata(String id) throws IOException {
        if (!validId(id)) throw invalid("The Google Drive backup ID is invalid.");
        Managed result = managed(json(request("GET", "/drive/v3/files/" + id + "?fields=" + encode(FILE_FIELDS), null, null, JSON_LIMIT)));
        if (!id.equals(result.file.id)) throw invalid("Google Drive returned different backup details.");
        return result;
    }

    private static JSONObject properties(String state) throws JSONException {
        return new JSONObject().put("app", "voiceflow").put("schema", "1").put("encrypted", "vfb").put("state", state);
    }

    private static final class Managed {
        final BackupFile file;
        final String state;
        Managed(BackupFile file, String state) { this.file = file; this.state = state; }
    }

    private static Managed managed(JSONObject value) throws IOException {
        JSONObject properties = value.optJSONObject("appProperties");
        if (properties == null || !"voiceflow".equals(properties.optString("app", ""))
                || !"vfb".equals(properties.optString("encrypted", "")) || !MIME.equals(value.optString("mimeType", ""))
                || value.optBoolean("trashed", false))
            throw invalid("This is not an encrypted VonoTation Google Drive backup.");
        if (!"1".equals(properties.optString("schema", "")))
            throw invalid("This Google Drive backup version is not supported by this app.");
        String state = properties.optString("state", "");
        if (!"pending".equals(state) && !"committed".equals(state))
            throw invalid("Google Drive returned invalid backup status.");
        String size = text(value, "size", 20, false);
        if (!size.matches("[0-9]{1,20}")) throw invalid("Google Drive returned an invalid backup size.");
        long length;
        try { length = Long.parseLong(size); }
        catch (NumberFormatException invalid) { throw invalid("Google Drive returned an invalid backup size."); }
        BackupFile file = new BackupFile(text(value, "id", 256, false), text(value, "name", 160, false),
                text(value, "modifiedTime", 64, false), length);
        validateFile(file);
        return new Managed(file, state);
    }

    /** A response outside the fixed query is a discovery failure, never a tolerable file error. */
    private static void requireCommittedCandidate(JSONObject value) throws IOException {
        JSONObject properties = value.optJSONObject("appProperties");
        if (properties == null || !"voiceflow".equals(properties.optString("app", ""))
                || !"committed".equals(properties.optString("state", ""))
                || !MIME.equals(value.optString("mimeType", "")) || value.optBoolean("trashed", false)
                || (value.has("trashed") && !(value.opt("trashed") instanceof Boolean)))
            throw invalid("Google Drive returned backup details outside the requested app-data scope.");
    }

    private static RejectedBackup rejected(JSONObject value) {
        JSONObject properties = value.optJSONObject("appProperties");
        Object schema = properties == null ? null : properties.opt("schema");
        RejectionReason reason;
        if (schema instanceof String && !"1".equals(schema)) reason = RejectionReason.UNSUPPORTED_VERSION;
        else if (safeSize(value) < MIN_ARCHIVE_BYTES || safeSize(value) > BackupArchive.MAX_BYTES)
            reason = RejectionReason.INVALID_SIZE;
        else reason = RejectionReason.INVALID_METADATA;
        return new RejectedBackup(value, reason);
    }

    private static long safeSize(JSONObject value) {
        Object raw = value.opt("size");
        if (!(raw instanceof String) || !((String) raw).matches("[0-9]{1,20}")) return -1;
        try { return Long.parseLong((String) raw); }
        catch (NumberFormatException invalid) { return -1; }
    }

    private static void validateFile(BackupFile file) throws IOException {
        if (file == null || !validId(file.id) || !validName(file.name))
            throw invalid("Google Drive returned invalid VonoTation backup details.");
        if (file.size < MIN_ARCHIVE_BYTES || file.size > BackupArchive.MAX_BYTES)
            throw invalid("The Google Drive backup is incomplete or too large (maximum 8 MB).");
        if (!validDate(file.modifiedTime)) throw invalid("Google Drive returned an invalid backup date.");
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,256}");
    }

    private static boolean validName(String name) {
        return name != null && name.matches("(VonoTation|VoiceFlow)-backup-[0-9]{1,17}-[a-f0-9]{32}\\.vfb");
    }

    private static boolean validDate(String date) {
        if (date == null || date.length() > 64) return false;
        try { Instant.parse(date); return true; }
        catch (DateTimeParseException invalid) { return false; }
    }

    private static void validateArchive(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < MIN_ARCHIVE_BYTES || bytes.length > BackupArchive.MAX_BYTES)
            throw invalid("The encrypted backup is incomplete or too large (maximum 8 MB).");
        for (int i = 0; i < ARCHIVE_MAGIC.length; i++)
            if (bytes[i] != ARCHIVE_MAGIC[i]) throw invalid("Only encrypted VonoTation archives can be sent to Google Drive.");
    }

    private byte[] request(String method, String path, String contentType, byte[][] parts, int limit) throws IOException {
        checkInterrupted();
        URL url = new URL(ORIGIN + path);
        if (!"https".equals(url.getProtocol()) || !"www.googleapis.com".equals(url.getHost()) || url.getPort() != -1
                || url.getUserInfo() != null || url.getRef() != null || !path.startsWith("/"))
            throw invalid("The Google Drive request address is invalid.");
        HttpsURLConnection connection = null;
        try {
            connection = transport.open(url);
            if (connection == null || !url.toExternalForm().equals(connection.getURL().toExternalForm()))
                throw invalid("The Google Drive request address is invalid.");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(60000);
            connection.setUseCaches(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", path.endsWith("?alt=media") ? MIME : "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (parts != null) {
                long length = 0;
                for (byte[] part : parts) length += part.length;
                if (length > BackupArchive.MAX_BYTES + 8192L) throw invalid("The Google Drive upload is too large.");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType);
                connection.setFixedLengthStreamingMode(length);
                try (OutputStream out = connection.getOutputStream()) {
                    for (byte[] part : parts) {
                        for (int offset = 0; offset < part.length; offset += 16384) {
                            checkInterrupted();
                            out.write(part, offset, Math.min(16384, part.length - offset));
                        }
                    }
                    checkInterrupted();
                    out.flush();
                }
            }
            checkInterrupted();
            int status = connection.getResponseCode();
            checkInterrupted();
            if (status < 200 || status >= 300) throw httpFailure(connection, status);
            if ("DELETE".equals(method)) {
                if (status != 204) throw invalid("Google Drive did not confirm backup cleanup.");
                return new byte[0];
            }
            if (status != 200 && !("POST".equals(method) && status == 201))
                throw invalid("Google Drive returned an unexpected response.");
            long contentLength = connection.getContentLengthLong();
            if (contentLength > limit) throw invalid(limit == BackupArchive.MAX_BYTES
                    ? "The Google Drive backup is too large (maximum 8 MB)." : "Google Drive returned too much backup information.");
            try (InputStream in = connection.getInputStream()) { return read(in, limit); }
        } catch (HttpFailure | ProtocolFailure failure) { throw failure; }
        catch (SocketTimeoutException timeout) {
            if (Thread.currentThread().isInterrupted()) throw interrupted();
            throw new IOException("Google Drive timed out. Check your connection and try again.");
        } catch (InterruptedIOException interrupted) {
            throw interrupted();
        } catch (IOException failure) {
            // Provider exceptions can include URLs or private body contents. Do not forward them.
            if (Thread.currentThread().isInterrupted()) throw interrupted();
            throw new IOException("Could not reach Google Drive. Check your connection and try again.");
        } finally { if (connection != null) connection.disconnect(); }
    }

    private static HttpFailure httpFailure(HttpsURLConnection connection, int status) throws IOException {
        boolean retryable = status == 408 || status == 429 || status >= 500;
        FailureKind kind = status == 401 ? FailureKind.PERMISSION : FailureKind.GENERAL;
        try (InputStream error = connection.getErrorStream()) {
            if (error != null && status == 403) {
                try {
                    JSONObject body = json(read(error, ERROR_LIMIT)).optJSONObject("error");
                    JSONArray errors = body == null ? null : body.optJSONArray("errors");
                    for (int i = 0; errors != null && i < errors.length(); i++) {
                        JSONObject entry = errors.optJSONObject(i);
                        String reason = entry == null ? "" : entry.optString("reason", "");
                        if ("rateLimitExceeded".equals(reason) || "userRateLimitExceeded".equals(reason)) retryable = true;
                        else if ("insufficientPermissions".equals(reason) || "insufficientFilePermissions".equals(reason)
                                || "authError".equals(reason)) kind = FailureKind.PERMISSION;
                        else if ("storageQuotaExceeded".equals(reason)) kind = FailureKind.STORAGE;
                        else if ("accessNotConfigured".equals(reason) || "serviceDisabled".equals(reason)) kind = FailureKind.SETUP;
                        else if ("dailyLimitExceeded".equals(reason)) kind = FailureKind.DAILY_LIMIT;
                        else if ("domainPolicy".equals(reason)) kind = FailureKind.ACCOUNT_POLICY;
                    }
                } catch (InterruptedIOException interrupted) { throw interrupted; }
                catch (IOException ignored) { /* A malformed error never changes the public message. */ }
            }
        } catch (InterruptedIOException interrupted) { throw interrupted; }
        catch (IOException ignored) { }
        return new HttpFailure(status, retryable, kind);
    }

    private static String httpMessage(int status, boolean retryable, FailureKind kind) {
        if (status == 401) return "Google Drive authorization expired. Reconnect your Google account.";
        if (status == 403 && !retryable) {
            if (kind == FailureKind.PERMISSION) return "Google Drive backup permission is missing. Reconnect your Google account and allow backup access.";
            if (kind == FailureKind.STORAGE) return "Your Google Drive storage is full. Free up space and try again.";
            if (kind == FailureKind.SETUP) return "Google Drive API is not available for this app. Check its Google Cloud setup.";
            if (kind == FailureKind.DAILY_LIMIT) return "Google Drive's daily request limit was reached. Please try again tomorrow.";
            if (kind == FailureKind.ACCOUNT_POLICY) return "This Google account's organization does not allow VonoTation backups. Try another account.";
            return "Google Drive denied this backup request (HTTP 403). Check its connection and try again.";
        }
        if (status == 404) return "This Google Drive backup is no longer available.";
        if (status == 429 || (status == 403 && retryable)) return "Google Drive is limiting requests. Please try again later.";
        if (status >= 300 && status < 400) return "Google Drive redirected the request. No authorization was sent to the new address.";
        if (retryable) return "Google Drive is temporarily unavailable (HTTP " + status + "). Please try again later.";
        return "Google Drive rejected the backup request (HTTP " + status + "). Please try again.";
    }

    private static byte[] read(InputStream in, int limit) throws IOException {
        if (in == null) throw invalid("Google Drive returned an empty response.");
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 16384));
        byte[] buffer = new byte[16384];
        while (true) {
            checkInterrupted();
            int count = in.read(buffer);
            if (count == -1) break;
            if (count > limit - out.size()) throw invalid(limit == BackupArchive.MAX_BYTES
                    ? "The Google Drive backup is too large (maximum 8 MB)." : "Google Drive returned too much backup information.");
            out.write(buffer, 0, count);
        }
        checkInterrupted();
        return out.toByteArray();
    }

    private static JSONObject json(byte[] bytes) throws IOException {
        try {
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            int depth = 0, fields = 0;
            boolean quoted = false, escaped = false;
            for (int i = 0; i < value.length(); i++) {
                char next = value.charAt(i);
                if (quoted) {
                    if (escaped) escaped = false;
                    else if (next == '\\') escaped = true;
                    else if (next == '"') quoted = false;
                } else if (next == '"') quoted = true;
                else if (next == '{' || next == '[') { if (++depth > 16) throw invalid("Google Drive returned invalid backup information."); }
                else if (next == '}' || next == ']') { if (--depth < 0) throw invalid("Google Drive returned invalid backup information."); }
                else if (next == ',' && ++fields > 20000) throw invalid("Google Drive returned too much backup information.");
            }
            if (depth != 0 || quoted || !value.trim().startsWith("{") || !value.trim().endsWith("}"))
                throw invalid("Google Drive returned invalid backup information.");
            return new JSONObject(value);
        } catch (JSONException | CharacterCodingException failure) { throw invalid("Google Drive returned invalid backup information."); }
    }

    private static String text(JSONObject value, String key, int max, boolean optional) throws IOException {
        if (!value.has(key)) {
            if (optional) return "";
            throw invalid("Google Drive returned incomplete backup information.");
        }
        Object raw = value.opt(key);
        if (!(raw instanceof String)) throw invalid("Google Drive returned invalid backup information.");
        String result = (String) raw;
        if (result.length() > max || (!optional && result.isEmpty())) throw invalid("Google Drive returned invalid backup information.");
        for (int i = 0; i < result.length(); i++)
            if (Character.isISOControl(result.charAt(i))) throw invalid("Google Drive returned invalid backup information.");
        return result;
    }

    private static String encode(String value) throws IOException { return URLEncoder.encode(value, "UTF-8"); }
    private static ProtocolFailure invalid(String message) { return new ProtocolFailure(message); }
    private static InterruptedIOException interrupted() { return new InterruptedIOException("Google Drive backup was interrupted. Please try again."); }
    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw interrupted();
    }
}
