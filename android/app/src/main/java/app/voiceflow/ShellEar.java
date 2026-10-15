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
    private static boolean going;
    private static ICallCapture capture;
    private static ServiceConnection connection;
    private static Shizuku.UserServiceArgs args;
    private static Callback callback;
    private static File dest;
    private static Thread reader;
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

    static boolean running() { return going; }

    static void request(int code) { try { Shizuku.requestPermission(code); } catch(Throwable ignored) {} }

    static void listen(Shizuku.OnRequestPermissionResultListener listener) {
        try { Shizuku.addRequestPermissionResultListener(listener); }
        catch(Throwable ignored) {}
    }

    static boolean start(Context context,File file,Callback done) {
        if(going)return true;
        if(!ready())return false;
        going=true;dest=file;callback=done;
        ParcelFileDescriptor[] pipe;
        try { pipe=ParcelFileDescriptor.createPipe(); }
        catch(Exception error) { going=false;callback=null;done.failed();return false; }
        ParcelFileDescriptor read=pipe[0],write=pipe[1];
        args=new Shizuku.UserServiceArgs(new ComponentName(context.getPackageName(),CallShell.class.getName()))
            .daemon(false).processNameSuffix("call").debuggable(false).version(1);
        connection=new ServiceConnection() {
            public void onServiceConnected(ComponentName name,IBinder binder) {
                if(!going){release();return;}
                capture=ICallCapture.Stub.asInterface(binder);
                reader=new Thread(()->pull(read,write),"call-read");
                reader.start();
            }
            public void onServiceDisconnected(ComponentName name) { capture=null; }
        };
        try { Shizuku.bindUserService(args,connection); }
        catch(Throwable error) {
            try{read.close();}catch(Exception ignored){}
            try{write.close();}catch(Exception ignored){}
            going=false;callback=null;done.failed();return false;
        }
        return true;
    }

    static void stop() {
        going=false;
        ICallCapture live=capture;
        if(live!=null)try{live.close();}catch(Exception ignored){}
        Thread worker=reader;
        if(worker!=null && worker!=Thread.currentThread())try{worker.join(5000);}catch(InterruptedException ignored){}
        release();
    }

    private static void pull(ParcelFileDescriptor read,ParcelFileDescriptor write) {
        AacFile aac=null;
        File file=dest;
        boolean wrote=false;
        try {
            int spec=capture==null?0:capture.open(write);
            try{write.close();}catch(Exception ignored){}
            if(spec==0 || file==null){finish(false);return;}
            int rate=spec & 0xffffff,channels=Math.max(1,spec>>24);
            aac=new AacFile(file,rate,channels);
            try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(read)){
                byte[] buf=new byte[4096];
                int n;
                while((n=in.read(buf))>=0){if(n>0){aac.write(buf,n);wrote=true;}}
            }
        } catch(Exception ignored) {}
        if(aac!=null)aac.close();
        finish(wrote && file!=null && file.isFile() && file.length()>1000);
    }

    private static void finish(boolean ok) {
        Callback done=callback;callback=null;going=false;reader=null;
        release();
        if(done!=null){if(ok)done.saved();else done.failed();}
    }

    private static void release() {
        Shizuku.UserServiceArgs current=args;ServiceConnection bound=connection;
        args=null;connection=null;capture=null;
        if(current!=null && bound!=null)try{Shizuku.unbindUserService(current,bound,true);}catch(Throwable ignored){}
    }
}
