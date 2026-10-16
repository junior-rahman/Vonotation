package app.voiceflow;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.InputStream;
import rikka.shizuku.Shizuku;

/** Records both sides of a call, earpiece included, through Shizuku's shell process. */
final class ShellEar {
    interface Callback { void saved(); void failed(); }
    private static final String SHIZUKU="moe.shizuku.privileged.api";
    private static final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    private static final class Session {
        final long owner;
        final File dest;
        final Callback callback;
        final CaptureGate.Lease lease;
        final java.util.concurrent.atomic.AtomicBoolean ended=new java.util.concurrent.atomic.AtomicBoolean();
        volatile boolean going=true;
        volatile ICallCapture capture;
        volatile Thread reader;
        Shizuku.UserServiceArgs args;
        ServiceConnection connection;
        ParcelFileDescriptor read,write;
        Session(long owner,File dest,Callback callback,CaptureGate.Lease lease){this.owner=owner;this.dest=dest;this.callback=callback;this.lease=lease;}
    }
    private static volatile Session session;
    private ShellEar() {}

    static boolean installed(Context context) {
        try { context.getPackageManager().getPackageInfo(SHIZUKU,0); return true; }
        catch(Exception error) { return false; }
    }

    static int stage(Context context) {
        if(!installed(context))return 0;
        try {
            if(!Shizuku.pingBinder())return 1;
            if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED)return 2;
            return 3;
        } catch(Throwable error) { return 1; }
    }

    static boolean ready() {
        try { return Shizuku.pingBinder() && Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED; }
        catch(Throwable error) { return false; }
    }

    static boolean running() { Session active=session;return active!=null && active.going; }
    static boolean updateBusy(){return session!=null;}

    static void request(int code) { try { Shizuku.requestPermission(code); } catch(Throwable ignored) {} }

    static void listen(Shizuku.OnRequestPermissionResultListener listener) {
        try { Shizuku.addRequestPermissionResultListener(listener); }
        catch(Throwable ignored) {}
    }

    static synchronized boolean start(Context context,long owner,File file,Callback done) {
        if(session!=null || !ready())return false;
        CaptureGate.Lease lease=CaptureBarrier.begin(context);
        if(lease==null)return false;
        Session active=new Session(owner,file,done,lease);
        try{
            ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
            active.read=pipe[0];active.write=pipe[1];
        }catch(Exception error){lease.close();return false;}
        active.args=new Shizuku.UserServiceArgs(new ComponentName(context.getPackageName(),CallShell.class.getName()))
            .daemon(false).processNameSuffix("call").debuggable(false).version(1);
        active.connection=new ServiceConnection() {
            public void onServiceConnected(ComponentName name,IBinder binder) {
                ICallCapture capture=ICallCapture.Stub.asInterface(binder);
                if(session!=active || !active.going){try{capture.close();}catch(Exception ignored){}release(active);return;}
                active.capture=capture;
                active.reader=new Thread(()->pull(active),"call-read");
                active.reader.start();
            }
            public void onServiceDisconnected(ComponentName name){active.capture=null;closePipes(active);}
        };
        session=active;
        try{Shizuku.bindUserService(active.args,active.connection);}
        catch(Throwable error){session=null;closePipes(active);release(active);lease.close();return false;}
        return true;
    }

    static void stop(){Session active=session;if(active!=null)stop(active.owner);}

    static void stop(long owner) {
        Session active=session;
        if(active==null || active.owner!=owner)return;
        synchronized(active){if(!active.going)return;active.going=false;}
        new Thread(()->{
            ICallCapture live=active.capture;
            if(live!=null)try{live.close();}catch(Exception ignored){}
            closePipes(active);
            // A bind that has not delivered its binder still needs its lease and descriptors released.
            if(active.reader==null)finish(active,false);
        },"shell-call-stop").start();
    }

    private static void pull(Session active) {
        AacFile aac=null;
        boolean wrote=false;
        try {
            ICallCapture capture=active.capture;
            int spec=capture==null?0:capture.open(active.write);
            try{active.write.close();}catch(Exception ignored){}
            if(spec!=0 && active.dest!=null){
                int rate=spec & 0xffffff,channels=Math.max(1,spec>>24);
                aac=new AacFile(active.dest,rate,channels);
                try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(active.read)){
                    byte[] buf=new byte[4096];
                    int n;
                    while(active.going && (n=in.read(buf))>=0){if(n>0){aac.write(buf,n);wrote=true;}}
                }
            }
        } catch(Exception ignored) {}
        finally{
            if(aac!=null)aac.close();
            closePipes(active);
            finish(active,wrote && active.dest!=null && active.dest.isFile() && active.dest.length()>1000);
        }
    }

    private static void finish(Session active,boolean ok) {
        if(!active.ended.compareAndSet(false,true))return;
        active.going=false;
        release(active);
        main.post(()->{
            synchronized(ShellEar.class){if(session==active)session=null;}
            try{if(active.callback!=null){if(ok)active.callback.saved();else active.callback.failed();}}
            finally{active.lease.close();}
        });
    }

    private static void closePipes(Session active){
        try{if(active.read!=null)active.read.close();}catch(Exception ignored){}
        try{if(active.write!=null)active.write.close();}catch(Exception ignored){}
    }

    private static void release(Session active) {
        Shizuku.UserServiceArgs current=active.args;ServiceConnection bound=active.connection;
        active.args=null;active.connection=null;active.capture=null;
        if(current!=null && bound!=null)try{Shizuku.unbindUserService(current,bound,true);}catch(Throwable ignored){}
    }
}
