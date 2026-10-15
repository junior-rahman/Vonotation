package app.voiceflow;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

/** Notices when wireless debugging drops, so both-sides call recording is not silently gone. */
final class LineWatch {
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService probe=java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final int NOTICE=47;
    private static boolean watching,up,settled,stale,greeted;
    private static boolean shownUp,shownStale,shownSettled;
    private static long trustUntil,ignoreLossUntil,seenAt,suppressLossUntil;
    private static int misses,rechecks;
    private static String provenHost;
    private static int provenPort;
    private static final Runnable confirmDownTask=LineWatch::confirmDown;
    private static Runnable onChange;
    private static NsdManager nsd;
    private static NsdManager.DiscoveryListener discovery;
    private static Context app;
    private LineWatch() {}

    static boolean up(){return up;}
    static boolean settled(){return settled;}
    /** True after debugging dropped or came back on a port this app can no longer use. */
    static boolean needsReconnect(){return stale;}

    static void cleared(Context context) {
        long now=System.currentTimeMillis();
        stale=false;trustUntil=now+20000;ignoreLossUntil=now+5000;
        cancel(context);
        publish();
    }

    static void start(Context context,Runnable changed) {
        app=context.getApplicationContext();
        LineLink.INSTANCE.install(app);
        onChange=changed;
        if(!LineWire.paired(app)){settled=true;up=false;stale=false;LineWire.forget();publish();return;}
        if(watching)return;
        // A new process, including right after an update, rediscovers debugging.
        // That first flap is not a disconnect, so it does not notify.
        if(!greeted){
            greeted=true;
            long now=System.currentTimeMillis();
            trustUntil=now+20000;ignoreLossUntil=now+20000;
            stale=false;cancel(app);
        }
        nsd=(NsdManager)app.getSystemService(Context.NSD_SERVICE);
        if(nsd==null){mark(false);return;}
        listen();
    }

    /** Lets pairing look up its own port without fighting the connect listener. */
    static void hold() {
        java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        main.post(()->{
            if(nsd!=null && discovery!=null){try{nsd.stopServiceDiscovery(discovery);}catch(RuntimeException ignored){}}
            watching=false;discovery=null;done.countDown();
        });
        try{done.await(2,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ignored){}
    }

    static void resume() {
        main.post(()->{
            if(app==null || watching || !LineWire.paired(app))return;
            if(nsd==null)nsd=(NsdManager)app.getSystemService(Context.NSD_SERVICE);
            listen();
        });
    }

    private static void listen() {
        if(nsd==null || app==null){mark(false);return;}
        discovery=new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String type) {}
            public void onDiscoveryStopped(String type) {}
            public void onStartDiscoveryFailed(String type,int error){watching=false;mark(false);}
            public void onStopDiscoveryFailed(String type,int error) {}
            public void onServiceFound(NsdServiceInfo info){resolve(info);}
            public void onServiceLost(NsdServiceInfo info){lost();}
        };
        try{nsd.discoverServices("_adb-tls-connect._tcp.",NsdManager.PROTOCOL_DNS_SD,discovery);watching=true;}
        catch(RuntimeException error){watching=false;mark(false);}
    }

    private static void resolve(NsdServiceInfo info) {
        if(nsd==null)return;
        try {
            nsd.resolveService(info,new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo failed,int error) {}
                public void onServiceResolved(NsdServiceInfo resolved) {
                    if(resolved.getHost()==null)return;
                    LineWire.End end=new LineWire.End();
                    end.host=resolved.getHost().getHostAddress();
                    end.port=resolved.getPort();
                    LineWire.remember(end);
                    seen(end.host,end.port);
                    int port=end.port;
                    String host=end.host;
                    if(host!=null && host.equals(provenHost) && port==provenPort){stale=false;misses=0;cancel(app);publish();return;}
                    long wait=Math.max(0,trustUntil-System.currentTimeMillis());
                    main.postDelayed(()->probe.execute(()->check(host,port)),wait);
                }
            });
        } catch(RuntimeException ignored) {}
    }

    private static void check(String host,int port){
        if(LineWire.running())return;
        String state=LineLink.INSTANCE.reachable(host,port);
        main.post(()->{
            LineWire.End now=LineWire.current();
            if(!up || now==null || now.port!=port)return;
            if("ok".equals(state)){provenHost=host;provenPort=port;misses=0;stale=false;cancel(app);publish();return;}
            // A timeout is not a disconnect. Only a rejected key means pairing is dead.
            if(!"auth".equals(state))return;
            misses++;
            if(misses<3){main.postDelayed(()->probe.execute(()->check(host,port)),30000);return;}
            misses=0;
            stale=true;
            warn(app,"VonoTation is disconnected","Wireless debugging is on, but calls won't hear both sides until you pair again.");
            publish();
        });
    }

    private static void seen(String host,int port){
        seenAt=System.currentTimeMillis();
        rechecks=0;
        up=true;settled=true;
        main.removeCallbacks(confirmDownTask);
        if(!stale)cancel(app);
        publish();
    }

    private static void lost() {
        if(System.currentTimeMillis()<suppressLossUntil)return;
        if(System.currentTimeMillis()<ignoreLossUntil)return;
        // The service re-announces on its own. One loss is not proof it was turned off.
        main.removeCallbacks(confirmDownTask);
        main.postDelayed(confirmDownTask,60000);
    }

    /** Notifies only after a loss stays gone through a fresh search. */
    private static void confirmDown(){
        if(app==null || System.currentTimeMillis()<ignoreLossUntil)return;
        if(System.currentTimeMillis()-seenAt<45000)return;
        if(rechecks<2){rechecks++;restart();main.postDelayed(confirmDownTask,30000);return;}
        rechecks=0;
        if(System.currentTimeMillis()-seenAt<20000)return;
        trustUntil=0;
        provenHost=null;provenPort=0;
        up=false;settled=true;stale=true;
        warn(app,"Wireless debugging is off","Call recording can't hear both sides until you turn it back on and pair again.");
        publish();
    }

    private static void restart() {
        suppressLossUntil=System.currentTimeMillis()+4000;
        if(nsd!=null && discovery!=null){try{nsd.stopServiceDiscovery(discovery);}catch(RuntimeException ignored){}}
        watching=false;discovery=null;
        if(app!=null && LineWire.paired(app))listen();
    }

    private static void mark(boolean live) {
        if(live){seenAt=System.currentTimeMillis();up=true;settled=true;cancel(app);publish();return;}
        if(System.currentTimeMillis()<ignoreLossUntil)return;
        main.removeCallbacks(confirmDownTask);
        main.postDelayed(confirmDownTask,60000);
    }

    private static void publish() {
        if(shownUp==up && shownStale==stale && shownSettled==settled)return;
        shownUp=up;shownStale=stale;shownSettled=settled;
        Runnable changed=onChange;
        if(changed!=null)main.post(changed);
    }

    private static void warn(Context context,String title,String text) {
        if(context==null || !LineWire.paired(context))return;
        NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if(manager==null)return;
        if(android.os.Build.VERSION.SDK_INT>=26){
            NotificationChannel channel=new NotificationChannel("line","Wireless debugging",NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Warns when call recording loses wireless debugging");
            manager.createNotificationChannel(channel);
        }
        Intent open=new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open_calls",true);
        PendingIntent tap=PendingIntent.getActivity(context,NOTICE,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notice=new Notification.Builder(context,"line")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build();
        try{manager.notify(NOTICE,notice);}catch(SecurityException ignored){}
    }

    private static void cancel(Context context) {
        if(context==null)return;
        NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if(manager!=null)manager.cancel(NOTICE);
    }
}
