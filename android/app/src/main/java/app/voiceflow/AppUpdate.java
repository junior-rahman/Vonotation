package app.voiceflow;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** User-started, verified APK update. Android always owns the final installation confirmation. */
final class AppUpdate {
    private static final String FEED="https://raw.githubusercontent.com/junior-rahman/Vonotation/main/latest.json";
    static final String EXTRA_TOKEN="update_token",EXTRA_SESSION="update_session";
    private static final String IDLE="idle",CHECKING="checking",DOWNLOADING="downloading",VERIFYING="verifying",READY="ready",PERMISSION="permission",INSTALLING="installing",CONFIRM="confirm",ERROR="error";
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor();
    private static WeakReference<Button> button;
    private static WeakReference<TextView> line;
    private static WeakReference<Activity> foreground;
    private static volatile boolean busy;
    private static Intent confirmation;
    private static boolean confirmationLaunched;
    private AppUpdate(){}
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("app_updates",Context.MODE_PRIVATE);}
    private static Handler main(Context c){return new Handler(c.getMainLooper());}
    private static File apk(Context c){return new File(c.getCacheDir(),"verified-update.apk");}
    private static File partial(Context c){return new File(c.getCacheDir(),"update-download.part");}

    static void bind(Context context,Button action,TextView status){
        button=new WeakReference<>(action);line=new WeakReference<>(status);render(context);
    }
    static void resume(Activity activity){
        foreground=new WeakReference<>(activity);
        if(busy){render(activity);return;}
        Release release=saved(activity);String phase=prefs(activity).getString("phase",IDLE);
        if(release!=null&&mine(activity)>=release.code){
            clearSession(activity);clearPrepared(activity);state(activity,IDLE,"Update","Update installed. "+here(activity));return;
        }
        if(phase.equals(CHECKING)||phase.equals(DOWNLOADING)||phase.equals(VERIFYING)){
            partial(activity).delete();state(activity,READY,"Continue update","The previous update was interrupted. Tap to continue.");
        }
        int id=prefs(activity).getInt("session",-1);
        if(id>=0&&(phase.equals(INSTALLING)||phase.equals(CONFIRM))){
            PackageInstaller.SessionInfo info=activity.getPackageManager().getPackageInstaller().getSessionInfo(id);
            if(info==null){clearSession(activity);state(activity,READY,"Continue update","Installation wasn't completed. Tap to try again.");}
            else if(info.isActive()||(phase.equals(INSTALLING)&&installerInFlight(activity))){state(activity,INSTALLING,"Installing…","Android is preparing the installation confirmation…");}
            else{state(activity,CONFIRM,"Continue installation","Confirm the update in Android's installation screen.");if(confirmation!=null&&!confirmationLaunched)launchConfirmation(activity);}
        }else if(phase.equals(PERMISSION)&&canInstall(activity)&&release!=null&&apk(activity).isFile()){
            continuePrepared(activity,false);return;
        }
        render(activity);
    }
    static void pause(Activity activity){Activity current=foreground==null?null:foreground.get();if(current==activity)foreground=null;}
    static void start(Activity activity){
        if(busy)return;foreground=new WeakReference<>(activity);
        String phase=prefs(activity).getString("phase",IDLE);
        if(activeSession(activity)||(phase.equals(INSTALLING)&&installerInFlight(activity))){state(activity,INSTALLING,"Installing…","Android is preparing the installation. Please wait a moment.");return;}
        if(phase.equals(CONFIRM)&&confirmation!=null){launchConfirmation(activity);return;}
        Release release=saved(activity);
        if(release!=null&&release.code>mine(activity)&&apk(activity).isFile()){
            // A lost confirmation Intent is not reconstructible. Replace only our own session from the verified cache.
            clearSession(activity);continuePrepared(activity,true);return;
        }
        busy=true;state(activity,CHECKING,"Checking…","Checking for a newer version…");
        Context app=activity.getApplicationContext();
        WORK.execute(()->{
            try{
                Release next=Release.parse(readFeed());
                if(next.code<=mine(app)){busy=false;clearPrepared(app);state(app,IDLE,"Update","You're on "+here(app)+". That's the latest.");return;}
                clearSession(app);clearPrepared(app);
                if(!prefs(app).edit().putString("release",next.json).commit())throw new Problem("Couldn't save the update state. Try again.");
                state(app,DOWNLOADING,"Downloading…","Downloading "+next.name+"…");download(app,next);
                state(app,VERIFYING,"Verifying…","Checking update integrity and signing…");verify(app,next);
                state(app,READY,"Continue update","Update verified. Ready to install.");
                main(app).post(()->{busy=false;Activity active=currentActivity();if(active!=null)continuePrepared(active,true);else render(app);});
            }catch(Exception error){fail(app,problem(error,"Couldn't check or download the update. Try again."),true);}
        });
    }
    private static void continuePrepared(Activity activity,boolean requestPermission){
        if(busy)return;Release release=saved(activity);
        if(release==null||!apk(activity).isFile()){state(activity,ERROR,"Update","The saved update is unavailable. Tap Update to download again.");return;}
        if(audioBusy()){deferred(activity);return;}
        if(!canInstall(activity)){
            state(activity,PERMISSION,"Allow installation","Update verified. Allow VonoTation to install it; the update resumes when you return.");
            if(requestPermission)try{activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));}catch(RuntimeException unavailable){state(activity,PERMISSION,"Allow installation","Open Android settings to allow VonoTation to install updates, then return.");}
            return;
        }
        busy=true;Context app=activity.getApplicationContext();state(app,VERIFYING,"Verifying…","Rechecking the saved update…");
        WORK.execute(()->{
            try{verify(app,release);install(app,release);busy=false;render(app);}
            catch(Deferred wait){busy=false;deferred(app);}
            catch(Exception error){fail(app,problem(error,"The update couldn't be prepared. Tap Update to retry."),true);}
        });
    }
    private static boolean audioBusy(){return Dictation.isWorking()||VoiceTake.updateBusy()||Translate.going()||LineWire.updateBusy()||Notes.updateBusy();}
    private static boolean audioBusyOnMain(Context context) throws Exception {
        if(Looper.myLooper()==context.getMainLooper())return audioBusy();
        FutureTask<Boolean> check=new FutureTask<>(AppUpdate::audioBusy);main(context).post(check);return check.get(5,TimeUnit.SECONDS);
    }
    private static void deferred(Context context){state(context,READY,"Continue update","Finish recording or processing your audio, then continue the update.");}
    private static Activity currentActivity(){Activity a=foreground==null?null:foreground.get();return a==null||a.isFinishing()||a.isDestroyed()?null:a;}
    private static PackageInstaller.SessionInfo ownedSession(Context context){int id=prefs(context).getInt("session",-1);return id<0?null:context.getPackageManager().getPackageInstaller().getSessionInfo(id);}
    private static boolean activeSession(Context context){PackageInstaller.SessionInfo info=ownedSession(context);return info!=null&&info.isActive();}
    /** A committed session survives our process, including while Android shows confirmation. */
    static boolean installPending(Context context){
        SharedPreferences p=prefs(context);String phase=p.getString("phase",IDLE);
        if(!INSTALLING.equals(phase)&&!CONFIRM.equals(phase))return false;
        if(p.getInt("session",-1)<0)return false;
        try{return ownedSession(context)!=null;}catch(RuntimeException unavailable){return true;}
    }
    private static boolean installerInFlight(Context context){
        PackageInstaller.SessionInfo info=ownedSession(context);long started=prefs(context).getLong("install_started",0);
        return UpdatePolicy.sessionMustWait(info!=null,info!=null&&info.isActive(),started,System.currentTimeMillis());
    }
    private static boolean canInstall(Context context){return Build.VERSION.SDK_INT<26||context.getPackageManager().canRequestPackageInstalls();}

    static void result(Context context,int session,String token,int status,String message,Intent confirm){
        SharedPreferences p=prefs(context);
        if(session<0||session!=p.getInt("session",-1)||token==null||!token.equals(p.getString("token","")))return;
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){
            busy=false;confirmation=confirm==null?null:new Intent(confirm);confirmationLaunched=false;
            state(context,CONFIRM,"Continue installation",confirm==null?"Tap to reopen Android's installation confirmation.":"Confirm the update in Android's installation screen.");
            Activity active=currentActivity();if(active!=null&&confirmation!=null)launchConfirmation(active);return;
        }
        busy=false;clearSession(context);
        if(status==PackageInstaller.STATUS_SUCCESS){clearPrepared(context);state(context,IDLE,"Update","Update installed. "+here(context));return;}
        String detail=status==PackageInstaller.STATUS_FAILURE_ABORTED?"Installation was cancelled. Tap to try again.":status==PackageInstaller.STATUS_FAILURE_STORAGE?"Android needs more free space to install the update.":status==PackageInstaller.STATUS_FAILURE_CONFLICT?"Android couldn't replace the installed app. Check that the release uses the same signing key.":status==PackageInstaller.STATUS_FAILURE_BLOCKED?"Android blocked installation. Check the install permission, then retry.":"The update wasn't installed. Tap to try again.";
        state(context,READY,"Continue update",detail);
    }
    private static void launchConfirmation(Activity activity){
        if(confirmation==null)return;
        if(!CaptureBarrier.reserveInstall()){state(activity,CONFIRM,"Continue installation","Finish recording or processing your audio before installing the update.");return;}
        if(audioBusy()){CaptureBarrier.releaseInstall();state(activity,CONFIRM,"Continue installation","Finish recording or processing your audio before installing the update.");return;}
        state(activity,CONFIRM,"Continue installation","Complete Android's installation screen. You can return here to continue.");
        try{activity.startActivity(new Intent(confirmation));confirmationLaunched=true;}
        catch(RuntimeException blocked){confirmationLaunched=false;state(activity,CONFIRM,"Continue installation","Tap Continue installation while VonoTation is open to confirm the update.");}
        // Keep the pending Intent and session until a terminal callback; background launch blocks may be silent.
    }

    private static void install(Context context,Release release) throws Exception {
        if(!CaptureBarrier.reserveInstall())throw new Deferred();
        boolean committed=false;
        try{prepareInstall(context,release);committed=true;}
        finally{if(!committed)CaptureBarrier.releaseInstall();}
    }
    private static void prepareInstall(Context context,Release release) throws Exception {
        if(!canInstall(context))throw new Problem("Installation permission changed. Tap Update to allow it again.");
        if(audioBusyOnMain(context))throw new Deferred();
        PackageInstaller installer=context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());params.setSize(release.size);
        if(Build.VERSION.SDK_INT>=31)params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        int id=installer.createSession(params);boolean committed=false;
        String token=UUID.randomUUID().toString();
        try{
            if(!prefs(context).edit().putInt("session",id).putString("token",token).commit())throw new Problem("Couldn't save the installer state. Try again.");
            try(PackageInstaller.Session session=installer.openSession(id)){
                try(InputStream input=new FileInputStream(apk(context));OutputStream output=session.openWrite("base.apk",0,release.size)){
                    byte[] buffer=new byte[65536];int n;long copied=0;
                    while((n=input.read(buffer))!=-1){if(n==0)continue;copied+=n;if(!UpdatePolicy.bounded(copied,release.size))throw new Problem("The cached update changed. Download it again.");output.write(buffer,0,n);}
                    if(copied!=release.size)throw new Problem("The cached update is incomplete. Download it again.");session.fsync(output);
                }
                if(audioBusyOnMain(context))throw new Deferred();
                Intent done=new Intent(context,UpdateResult.class).setAction("app.voiceflow.UPDATE_RESULT."+token).putExtra(EXTRA_SESSION,id).putExtra(EXTRA_TOKEN,token);
                int flags=PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0);
                if(!prefs(context).edit().putString("phase",INSTALLING).putString("title","Installing…").putString("detail","Waiting for Android's installation confirmation…").putLong("install_started",System.currentTimeMillis()).commit())throw new Problem("Couldn't save the installer state. Try again.");
                render(context);session.commit(PendingIntent.getBroadcast(context,id,done,flags).getIntentSender());committed=true;
                main(context).postDelayed(()->{if(prefs(context).getInt("session",-1)==id&&INSTALLING.equals(prefs(context).getString("phase",IDLE)))state(context,CONFIRM,"Continue installation","If Android's confirmation didn't appear, tap to continue.");},60000);
            }
        }finally{if(!committed){try{installer.abandonSession(id);}catch(RuntimeException ignored){}prefs(context).edit().remove("session").remove("token").commit();}}
    }
    private static void verify(Context context,Release release) throws Exception {
        File file=apk(context);if(!file.isFile()||file.length()!=release.size)throw new Problem("The downloaded update is incomplete. Download it again.");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long size=0;
        try(InputStream input=new FileInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1){size+=n;if(!UpdatePolicy.bounded(size,release.size))throw new Problem("The downloaded update is too large.");digest.update(buffer,0,n);}}
        if(!UpdatePolicy.integrity(size,release.size,UpdatePolicy.hex(digest.digest()),release.hash))throw new Problem("The update integrity check failed. Download it again.");
        PackageManager pm=context.getPackageManager();int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo downloaded=pm.getPackageArchiveInfo(file.getAbsolutePath(),flags),installed=pm.getPackageInfo(context.getPackageName(),flags);
        if(downloaded==null||!context.getPackageName().equals(downloaded.packageName))throw new Problem("The downloaded file isn't a VonoTation update.");
        long code=Build.VERSION.SDK_INT>=28?downloaded.getLongVersionCode():downloaded.versionCode;
        if(code!=release.code||!release.name.equals(downloaded.versionName)||code<=mine(context))throw new Problem("The update version doesn't match its release information.");
        if(!UpdatePolicy.sameSigners(signers(installed),signers(downloaded)))throw new Problem("This release uses a different signing key and cannot update the installed app.");
    }
    private static byte[][] signers(PackageInfo info){
        Signature[] values=Build.VERSION.SDK_INT>=28?(info.signingInfo==null?null:info.signingInfo.getApkContentsSigners()):info.signatures;
        if(values==null)return null;byte[][] out=new byte[values.length][];for(int i=0;i<values.length;i++)out[i]=values[i].toByteArray();return out;
    }
    private static void download(Context context,Release release) throws Exception {
        HttpURLConnection connection=open(release.url);File temporary=partial(context);boolean complete=false;long started=SystemClock.elapsedRealtime();
        try{
            long length=connection.getContentLengthLong();if(length>0&&length!=release.size)throw new Problem("The update download size doesn't match the release.");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long got=0;int lastPercent=-1;
            try(InputStream input=connection.getInputStream();FileOutputStream output=new FileOutputStream(temporary)){
                byte[] buffer=new byte[65536];int n;
                while((n=input.read(buffer))!=-1){if(n==0)continue;got+=n;if(!UpdatePolicy.bounded(got,release.size)||SystemClock.elapsedRealtime()-started>300000)throw new Problem("The update download exceeded its size or time limit.");output.write(buffer,0,n);digest.update(buffer,0,n);int percent=(int)(got*100/release.size);if(percent!=lastPercent){lastPercent=percent;state(context,DOWNLOADING,"Downloading…","Downloading "+release.name+"… "+percent+"%");}}
                output.getFD().sync();
            }
            if(!UpdatePolicy.integrity(got,release.size,UpdatePolicy.hex(digest.digest()),release.hash))throw new Problem("The update integrity check failed. Download it again.");
            File ready=apk(context);if(ready.exists()&&!ready.delete())throw new Problem("Couldn't replace the saved update.");if(!temporary.renameTo(ready))throw new Problem("Couldn't save the verified update.");complete=true;
        }finally{connection.disconnect();if(!complete)temporary.delete();}
    }
    private static String readFeed() throws Exception {
        HttpURLConnection connection=open(FEED);long started=SystemClock.elapsedRealtime();
        try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
            long declared=connection.getContentLengthLong();if(declared>UpdatePolicy.MAX_FEED)throw new Problem("The update information is too large.");byte[] buffer=new byte[4096];int n;
            while((n=input.read(buffer))!=-1){if(output.size()+n>UpdatePolicy.MAX_FEED||SystemClock.elapsedRealtime()-started>30000)throw new Problem("The update information exceeded its limit.");output.write(buffer,0,n);}
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
        }finally{connection.disconnect();}
    }
    private static HttpURLConnection open(String address) throws Exception {
        for(int redirects=0;redirects<=UpdatePolicy.MAX_REDIRECTS;redirects++){
            if(!UpdatePolicy.secureUrl(address))throw new Problem("The update server used an untrusted address.");
            HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(12000);connection.setReadTimeout(30000);connection.setRequestProperty("User-Agent","VonoTation-Updater");connection.setRequestProperty("Accept-Encoding","identity");
            try{
                int status=connection.getResponseCode();
                if(status==301||status==302||status==303||status==307||status==308){String location=connection.getHeaderField("Location");if(location==null||redirects==UpdatePolicy.MAX_REDIRECTS)throw new Problem("The update server redirected too many times.");address=new URI(address).resolve(location).toString();connection.disconnect();continue;}
                if(status!=200)throw new Problem(status==404?"No update release is available yet. Try again later.":"The update server is unavailable. Try again later.");
                String encoding=connection.getContentEncoding();if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw new Problem("The update server used an unsupported file encoding.");return connection;
            }catch(Exception failure){connection.disconnect();throw failure;}
        }
        throw new Problem("The update server redirected too many times.");
    }
    private static final class Release {
        final int code;final String name,url,hash,json;final long size;
        private Release(int code,String name,String url,String hash,long size,String json){this.code=code;this.name=name;this.url=url;this.hash=hash;this.size=size;this.json=json;}
        static Release parse(String raw) throws Exception {
            if(raw==null||raw.getBytes(StandardCharsets.UTF_8).length>UpdatePolicy.MAX_FEED)throw new Problem("Invalid update information.");
            JSONTokener parser=new JSONTokener(raw);Object parsed=parser.nextValue();if(!(parsed instanceof JSONObject)||parser.nextClean()!=0)throw new Problem("Invalid update information.");
            JSONObject value=(JSONObject)parsed;long code=integer(value.get("versionCode")),size=integer(value.get("size"));
            Object name=value.get("versionName"),url=value.get("apk"),hash=value.get("sha256");if(!(name instanceof String)||!(url instanceof String)||!(hash instanceof String))throw new Problem("The update information has invalid fields.");
            UpdatePolicy.validate(code,(String)name,(String)url,(String)hash,size);return new Release((int)code,(String)name,(String)url,(String)hash,size,raw);
        }
        private static long integer(Object value) throws Problem {if(!(value instanceof Number))throw new Problem("The update information has invalid numbers.");Number n=(Number)value;long whole=n.longValue();if(n.doubleValue()!=whole)throw new Problem("The update information has invalid numbers.");return whole;}
    }
    private static Release saved(Context context){try{return Release.parse(prefs(context).getString("release",""));}catch(Exception invalid){return null;}}
    private static void clearPrepared(Context context){apk(context).delete();partial(context).delete();prefs(context).edit().remove("release").apply();}
    private static void clearSession(Context context){
        int id=prefs(context).getInt("session",-1);if(id>=0)try{PackageInstaller.SessionInfo info=ownedSession(context);if(info==null||!info.isActive()||!info.isSealed())context.getPackageManager().getPackageInstaller().abandonSession(id);}catch(RuntimeException ignored){}
        prefs(context).edit().remove("session").remove("token").remove("install_started").commit();confirmation=null;confirmationLaunched=false;
        CaptureBarrier.releaseInstall();
    }
    private static void fail(Context context,String message,boolean discard){busy=false;clearSession(context);if(discard)clearPrepared(context);else partial(context).delete();state(context,ERROR,"Update",message);}
    private static String problem(Exception error,String fallback){return error instanceof Problem||error instanceof IllegalArgumentException?error.getMessage():fallback;}
    private static final class Deferred extends Exception{}
    private static final class Problem extends Exception{Problem(String message){super(message);}}
    private static long mine(Context context){try{PackageInfo info=context.getPackageManager().getPackageInfo(context.getPackageName(),0);return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;}catch(PackageManager.NameNotFoundException missing){return 0;}}
    private static String here(Context context){try{return "VonoTation "+context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;}catch(PackageManager.NameNotFoundException missing){return "VonoTation";}}
    private static void state(Context context,String phase,String title,String detail){prefs(context).edit().putString("phase",phase).putString("title",title).putString("detail",detail).apply();render(context);}
    private static void render(Context context){main(context).post(()->{SharedPreferences p=prefs(context);Button action=button==null?null:button.get();TextView status=line==null?null:line.get();if(action!=null&&action.isAttachedToWindow()){action.setText(p.getString("title","Update"));action.setEnabled(!busy);}if(status!=null&&status.isAttachedToWindow())status.setText(p.getString("detail",here(context)));});}
}
