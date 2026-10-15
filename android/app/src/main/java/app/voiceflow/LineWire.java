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
    private static final class Session {
        volatile boolean going=true;
        volatile AutoCloseable link;
        Thread worker;
        final java.util.concurrent.atomic.AtomicBoolean ended=new java.util.concurrent.atomic.AtomicBoolean();
    }
    private static volatile Session session;
    private static final java.util.concurrent.atomic.AtomicInteger pending=new java.util.concurrent.atomic.AtomicInteger();
    private LineWire() {}

    static boolean paired(Context context){return Store.bool(context,"line_paired",false);}
    static boolean running(){Session active=session;return active!=null && active.going;}
    /** Includes stop-time encoding and handing the saved audio to Notes. */
    static boolean updateBusy(){return pending.get()>0;}

    static final class End { String host; int port; }
    private static volatile End cached;
    static End current(){return cached;}
    static void remember(End end){cached=end;}
    static void forget(){cached=null;}

    /** Only this phone's ADB endpoint is relevant; nearby phones advertise the same service type. */
    static boolean local(java.net.InetAddress host) {
        if(host==null)return false;
        if(host.isLoopbackAddress())return true;
        try{return java.net.NetworkInterface.getByInetAddress(host)!=null;}
        catch(java.net.SocketException error){return false;}
    }

    static End find(Context context,String type) {
        NsdManager nsd=(NsdManager)context.getSystemService(Context.NSD_SERVICE);
        if(nsd==null)return null;
        if(type.contains("connect")){End known=cached;if(known!=null)return known;}
        CountDownLatch latch=new CountDownLatch(1);
        AtomicReference<End> found=new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean finished=new java.util.concurrent.atomic.AtomicBoolean();
        class Resolver {
            final java.util.ArrayDeque<NsdServiceInfo> queue=new java.util.ArrayDeque<>();
            boolean resolving;
            void add(NsdServiceInfo info){if(finished.get())return;queue.add(info);next();}
            void next(){
                if(finished.get() || resolving || queue.isEmpty())return;
                NsdServiceInfo info=queue.removeFirst();resolving=true;
                try{nsd.resolveService(info,new NsdManager.ResolveListener(){
                    public void onResolveFailed(NsdServiceInfo failed,int error){main.post(()->{
                        resolving=false;
                        if(error==NsdManager.FAILURE_ALREADY_ACTIVE){queue.addLast(info);main.postDelayed(()->next(),250);}
                        else next();
                    });}
                    public void onServiceResolved(NsdServiceInfo resolved){main.post(()->{
                        resolving=false;
                        if(finished.get())return;
                        if(local(resolved.getHost()) && resolved.getPort()>0){
                            End end=new End();end.host=resolved.getHost().getHostAddress();end.port=resolved.getPort();
                            if(found.compareAndSet(null,end))latch.countDown();
                        }else next();
                    });}
                });}catch(RuntimeException error){resolving=false;main.post(()->next());}
            }
        }
        Resolver resolver=new Resolver();
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
                main.post(()->resolver.add(serviceInfo));
            }
        };
        main.post(()->{try{nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,discovery);}catch(RuntimeException error){latch.countDown();}});
        try{latch.await(6,TimeUnit.SECONDS);}catch(InterruptedException ignored){}
        finished.set(true);
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
            forget();
            LineWatch.cleared(context);
        } finally {LineWatch.resume();}
    }

    private static boolean closed(Exception error){
        String message=error.getMessage()==null?"":error.getMessage().toLowerCase();
        return message.contains("closed") || message.contains("reset") || message.contains("refused") || message.contains("abort");
    }

    static synchronized boolean start(Context context,File dest,Callback done) {
        if(running() || !paired(context))return false;
        Session active=new Session();session=active;
        pending.incrementAndGet();
        active.worker=new Thread(()->run(context,dest,done,active),"line-call");
        active.worker.start();
        return true;
    }

    static void stop() {
        Session active=session;
        if(active==null || !active.going)return;
        active.going=false;
        Runnable close=()->{
            AutoCloseable current=active.link;
            if(current!=null)try{current.close();}catch(Exception ignored){}
            Thread thread=active.worker;
            if(thread!=null && thread!=Thread.currentThread())try{thread.join(4000);}catch(InterruptedException ignored){}
        };
        if(Looper.myLooper()==Looper.getMainLooper())new Thread(close,"line-stop").start();
        else close.run();
    }

    private static void run(Context context,File dest,Callback done,Session active) {
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
            if(end==null || !active.going){finish(active,done,false);return;}
            Exception failed=null;
            for(int attempt=0;attempt<2 && active.going && connection==null;attempt++){
                if(attempt>0){
                    try{Thread.sleep(700);}catch(InterruptedException ignored){}
                    LineWatch.hold();
                    try{forget();End again=find(context,"_adb-tls-connect._tcp.");if(again!=null)end=again;}
                    finally{LineWatch.resume();}
                }
                try{
                    PipedInputStream read=new PipedInputStream(64*1024);
                    PipedOutputStream write=new PipedOutputStream(read);
                    connection=LineLink.INSTANCE.record(end.host,end.port,write);
                    active.link=connection;
                    failed=null;
                    InputStream in=read;
                    ByteArrayOutputStream pending=new ByteArrayOutputStream();
                    byte[] buf=new byte[4096];
                    int rate=16000,channels=1;
                    boolean headed=false;
                    while(active.going){
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
                    connection=null;active.link=null;
                }
            }
            if(failed!=null)throw failed;
        } catch(Exception ignored) {}
        if(aac!=null)aac.close();
        if(connection!=null)try{connection.close();}catch(Exception ignored){}
        active.link=null;
        finish(active,done,wrote && dest.isFile() && dest.length()>1000);
    }

    private static synchronized void finish(Session active,Callback done,boolean ok) {
        if(!active.ended.compareAndSet(false,true))return;
        active.going=false;active.worker=null;
        main.post(()->{
            try{if(done!=null){if(ok)done.saved();else done.failed();}}
            finally{
                pending.decrementAndGet();
                synchronized(LineWire.class){if(session==active)session=null;}
            }
        });
    }

    private static int index(byte[] all) {
        for(int i=0;i+1<all.length;i++)if(all[i]=='V' && all[i+1]=='F')return i;
        return -1;
    }
}
