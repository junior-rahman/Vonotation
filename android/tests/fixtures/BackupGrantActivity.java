package app.voiceflow.fixture;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.provider.DocumentsContract;

/** Issues an ordinary Android URI grant from the provider UID, without app test hooks. */
public final class BackupGrantActivity extends Activity {
    public void onCreate(Bundle state){super.onCreate(state);ResultReceiver receiver=getIntent().getParcelableExtra("reply");Bundle result=new Bundle();try{
        String command=getIntent().getStringExtra("command");Uri uri=DocumentsContract.buildDocumentUri(BackupProvider.AUTHORITY,BackupProvider.DOCUMENT);int flags=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if("reset".equals(command))BackupProvider.reset();
        else if("fail-write".equals(command))BackupProvider.failWrites=true;
        else if("allow-write".equals(command))BackupProvider.failWrites=false;
        else if("unavailable".equals(command))BackupProvider.unavailable=true;
        else if("available".equals(command))BackupProvider.unavailable=false;
        else if("block-read".equals(command))BackupProvider.block(false);
        else if("block-write".equals(command))BackupProvider.block(true);
        else if("unblock".equals(command))BackupProvider.unblock();
        else if("status".equals(command)){}
        else if("tamper".equals(command))BackupProvider.tamper();
        else if("revoke".equals(command))revokeUriPermission("app.voiceflow",uri,flags);
        else if("grant".equals(command)){Intent target=new Intent(Intent.ACTION_VIEW).setClassName("app.voiceflow","app.voiceflow.MainActivity").setData(uri).addFlags(flags|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);target.setClipData(android.content.ClipData.newRawUri("QA backup",uri));startActivity(target);}
        else throw new Exception("Unknown provider fixture command");
        result.putString("uri",uri.toString());result.putBoolean("ok",true);result.putBoolean("read_entered",BackupProvider.readEntered);result.putBoolean("write_entered",BackupProvider.writeEntered);
    }catch(Throwable error){result.putString("error",error.toString());}finally{if(receiver!=null)receiver.send(result.getBoolean("ok")?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);finish();}}
}
