package app.voiceflow;

/** Google auth failures are reported with their real code, not all as "cancelled". */
public final class GoogleAuthErrorTest {
    private static int checks;
    static void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError(what); }
    public static void main(String[] args) {
        String sha = "A7:80:92:53";
        GoogleAuthError e = GoogleAuthError.of(10, GoogleAuthError.RESULT_OK, "app.voiceflow", sha);
        check(e.configuration && e.detail.contains("code 10 DEVELOPER_ERROR") && e.detail.contains("app.voiceflow") && e.detail.contains(sha), "developer error " + e.detail);
        check(!e.title.contains("cancel"), "developer error is not 'cancelled'");
        check(!e.retryable && !e.cancelledByUser, "confirmed setup error requires setup attention rather than blind retry");
        e = GoogleAuthError.of(12501, GoogleAuthError.RESULT_CANCELED, "app.voiceflow", sha);
        check(e.cancelledByUser && e.detail.contains("12501 SIGN_IN_CANCELLED") && e.detail.contains("resultCode 0"), "12501 " + e.detail);
        e = GoogleAuthError.of(GoogleAuthError.NO_STATUS, GoogleAuthError.RESULT_CANCELED, "app.voiceflow", sha);
        check(!e.cancelledByUser && !e.configuration && e.retryable, "missing result cannot identify cancellation or configuration failure");
        check(e.detail.contains("resultCode 0, no status") && e.detail.contains(sha), "missing result retains useful diagnostic evidence");
        check(!e.detail.contains("probably") && !e.detail.contains("must list") && e.detail.contains("Tap Connect Google again"), "missing result gives a recovery action without guessing its cause");
        e = GoogleAuthError.of(7, GoogleAuthError.RESULT_OK, "app.voiceflow", sha);
        check(e.retryable && e.detail.contains("NETWORK_ERROR"), "network");
        e = GoogleAuthError.of(16, GoogleAuthError.RESULT_OK, "app.voiceflow", sha);
        check(e.cancelledByUser, "16 canceled");
        e = GoogleAuthError.of(12500, GoogleAuthError.RESULT_OK, "app.voiceflow", "");
        check(!e.configuration && !e.cancelledByUser && e.retryable, "generic sign-in failure does not prove configuration failure");
        check(e.detail.contains("12500 SIGN_IN_FAILED") && e.detail.contains("SHA-1 unknown"), "generic failure preserves code and unknown certificate evidence");
        check(e.detail.contains("account is allowed") && !e.detail.contains("Google rejected"), "generic failure presents access and registration as checks");
        e = GoogleAuthError.of(10, GoogleAuthError.RESULT_CANCELED, "app.voiceflow", sha);
        check(e.configuration && !e.cancelledByUser && e.detail.contains("code 10 DEVELOPER_ERROR") && e.detail.contains("resultCode 0"), "Google setup status takes precedence over canceled Activity result");
        e = GoogleAuthError.of(12500, GoogleAuthError.RESULT_CANCELED, "app.voiceflow", sha);
        check(!e.configuration && !e.cancelledByUser && e.detail.contains("resultCode 0"), "generic failure remains neutral when Activity is canceled");
        e = GoogleAuthError.of(GoogleAuthError.NO_STATUS, GoogleAuthError.RESULT_OK, "app.voiceflow", sha);
        check(!e.configuration && !e.cancelledByUser && e.detail.contains("no status"), "unknown non-canceled outcome remains neutral");
        e = GoogleAuthError.of(99, GoogleAuthError.RESULT_OK, "app.voiceflow", sha);
        check(e.detail.contains("code 99 status 99"), "unknown " + e.detail);
        System.out.println("PASS: " + checks + " google-auth-error checks.");
    }
}
