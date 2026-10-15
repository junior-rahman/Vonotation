package app.voiceflow;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

/** Only the matching owned session may update state; Android confirmation launches from a resumed app. */
public final class UpdateResult extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null)return;
        int status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        int session=intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,intent.getIntExtra(AppUpdate.EXTRA_SESSION,-1));
        Intent confirm=null;
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION)confirm=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra(Intent.EXTRA_INTENT,Intent.class):intent.getParcelableExtra(Intent.EXTRA_INTENT);
        AppUpdate.result(context,session,intent.getStringExtra(AppUpdate.EXTRA_TOKEN),status,intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),confirm);
    }
}
