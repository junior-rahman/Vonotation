package app.voiceflow;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * After a restart or app update, checks that the bubble's Accessibility service came back. Android binds an
 * enabled service by itself after the first unlock; this only detects when it was turned off or didn't start
 * and posts "Tap to turn VonoTation back on". Not directBootAware: credential-protected prefs are read after unlock.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        // Give Android a few seconds to bind the service before deciding it didn't start (receiver limit is 10 s).
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                BubbleHealth.checkAfterRestart(app, action.endsWith("BOOT_COMPLETED") ? "boot" : "update");
                // Reload the saved ADB identity and rediscover its new port; never re-pair or start a microphone at boot.
                LineWatch.start(app,null);
            }
            finally { pending.finish(); }
        }, 7000);
    }
}
