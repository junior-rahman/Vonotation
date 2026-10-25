package app.voiceflow;

import android.accounts.Account;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.util.Log;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.ClearTokenRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Drive authorization only. Tokens remain in memory; account consent is owned by Google. */
final class DriveAuth {
    static final int REQUEST_CODE = 73101;
    static final String SCOPE = "https://www.googleapis.com/auth/drive.appdata";

    interface Callback {
        void ready(String accessToken);
        void failed(Exception error);
    }

    static final class ReconnectException extends IOException {
        ReconnectException() { super("Reconnect Google to allow Voitation backup access."); }
        ReconnectException(String message) { super(message); }
    }

    static final class AuthException extends IOException {
        final boolean retryable;
        /** Short title for the backup row; null for generic failures. */
        final String title;
        AuthException(String message, boolean retryable) { this(null, message, retryable); }
        AuthException(String title, String message, boolean retryable) { super(message); this.retryable = retryable; this.title = title; }
    }

    private static AuthorizationRequest request(String email) {
        AuthorizationRequest.Builder builder = AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(new Scope(SCOPE)));
        if (email == null || email.isEmpty()) builder.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT);
        else builder.setAccount(new Account(email, "com.google"));
        return builder.build();
    }

    /** Called only after a user taps Connect/Reconnect in the foreground. */
    static void authorize(Activity activity, String email, Callback callback) {
        if (!alive(activity)) return;
        try {
            requireServices(activity);
            Identity.getAuthorizationClient(activity).authorize(request(email))
                    .addOnSuccessListener(result -> {
                        if (!alive(activity)) return;
                        try {
                            if (result.hasResolution()) {
                                // A dialog over the screen takes window focus; only a stopped screen can't show consent.
                                if (activity.getWindow().getDecorView().getWindowVisibility() != android.view.View.VISIBLE) {
                                    callback.failed(new AuthException("Return to Voitation and tap Connect Google again.", false));
                                    return;
                                }
                                PendingIntent resolution = result.getPendingIntent();
                                if (resolution == null) throw new ReconnectException();
                                Log.i(TAG, "authorize: launching Google consent/account UI");
                                activity.startIntentSenderForResult(resolution.getIntentSender(), REQUEST_CODE,
                                        null, 0, 0, 0);
                            } else callback.ready(accessToken(result));
                        } catch (Exception failure) { callback.failed(safe(activity, failure, GoogleAuthError.RESULT_OK)); }
                    })
                    .addOnFailureListener(failure -> {
                        if (alive(activity)) callback.failed(safe(activity, failure, GoogleAuthError.RESULT_OK));
                    });
        } catch (Exception failure) { callback.failed(safe(activity, failure, GoogleAuthError.RESULT_OK)); }
    }

    /** The requesting Activity owns its callback; no callback or token survives its destruction. */
    static boolean result(Activity activity, int requestCode, int resultCode, Intent intent, Callback callback) {
        if (requestCode != REQUEST_CODE) return false;
        Log.w(TAG, "consent result: resultCode=" + resultCode + " data=" + (intent != null) + " callback=" + (callback != null));
        if (callback == null || !alive(activity)) return true;
        // The result intent carries Google's status even when resultCode is RESULT_CANCELED (for example
        // DEVELOPER_ERROR when this build's certificate isn't registered), so always read it first.
        if (intent != null) {
            try {
                AuthorizationResult result = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(intent);
                if (resultCode == Activity.RESULT_OK) { callback.ready(accessToken(result)); return true; }
                Log.w(TAG, "consent result: status OK but resultCode=" + resultCode);
            } catch (Exception failure) { callback.failed(safe(activity, failure, resultCode)); return true; }
        }
        callback.failed(describe(activity, GoogleAuthError.NO_STATUS, resultCode));
        return true;
    }

    /** Background callers receive a reconnect error instead of launching account/consent UI. */
    static String token(Context context, String email) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new AuthException("Backup authorization must run outside the main thread.", false);
        if (email == null || email.isEmpty()) throw new ReconnectException();
        requireServices(context);
        try {
            AuthorizationResult result = Tasks.await(
                    Identity.getAuthorizationClient(context.getApplicationContext()).authorize(request(email)),
                    30, TimeUnit.SECONDS);
            if (result.hasResolution()) throw new ReconnectException();
            return accessToken(result);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Backup interrupted. Please try again.");
        } catch (TimeoutException timeout) {
            throw new AuthException("Google connection timed out. Please try again.", true);
        } catch (ExecutionException failure) {
            throw safe(context, failure.getCause(), GoogleAuthError.RESULT_OK);
        }
    }

    /** A rejected access token must not be returned repeatedly by Google's local cache. */
    static void invalidate(Context context, String accessToken) {
        if (accessToken == null || accessToken.isEmpty() || Looper.myLooper() == Looper.getMainLooper()) return;
        try {
            Tasks.await(Identity.getAuthorizationClient(context.getApplicationContext()).clearToken(
                    ClearTokenRequest.builder().setToken(accessToken).build()), 10, TimeUnit.SECONDS);
        } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        catch (Exception ignored) { /* The original request's fixed reconnect message remains authoritative. */ }
    }

    private static String accessToken(AuthorizationResult result) throws IOException {
        if (result == null || result.getGrantedScopes() == null || !result.getGrantedScopes().contains(SCOPE))
            throw new ReconnectException();
        String token = result.getAccessToken();
        if (token == null || token.isEmpty() || token.length() > 8192
                || token.chars().anyMatch(value -> Character.isWhitespace(value) || Character.isISOControl(value)))
            throw new ReconnectException();
        return token;
    }

    private static void requireServices(Context context) throws AuthException {
        int status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context);
        if (status != ConnectionResult.SUCCESS) {
            Log.w(TAG, "Google Play services unavailable: ConnectionResult " + status);
            throw new AuthException("Google Play services unavailable", "Google Play services must be available and up to date to connect Google Drive (ConnectionResult " + status + ").",
                    status == ConnectionResult.SERVICE_UPDATING || status == ConnectionResult.NETWORK_ERROR);
        }
    }

    private static boolean alive(Activity activity) { return !activity.isFinishing() && !activity.isDestroyed(); }

    private static final String TAG = "VoitationDrive";

    /** Logs the exact Google outcome and returns a specific, fixed-format message (no internal details). */
    static AuthException describe(Context context, int status, int resultCode) {
        GoogleAuthError error = GoogleAuthError.of(status, resultCode, context.getPackageName(), certificateSha1(context));
        Log.w(TAG, "Google authorization failed: status=" + (status == GoogleAuthError.NO_STATUS ? "none" : status + " " + GoogleAuthError.codeName(status))
                + " resultCode=" + resultCode + " -> " + error.title);
        return new AuthException(error.title, error.detail, error.retryable);
    }

    static IOException safe(Context context, Throwable failure, int resultCode) {
        if (failure instanceof ApiException) {
            int status = ((ApiException) failure).getStatusCode();
            if (status == CommonStatusCodes.SIGN_IN_REQUIRED && resultCode == GoogleAuthError.RESULT_OK) { Log.w(TAG, "Google authorization: SIGN_IN_REQUIRED"); return new ReconnectException(); }
            return describe(context, status, resultCode);
        }
        if (failure != null && !(failure instanceof ReconnectException) && !(failure instanceof AuthException))
            Log.w(TAG, "Google authorization failed: " + failure.getClass().getName());
        return safe(failure);
    }

    /** SHA-1 of the certificate this APK is signed with, as shown in Google Cloud Console. */
    static String certificateSha1(Context context) {
        try {
            android.content.pm.Signature[] signers;
            if (android.os.Build.VERSION.SDK_INT >= 28)
                signers = context.getPackageManager().getPackageInfo(context.getPackageName(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.getApkContentsSigners();
            else
                signers = context.getPackageManager().getPackageInfo(context.getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES).signatures;
            byte[] digest = java.security.MessageDigest.getInstance("SHA-1").digest(signers[0].toByteArray());
            StringBuilder out = new StringBuilder();
            for (byte b : digest) { if (out.length() > 0) out.append(':'); out.append(String.format(java.util.Locale.ROOT, "%02X", b)); }
            return out.toString();
        } catch (Exception e) { return ""; }
    }

    /** Google failures may contain internal request details; expose only fixed messages. */
    static IOException safe(Throwable failure) {
        if (failure instanceof ReconnectException || failure instanceof AuthException) return (IOException) failure;
        if (failure instanceof ApiException) {
            int status = ((ApiException) failure).getStatusCode();
            if (status == CommonStatusCodes.SIGN_IN_REQUIRED) return new ReconnectException();
            if (status == CommonStatusCodes.NETWORK_ERROR || status == CommonStatusCodes.TIMEOUT
                    || status == CommonStatusCodes.INTERNAL_ERROR || status == CommonStatusCodes.API_NOT_CONNECTED)
                return new AuthException("Google connection is temporarily unavailable. Please try again.", true);
            if (status == CommonStatusCodes.CANCELED)
                return new AuthException("Google connection was cancelled.", false);
            if (status == CommonStatusCodes.DEVELOPER_ERROR)
                return new AuthException("Google sign-in configuration could not be verified for this app. Check the Android client and signing certificate.", false);
        }
        return new AuthException("Could not connect Google Drive. Please try again.", false);
    }

    private DriveAuth() { }
}
