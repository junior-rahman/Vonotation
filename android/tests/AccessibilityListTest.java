package app.voiceflow;

/** Reboot check: detect when Android turned the bubble's Accessibility service off. */
public final class AccessibilityListTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    public static void main(String[] args) {
        String p = "app.voiceflow", c = "app.voiceflow.FlowAccessibility";
        check(AccessibilityList.contains("app.voiceflow/app.voiceflow.FlowAccessibility", p, c), "full name");
        check(AccessibilityList.contains("com.x/.A:app.voiceflow/.FlowAccessibility", p, c), "short name in list");
        check(!AccessibilityList.contains("com.x/.A", p, c), "absent");
        check(!AccessibilityList.contains(null, p, c) && !AccessibilityList.contains("", p, c), "empty/null after reboot");
        check(!AccessibilityList.contains("app.voiceflow.evil/.FlowAccessibility", p, c), "prefix package");
        check(AccessibilityList.afterRestart(false, false, true).contains("turned"), "turned off");
        check(AccessibilityList.afterRestart(true, false, true).contains("didn’t start"), "not started");
        check(AccessibilityList.afterRestart(true, true, true) == null, "healthy");
        check(AccessibilityList.afterRestart(false, false, false) == null, "never set up: no nag");
        System.out.println("PASS: 9 accessibility-list checks.");
    }
}
