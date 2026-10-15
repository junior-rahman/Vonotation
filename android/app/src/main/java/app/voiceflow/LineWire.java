package app.voiceflow;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Earpiece call audio through wireless debugging. The connection exists only while a call does. */
final class LineWire {
    interface Callback { void saved(); void failed(); }
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static volatile boolean going;
    private static Thread worker;
    private static AutoCloseable link;
    private LineWire() {}

    static boolean paired(Context context){return Store.bool(context,"line_paired",false);}
    static boolean running(){return going;}

    static final class End { String host; int port; }
    private static volatile End cached;
    static End current(){return cached;}
    static void remember(End end){cached=end;}
    static void forget(){cached=null;}

    static End find(Context context,String type) {
        NsdManager nsd=(NsdManager)context.getSystemService(Context.NSD_SERVICE);
        if(nsd==null)return null;
        if(type.contains("connect")){End known=cached;if(known!=null)return known;}
        CountDownLatch latch=new CountDownLatch(1);
        AtomicReference<End> found=new AtomicReference<>();
        NsdManager.DiscoveryListener discovery=new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String serviceType) {}
            public void onDiscoveryStopped(String serviceType) {}
            public void onStartDiscoveryFailed(String serviceType,int error){latch.countDown();}
            public void onStopDiscoveryFailed(String serviceType,int error) {}
            public void onServiceLost(NsdServiceInfo serviceInfo) {}
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                String got=serviceInfo.getServiceType()==null?"":serviceInfo.getServiceType();
                if(got.length()>0 && type.contains("pairing") && !got.contains("pairing"))return;
                if(got.length()>0 && type.contains("connect") && got.contains("pairing"))return;
                nsd.resolveService(serviceInfo,new NsdManager.ResolveListener() {
                    public void onResolveFailed(NsdServiceInfo info,int error) {}
                    public void onServiceResolved(NsdServiceInfo info) {
                        if(found.get()!=null || info.getHost()==null)return;
                        End end=new End();end.host=info.getHost().getHostAddress();end.port=info.getPort();
                        found.set(end);latch.countDown();
                    }
                });
            }
        };
        main.post(()->{try{nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,discovery);}catch(RuntimeException error){latch.countDown();}});
        try{latch.await(6,TimeUnit.SECONDS);}catch(InterruptedException ignored){}
        main.post(()->{try{nsd.stopServiceDiscovery(discovery);}catch(RuntimeException ignored){}});
        return found.get();
    }

    static void pair(Context context,String code) throws Exception {
        LineWatch.hold();
        try{
            End end=find(context,"_adb-tls-pairing._tcp.");
            if(end==null)throw new IllegalStateException("Open Pair device with pairing code, then try again.");
            LineLink.INSTANCE.install(context);
            try{LineLink.INSTANCE.pair(end.host,end.port,code.trim());}
            catch(Exception error){
                if("127.0.0.1".equals(end.host) || !closed(error))throw error;
                LineLink.INSTANCE.pair("127.0.0.1",end.port,code.trim());
            }
            Store.prefs(context).edit().putBoolean("line_paired",true).apply();
            LineWatch.cleared(context);
        } finally {LineWatch.resume();}
    }

    private static boolean closed(Exception error){
        String message=error.getMessage()==null?"":error.getMessage().toLowerCase();
        return message.contains("closed") || message.contains("reset") || message.contains("refused") || message.contains("abort");
    }

    private static final java.util.concurrent.atomic.AtomicBoolean ended=new java.util.concurrent.atomic.AtomicBoolean();

    static boolean start(Context context,File dest,Callback done) {
        if(going || !paired(context))return false;
        ended.set(false);going=true;
        worker=new Thread(()->run(context,dest,done),"line-call");
        worker.start();
        return true;
    }

    static void stop() {
        going=false;
        AutoCloseable current=link;
        if(current!=null)try{current.close();}catch(Exception ignored){}
        Thread thread=worker;
        if(thread!=null && thread!=Thread.currentThread())try{thread.join(4000);}catch(InterruptedException ignored){}
    }

    private static void run(Context context,File dest,Callback done) {
        AutoCloseable connection=null;
        AacFile aac=null;
        boolean wrote=false;
        try {
            LineLink.INSTANCE.install(context);
            End end=current();
            if(end==null){
                LineWatch.hold();
                try{end=find(context,"_adb-tls-connect._tcp.");}
                finally{LineWatch.resume();}
            }
            if(end==null || !going){finish(done,false);return;}
            Exception failed=null;
            for(int attempt=0;attempt<2 && going && connection==null;attempt++){
                if(attempt>0){
                    try{Thread.sleep(700);}catch(InterruptedException ignored){}
                    LineWatch.hold();
                    try{End again=find(context,"_adb-tls-connect._tcp.");if(again!=null)end=again;}
                    finally{LineWatch.resume();}
                }
                try{
                    PipedInputStream read=new PipedInputStream(64*1024);
                    PipedOutputStream write=new PipedOutputStream(read);
                    connection=LineLink.INSTANCE.record(end.host,end.port,write);
                    link=connection;
                    failed=null;
                    InputStream in=read;
                    ByteArrayOutputStream pending=new ByteArrayOutputStream();
                    byte[] buf=new byte[4096];
                    int rate=16000,channels=1;
                    boolean headed=false;
                    while(going){
                        int n=in.read(buf);
                        if(n<0)break;
                        if(n==0)continue;
                        if(!headed){
                            pending.write(buf,0,n);
                            byte[] all=pending.toByteArray();
                            int at=index(all);
                            if(at<0 || all.length<at+10)continue;
                            rate=((all[at+2]&255)<<24)|((all[at+3]&255)<<16)|((all[at+4]&255)<<8)|(all[at+5]&255);
                            channels=all[at+9]&255;
                            if(rate<8000 || rate>48000 || channels<1 || channels>2)break;
                            aac=new AacFile(dest,rate,channels);
                            int from=at+10;
                            if(all.length>from){byte[] rest=new byte[all.length-from];System.arraycopy(all,from,rest,0,rest.length);aac.write(rest,rest.length);}
                            headed=true;wrote=true;
                            continue;
                        }
                        aac.write(buf,n);wrote=true;
                    }
                    break;
                }catch(Exception error){
                    failed=error;
                    if(connection!=null)try{connection.close();}catch(Exception ignored){}
                    connection=null;link=null;
                }
            }
            if(failed!=null)throw failed;
        } catch(Exception ignored) {}
        if(aac!=null)aac.close();
        if(connection!=null)try{connection.close();}catch(Exception ignored){}
        link=null;
        finish(done,wrote && dest.isFile() && dest.length()>1000);
    }

    private static void finish(Callback done,boolean ok) {
        if(!ended.compareAndSet(false,true))return;
        going=false;worker=null;
        if(done!=null){if(ok)done.saved();else done.failed();}
    }

    private static int index(byte[] all) {
        for(int i=0;i+1<all.length;i++)if(all[i]=='V' && all[i+1]=='F')return i;
        return -1;
    }
}
