package app.voiceflow;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.util.Log;

/**
 * The optional "Voitation is listening" foreground notice for bubble and keyboard dictation.
 *
 * The recorder always runs inside the bound accessibility or input-method service, and the bubble overlay
 * shows the recording and processing status, so the notice is never required for a session to keep going.
 *  - Notifications allowed: microphone-type foreground service with a visible notice and Finish action.
 *  - Notifications denied: the same microphone-type foreground service is still attempted. It doesn't need
 *    POST_NOTIFICATIONS (Android just hides the notice) and keeps the mic eligible on Android 14+ if the app
 *    loses its foreground state while recording.
 *  - startForeground refused (background-start limits, Android 14 microphone-type rules, OEM limits): log
 *    it and keep recording in the bound service with the bubble as the only status. The session never ends here.
 */
final class RecordingNotice {
    static final String NONE = RecordingMode.NONE;

    private RecordingNotice() {}

    /** Starts the notice if Android allows it; never throws. Returns the resulting mode. */
    // Generic Service hides the concrete component from lint. Both permitted bound services declare
    // microphone in Voitation; Notes removes those accessibility and keyboard components entirely.
    @android.annotation.SuppressLint("ForegroundServiceType")
    static String start(Service service, int id, String text, Intent stopIntent) {
        if(AppProduct.isNotes())return NONE;
        boolean allowed;
        try { allowed = ((NotificationManager) service.getSystemService(Service.NOTIFICATION_SERVICE)).areNotificationsEnabled(); }
        catch (RuntimeException e) { allowed = false; }
        boolean started = false;
        try {
            NotificationManager notifications = (NotificationManager) service.getSystemService(Service.NOTIFICATION_SERVICE);
            notifications.createNotificationChannel(new NotificationChannel("dictation", "Active dictation", NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop = PendingIntent.getService(service, id, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notice = new Notification.Builder(service, "dictation").setSmallIcon(R.drawable.ic_voitation_notice)
                    .setContentTitle("Voitation is listening").setContentText(text).setOngoing(true)
                    .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "Finish", stop).build()).build();
            if (Build.VERSION.SDK_INT >= 30) service.startForeground(id, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else service.startForeground(id, notice);
            started = true;
        } catch (Exception | LinkageError e) {
            Log.w("VonoTation", "Recording notice refused; recording continues in the bound service: " + e);
        }
        String mode = RecordingMode.of(started, allowed);
        Log.i("VonoTation", "Recording mode: " + mode);
        return mode;
    }

    /** Ends the notice if one was started; never throws. */
    static void stop(Service service, String mode) {
        try { service.stopForeground(Service.STOP_FOREGROUND_REMOVE); }
        catch (RuntimeException e) { Log.w("VonoTation", "stopForeground failed (" + mode + "): " + e); }
    }
}
