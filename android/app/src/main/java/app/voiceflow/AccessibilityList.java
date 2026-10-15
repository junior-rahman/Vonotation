package app.voiceflow;

/** Android-free: parses Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES ("pkg/.Cls:pkg/pkg.Cls2"). */
final class AccessibilityList {
    private AccessibilityList() {}
    static boolean contains(String setting, String pkg, String fullClass) {
        if (setting == null || setting.isEmpty()) return false;
        String shortClass = fullClass.startsWith(pkg + ".") ? fullClass.substring(pkg.length()) : fullClass;
        for (String entry : setting.split(":")) {
            String e = entry.trim();
            int slash = e.indexOf('/');
            if (slash <= 0 || !e.substring(0, slash).equalsIgnoreCase(pkg)) continue;
            String cls = e.substring(slash + 1);
            if (cls.equals(fullClass) || cls.equals(shortClass)) return true;
        }
        return false;
    }
    /** What to tell the user after a restart; null when nothing is wrong. */
    static String afterRestart(boolean enabledInSettings, boolean connected, boolean wasSetUp) {
        if (!wasSetUp) return null;
        if (!enabledInSettings) return "Android turned VonoTation’s bubble off after the restart.";
        if (!connected) return "VonoTation’s bubble didn’t start after the restart.";
        return null;
    }
}
