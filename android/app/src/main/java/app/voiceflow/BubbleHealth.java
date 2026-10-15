package app.voiceflow;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

/** Keeps the always-on bubble alive across restarts: service state, the "turn back on" prompt, battery exemption. */
final class BubbleHealth {
    static final String WAS_SET_UP = "bubble_was_connected";
    static final String FIX_ACCESSIBILITY = "accessibility";
    private static final int NOTICE_ID = 51;
    private BubbleHealth() {}

    static boolean enabledInSettings(Context c) {
        return AccessibilityList.contains(Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                c.getPackageName(), FlowAccessibility.class.getName());
    }
    static boolean connected() { return FlowAccessibility.instance != null; }
    static boolean wasSetUp(Context c) { return Store.bool(c, WAS_SET_UP, false); }

    /** Called from onServiceConnected: remember that the bubble was working and clear any prompt. */
    static void markConnected(Context c) {
        Store.prefs(c).edit().putBoolean(WAS_SET_UP, true).apply();
        try { ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTICE_ID); } catch (RuntimeException ignored) {}
    }

    /** After boot/update: if Android turned the service off (or it didn't start), post a tap-to-fix prompt. */
    static void checkAfterRestart(Context c, String why) {
        boolean enabled = enabledInSettings(c), up = connected(), setUp = wasSetUp(c);
        String problem = AccessibilityList.afterRestart(enabled, up, setUp);
        Log.w("VonoTation", "Bubble check after " + why + ": enabled=" + enabled + " connected=" + up + " wasSetUp=" + setUp + " ignoringBattery=" + ignoringBattery(c));
        if (problem == null) return;
        try {
            NotificationManager notifications = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            notifications.createNotificationChannel(new NotificationChannel("bubble_status", "Bubble status", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open = PendingIntent.getActivity(c, NOTICE_ID, new Intent(c, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(MainActivity.EXTRA_FIX, FIX_ACCESSIBILITY),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            notifications.notify(NOTICE_ID, new Notification.Builder(c, "bubble_status").setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("Tap to turn VonoTation back on").setContentText(problem).setStyle(new Notification.BigTextStyle().bigText(problem + " Tap to turn the bubble back on."))
                    .setContentIntent(open).setAutoCancel(true).build());
        } catch (RuntimeException e) { Log.w("VonoTation", "Bubble prompt could not be posted (notifications off?); Home shows it instead: " + e); }
    }

    static boolean ignoringBattery(Context c) {
        try { return ((PowerManager) c.getSystemService(Context.POWER_SERVICE)).isIgnoringBatteryOptimizations(c.getPackageName()); }
        catch (RuntimeException e) { return false; }
    }
    /** System dialog asking to exempt VonoTation; falls back to the battery optimization list. */
    static void requestBatteryExemption(android.app.Activity a) {
        try { a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + a.getPackageName()))); }
        catch (RuntimeException e) {
            try { a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (RuntimeException ignored) {}
        }
    }
}
