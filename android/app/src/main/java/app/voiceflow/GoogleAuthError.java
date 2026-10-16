package app.voiceflow;

/**
 * Android-free: turns a Google authorization outcome (ApiException status and/or Activity resultCode) into a
 * specific message. Before this, every non-OK result was shown as "Google connection was cancelled".
 */
final class GoogleAuthError {
    static final int NO_STATUS = Integer.MIN_VALUE;
    static final int RESULT_OK = -1, RESULT_CANCELED = 0;
    // CommonStatusCodes / GoogleSignInStatusCodes values (stable public constants).
    static final int SIGN_IN_REQUIRED = 4, NETWORK_ERROR = 7, INTERNAL_ERROR = 8, DEVELOPER_ERROR = 10, ERROR = 13,
            INTERRUPTED = 14, TIMEOUT = 15, CANCELED = 16, API_NOT_CONNECTED = 17,
            SIGN_IN_FAILED = 12500, SIGN_IN_CANCELLED = 12501, SIGN_IN_CURRENTLY_IN_PROGRESS = 12502;

    final String title, detail;
    final boolean retryable, cancelledByUser, configuration;
    private GoogleAuthError(String title, String detail, boolean retryable, boolean cancelledByUser, boolean configuration) {
        this.title = title; this.detail = detail; this.retryable = retryable; this.cancelledByUser = cancelledByUser; this.configuration = configuration;
    }

    static String codeName(int status) {
        switch (status) {
            case SIGN_IN_REQUIRED: return "SIGN_IN_REQUIRED";
            case NETWORK_ERROR: return "NETWORK_ERROR";
            case INTERNAL_ERROR: return "INTERNAL_ERROR";
            case DEVELOPER_ERROR: return "DEVELOPER_ERROR";
            case ERROR: return "ERROR";
            case INTERRUPTED: return "INTERRUPTED";
            case TIMEOUT: return "TIMEOUT";
            case CANCELED: return "CANCELED";
            case API_NOT_CONNECTED: return "API_NOT_CONNECTED";
            case SIGN_IN_FAILED: return "SIGN_IN_FAILED";
            case SIGN_IN_CANCELLED: return "SIGN_IN_CANCELLED";
            case SIGN_IN_CURRENTLY_IN_PROGRESS: return "SIGN_IN_CURRENTLY_IN_PROGRESS";
            default: return "status " + status;
        }
    }

    /** status: ApiException status code or NO_STATUS; resultCode: Activity result or RESULT_OK when not from UI. */
    static GoogleAuthError of(int status, int resultCode, String packageName, String certificateSha1) {
        String cert = "package " + packageName + ", SHA-1 " + (certificateSha1 == null || certificateSha1.isEmpty() ? "unknown" : certificateSha1);
        String code = status == NO_STATUS ? "resultCode " + resultCode + ", no status" : "code " + status + " " + codeName(status) + (resultCode == RESULT_OK ? "" : ", resultCode " + resultCode);
        if (status == DEVELOPER_ERROR)
            return new GoogleAuthError("Google sign-in isn’t set up for this build",
                    "Google rejected this app’s sign-in setup (" + code + "). The Android OAuth client must list " + cert + ".", false, false, true);
        if (status == SIGN_IN_FAILED)
            return new GoogleAuthError("Google connection did not finish",
                    "Google could not complete the connection (" + code + "). Try again. If it keeps failing, check that your account is allowed to use this app and that its Android OAuth registration matches " + cert + ".", true, false, false);
        if (status == SIGN_IN_CANCELLED || status == CANCELED)
            return new GoogleAuthError("Google connection cancelled", "Sign-in was closed before it finished (" + code + "). Your previous connection is unchanged.", true, true, false);
        if (status == NETWORK_ERROR || status == TIMEOUT || status == INTERRUPTED || status == INTERNAL_ERROR || status == API_NOT_CONNECTED || status == SIGN_IN_CURRENTLY_IN_PROGRESS)
            return new GoogleAuthError("Google is temporarily unavailable", "Google couldn’t complete sign-in right now (" + code + "). Check the connection and try again.", true, false, false);
        if (status == SIGN_IN_REQUIRED)
            return new GoogleAuthError("Reconnect Google", "Google needs you to sign in again (" + code + ").", true, false, false);
        if (status == NO_STATUS && resultCode == RESULT_CANCELED)
            return new GoogleAuthError("Google sign-in closed without a result",
                    "Google returned no result (" + code + "). VonoTation did not complete a connection. Tap Connect Google again. If it keeps closing, check your account’s access and the Android OAuth registration for " + cert + ".", true, false, false);
        return new GoogleAuthError("Google connection needs attention", "Could not connect Google Drive (" + code + ").", true, false, false);
    }
}
