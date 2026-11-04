package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipInputStream;
import org.json.*;

/** Real thought detail action and provider, using disposable source bytes; no receiver app is contacted. */
final class ThoughtAudioShareChecks {
    static int run(Instrumentation test, Activity activity) throws Exception {
        Context context=activity.getApplicationContext();
        String before=Store.get(context,"notes","[]");
        File a=new File(Notes.sourceDirectory(context),"thought-share-a.m4a");
        File b=new File(Notes.sourceDirectory(context),"thought-share-b.m4a");
        AtomicReference<Intent> chooser=new AtomicReference<>();
        Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent){
                if(!Intent.ACTION_CHOOSER.equals(intent.getAction()))return null;
                chooser.set(intent);return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);
            }
        };
        test.addMonitor(monitor);
        try {
            try(FileOutputStream out=new FileOutputStream(a)){out.write(new byte[]{1,2,3});}
            try(FileOutputStream out=new FileOutputStream(b)){out.write(new byte[]{4,5,6});}
            android.content.pm.ProviderInfo provider=context.getPackageManager().resolveContentProvider(context.getPackageName()+".recording-share",0);
            require(provider!=null&&!provider.exported&&provider.grantUriPermissions,"Private share provider missing");
            JSONObject thought=new JSONObject().put("id",8001).put("created",System.currentTimeMillis()).put("thought",true)
                .put("title","Share a saved thought").put("pending",true).put("audio",a.getName())
                .put("parts",new JSONArray().put(a.getName()));
            View body=render(test,activity,thought);
            require(find(body,"Share audio")!=null,"Audio-only pending thought has no Share audio action");
            Uri single=share(test,body,chooser,"audio/mp4");
            require("audio/mp4".equals(context.getContentResolver().getType(single)),"Single audio MIME mismatch");
            try(InputStream in=context.getContentResolver().openInputStream(single)){require(in.read()==1&&in.read()==2&&in.read()==3&&in.read()==-1,"Shared bytes changed");}
            try(Cursor cursor=context.getContentResolver().query(single,null,null,null,null)){
                require(cursor!=null&&cursor.moveToFirst()&&cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))==3,"Missing shared-file metadata");
            }
            boolean denied=false;try{context.getContentResolver().openFileDescriptor(single,"w");}catch(FileNotFoundException expected){denied=true;}
            require(denied,"Share allowed writes");
            thought.put("parts",new JSONArray().put(a.getName()).put(b.getName()));
            body=render(test,activity,thought);
            require(find(body,"Shares all audio parts in order as a ZIP file.")!=null,"Multipart explanation missing");
            Uri multiple=share(test,body,chooser,"application/zip");
            try(ZipInputStream zip=new ZipInputStream(context.getContentResolver().openInputStream(multiple))){
                require("Read-me.txt".equals(zip.getNextEntry().getName()),"Missing ZIP instructions");zip.closeEntry();
                require("0001.m4a".equals(zip.getNextEntry().getName())&&zip.read()==1,"First part missing or reordered");zip.closeEntry();
                require("0002.m4a".equals(zip.getNextEntry().getName())&&zip.read()==4,"Second part missing or reordered");zip.closeEntry();
                require(zip.getNextEntry()==null,"Unexpected ZIP entry");
            }
            thought.put("parts",new JSONArray().put(a.getName()).put("missing-source.m4a"));
            body=render(test,activity,thought);chooser.set(null);click(test,body);drain(test);
            require(chooser.get()==null,"Incomplete source was shared");
            thought.put("parts",new JSONArray().put(a.getName())).put("recording",true);
            require(find(render(test,activity,thought),"Share audio")==null,"Active recording can be shared");
            thought.put("recording",false).put("audio","").put("parts",new JSONArray());
            require(find(render(test,activity,thought),"Share audio")==null,"Text-only thought offers audio sharing");
            require(a.length()==3&&b.length()==3,"Original source was changed");
            thought.put("audio",a.getName()).put("parts",new JSONArray().put(a.getName()));
            render(test,activity,thought);
            return 19;
        } finally {
            test.removeMonitor(monitor);
            Store.prefs(context).edit().putString("notes",before).commit();a.delete();b.delete();
        }
    }
    private static View render(Instrumentation test,Activity activity,JSONObject thought)throws Exception{
        require(Store.prefs(activity).edit().putString("notes",new JSONArray().put(thought).toString()).commit(),"Could not seed thought");
        Method detail=Notes.class.getDeclaredMethod("thoughtDetail",Activity.class,LinearLayout.class,JSONObject.class,Runnable.class);detail.setAccessible(true);
        LinearLayout[] body=new LinearLayout[1];Throwable[] failure=new Throwable[1];
        test.runOnMainSync(()->{try{
            body[0]=Ui.column(activity);body[0].setPadding(Ui.dp(activity,20),Ui.dp(activity,32),Ui.dp(activity,20),Ui.dp(activity,24));body[0].setBackgroundColor(Daybook.paper(activity));
            android.widget.ScrollView scroll=new android.widget.ScrollView(activity);scroll.addView(body[0]);activity.setContentView(scroll);
            detail.invoke(null,activity,body[0],thought,(Runnable)()->{});
        }catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError(failure[0]);test.waitForIdleSync();return body[0];
    }
    private static Uri share(Instrumentation test,View body,AtomicReference<Intent> chooser,String type)throws Exception{
        chooser.set(null);click(test,body);drain(test);
        Intent outer=chooser.get();require(outer!=null,"Share action did not open Android chooser");
        Intent send=outer.getParcelableExtra(Intent.EXTRA_INTENT);
        require(send!=null&&Intent.ACTION_SEND.equals(send.getAction())&&type.equals(send.getType()),"Wrong send action or MIME");
        Uri uri=send.getParcelableExtra(Intent.EXTRA_STREAM);
        require(uri!=null&&"content".equals(uri.getScheme())&&(send.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0,"Missing URI grant");
        require(send.getClipData()!=null&&uri.equals(send.getClipData().getItemAt(0).getUri()),"ClipData does not carry shared URI");
        require((outer.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0&&outer.getClipData()!=null,"Chooser did not propagate URI grant");
        return uri;
    }
    private static void click(Instrumentation test,View body){test.runOnMainSync(()->{View button=find(body,"Share audio");require(button!=null&&button.performClick(),"Share button click failed");});}
    private static void drain(Instrumentation test)throws Exception{
        Field field=Notes.class.getDeclaredField("mediaWork");field.setAccessible(true);
        ((ExecutorService)field.get(null)).submit(()->{}).get(10,TimeUnit.SECONDS);test.waitForIdleSync();
    }
    private static View find(View view,String label){
        if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View match=find(((ViewGroup)view).getChildAt(i),label);if(match!=null)return match;}
        return null;
    }
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
