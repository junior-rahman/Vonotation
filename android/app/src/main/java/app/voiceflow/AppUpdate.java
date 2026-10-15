package app.voiceflow;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.lang.ref.WeakReference;

/** Settings update: checks a small public file, then installs a newer signed build. */
final class AppUpdate {
    private static final String FEED="https://raw.githubusercontent.com/JuelHossain/Vonotation/main/latest.json";
    private static final java.util.concurrent.ExecutorService WORK=java.util.concurrent.Executors.newSingleThreadExecutor();
    private static WeakReference<Button> button;
    private static WeakReference<TextView> line;
    private static volatile boolean busy;

    private AppUpdate(){}

    static void bind(Context context,Button action,TextView status){
        button=new WeakReference<>(action);
        line=new WeakReference<>(status);
        action.setText(busy?"Updating…":"Update");
        status.setText(here(context));
    }

    static void start(android.app.Activity activity){
        if(busy)return;
        if(Build.VERSION.SDK_INT>=26 && !activity.getPackageManager().canRequestPackageInstalls()){
            say(activity,"Allow VonoTation to install updates, then tap Update again.");
            try{activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));}catch(RuntimeException ignored){}
            return;
        }
        busy=true;
        paint(activity,"Updating…","Checking for a newer build…");
        Context app=activity.getApplicationContext();
        WORK.execute(()->{
            try{
                JSONObject feed=new JSONObject(text(FEED));
                int code=feed.optInt("versionCode",0);
                String name=feed.optString("versionName","");
                String apk=feed.optString("apk","");
                int mine=mine(app);
                if(code<=mine || apk.isEmpty()){
                    busy=false;
                    paint(app,"Update","You're on "+here(app)+". That's the latest.");
                    return;
                }
                paint(app,"Updating…","Downloading "+name+"…");
                install(app,apk,name);
            }catch(Exception error){
                busy=false;
                paint(app,"Update","Couldn't check for an update. Try again.");
            }
        });
    }

    static void finished(Context context,int status,String message){
        busy=false;
        if(status==PackageInstaller.STATUS_SUCCESS){say(context,"Update installed.");paint(context,"Update",here(context));return;}
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION)return;
        String why=message==null||message.isEmpty()?"The update didn't install.":message;
        paint(context,"Update",why);
        say(context,why);
    }

    private static void install(Context context,String address,String name) throws Exception {
        HttpURLConnection conn=open(address);
        try{
            int len=conn.getContentLength();
            if(len>40*1024*1024)throw new IllegalStateException("Update file is too large.");
            PackageInstaller installer=context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            if(len>0)params.setSize(len);
            params.setAppPackageName(context.getPackageName());
            int id=installer.createSession(params);
            PackageInstaller.Session session=installer.openSession(id);
            try(InputStream in=conn.getInputStream();OutputStream out=session.openWrite("base",0,len>0?len:-1)){
                byte[] buf=new byte[65536];
                int n;long got=0;
                while((n=in.read(buf))>=0){if(n==0)continue;out.write(buf,0,n);got+=n;if(len>0 && (got%262144)<n)paint(context,"Updating…","Downloading "+name+"… "+(got*100/len)+"%");}
                session.fsync(out);
            }
            Intent done=new Intent(context,UpdateResult.class);
            int flags=PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0);
            session.commit(PendingIntent.getBroadcast(context,70,done,flags).getIntentSender());
            session.close();
            paint(context,"Updating…","Confirm the install.");
        }finally{conn.disconnect();}
    }

    private static String text(String address) throws Exception {
        HttpURLConnection conn=open(address);
        try(InputStream in=conn.getInputStream()){
            byte[] buf=new byte[4096];StringBuilder out=new StringBuilder();int n;
            while((n=in.read(buf))>=0)out.append(new String(buf,0,n,"UTF-8"));
            if(out.length()>8000)throw new IllegalStateException("Update file is not valid.");
            return out.toString();
        }finally{conn.disconnect();}
    }

    private static HttpURLConnection open(String address) throws Exception {
        HttpURLConnection conn=(HttpURLConnection)new URL(address).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent","VonoTation");
        conn.setRequestProperty("Accept","*/*");
        int code=conn.getResponseCode();
        if(code>=400)throw new IllegalStateException("Update server returned "+code);
        return conn;
    }

    private static int mine(Context context){
        try{return context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionCode;}
        catch(PackageManager.NameNotFoundException error){return 0;}
    }

    private static String here(Context context){
        try{return "VonoTation "+context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;}
        catch(PackageManager.NameNotFoundException error){return "VonoTation";}
    }

    private static void paint(Context context,String title,String detail){
        android.os.Handler main=new android.os.Handler(context.getMainLooper());
        main.post(()->{
            Button action=button==null?null:button.get();
            TextView status=line==null?null:line.get();
            if(action!=null && action.isAttachedToWindow())action.setText(title);
            if(status!=null && status.isAttachedToWindow())status.setText(detail);
        });
    }

    private static void say(Context context,String message){
        new android.os.Handler(context.getMainLooper()).post(()->Toast.makeText(context,message,Toast.LENGTH_LONG).show());
    }
}
