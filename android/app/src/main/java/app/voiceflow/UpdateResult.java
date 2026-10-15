package app.voiceflow;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

/** The system installer hands back a confirm screen, or a failure, after an in-app update. */
public final class UpdateResult extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null)return;
        int status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){
            Intent confirm=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra(Intent.EXTRA_INTENT,Intent.class):intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if(confirm!=null){confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);try{context.startActivity(confirm);}catch(RuntimeException ignored){}}
            return;
        }
        AppUpdate.finished(context,status,intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
    }
}
