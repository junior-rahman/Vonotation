package app.voiceflow;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ListView;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Local native picker regression: older generations stay selectable without Google. */
public final class BackupGenerationPickerTest extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart() {
        Bundle report=new Bundle();Activity[] screen={null};
        try {
            List<DriveCloudClient.BackupFile> files=new ArrayList<>();
            for(int i=0;i<5;i++)files.add(new DriveCloudClient.BackupFile("synthetic-generation-"+i,"backup","2026-10-15T12:00:00Z",1024));
            Constructor<DriveCloudClient.Listing> constructor=DriveCloudClient.Listing.class.getDeclaredConstructor(List.class,List.class);constructor.setAccessible(true);
            DriveCloudClient.Listing listing=constructor.newInstance(files,Collections.emptyList());
            DriveBackup.Connection connection=new DriveBackup.Connection(new DriveCloudClient.Profile("fixture@example.invalid","Fixture","synthetic-id"),listing,"fixture-token-unused");
            Class<?> callbackType=Class.forName("app.voiceflow.BackupActivity$Done");
            DriveCloudClient.BackupFile[] chosen={null};
            Object callback=Proxy.newProxyInstance(callbackType.getClassLoader(),new Class<?>[]{callbackType},(proxy,method,args)->{if(method.getName().equals("accept"))chosen[0]=(DriveCloudClient.BackupFile)args[0];return null;});
            screen[0]=startActivitySync(new Intent(getTargetContext(),BackupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Method select=BackupActivity.class.getDeclaredMethod("selectBackup",DriveBackup.Connection.class,callbackType);select.setAccessible(true);
            Throwable[] failure={null};
            runOnMainSync(()->{try{select.invoke(screen[0],connection,callback);}catch(Throwable error){failure[0]=error;}});waitForIdleSync();
            if(failure[0]!=null)throw new Exception("Could not open the production picker",failure[0]);
            Field active=BackupActivity.class.getDeclaredField("activeDialog");active.setAccessible(true);AlertDialog dialog=(AlertDialog)active.get(screen[0]);
            if(dialog==null || !dialog.isShowing())throw new AssertionError("Backup generation dialog did not open");
            ListView list=dialog.getListView();
            if(list==null || list.getAdapter().getCount()!=5)throw new AssertionError("Older backup generations were truncated");
            runOnMainSync(()->list.setSelection(4));waitForIdleSync();
            runOnMainSync(()->list.performItemClick(null,4,list.getAdapter().getItemId(4)));waitForIdleSync();
            if(chosen[0]!=files.get(4))throw new AssertionError("Fifth backup generation could not be selected");
            report.putString("result","PASS: production native restore picker exposes all five synthetic generations and selects the fifth; no Google authorization or backup transport invoked");
        } catch(Throwable error){report.putString("failure",android.util.Log.getStackTraceString(error));}
        finally {if(screen[0]!=null)runOnMainSync(()->screen[0].finish());}
        finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);
    }
}
