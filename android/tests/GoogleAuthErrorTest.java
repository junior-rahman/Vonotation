package app.voiceflow;

/** Google auth failures are reported with their real code, not all as "cancelled". */
public final class GoogleAuthErrorTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    public static void main(String[] args) {
        String sha = "A7:80:92:53";
        GoogleAuthError e = GoogleAuthError.of(10, GoogleAuthError.RESULT_OK, "app.voiceflow", sha);
        check(e.configuration && e.detail.contains("code 10 DEVELOPER_ERROR") && e.detail.contains("app.voiceflow") && e.detail.contains(sha), "developer error " + e.detail);
        check(!e.title.contains("cancel"), "developer error is not 'cancelled'");
        e = GoogleAuthError.of(12501, GoogleAuthError.RESULT_CANCELED, "app.voiceflow", sha);
        check(e.cancelledByUser && e.detail.contains("12501 SIGN_IN_CANCELLED") && e.detail.contains("resultCode 0"), "12501 " + e.detail);
        e = GoogleAuthError.of(GoogleAuthError.NO_STATUS, GoogleAuthError.RESULT_CANCELED, "app.voiceflow", sha);
        check(!e.cancelledByUser && e.configuration && e.detail.contains("resultCode 0, no status") && e.detail.contains(sha), "bare cancel " + e.detail);
        e = GoogleAuthError.of(7, GoogleAuthError.RESULT_OK, "app.voiceflow", sha);
        check(e.retryable && e.detail.contains("NETWORK_ERROR"), "network");
        e = GoogleAuthError.of(16, GoogleAuthError.RESULT_OK, "app.voiceflow", sha);
        check(e.cancelledByUser, "16 canceled");
        e = GoogleAuthError.of(12500, GoogleAuthError.RESULT_OK, "app.voiceflow", "");
        check(e.configuration && e.detail.contains("SHA-1 unknown"), "12500");
        e = GoogleAuthError.of(99, GoogleAuthError.RESULT_OK, "app.voiceflow", sha);
        check(e.detail.contains("code 99 status 99"), "unknown " + e.detail);
        System.out.println("PASS: 9 google-auth-error checks.");
    }
}
