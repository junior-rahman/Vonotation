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
import android.os.SystemClock;

/** Notices when wireless debugging drops, so both-sides call recording is not silently gone. */
final class LineWatch {
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService probe=java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final int NOTICE=47;
    private static boolean watching,up,settled,stale,greeted;
    private static boolean shownUp,shownStale,shownSettled;
    private static long trustUntil,ignoreLossUntil,seenAt,suppressLossUntil;
    private static int rechecks;
    private static String selectedService;
    private static final LineConnectionState connection=new LineConnectionState();
    private static final LineNoticePolicy noticeState=new LineNoticePolicy();
    private static final Runnable confirmHealthyTask=LineWatch::confirmHealthy;
    private static final Runnable confirmDownTask=LineWatch::confirmDown;
    private static Runnable onChange;
    private static NsdManager nsd;
    private static NsdManager.DiscoveryListener discovery;
    private static final java.util.ArrayDeque<NsdServiceInfo> resolvingQueue=new java.util.ArrayDeque<>();
    private static boolean resolving;
    private static Context app;
    private LineWatch() {}

    static boolean up(){return up;}
    static boolean settled(){return settled;}
    /** True after debugging dropped or came back on a port this app can no longer use. */
    static boolean needsReconnect(){return stale;}

    static void cleared(Context context) {
        main.post(()->{
            long now=SystemClock.elapsedRealtime();
            stale=false;trustUntil=now+20000;ignoreLossUntil=now+5000;
            connection.clear();resetNotice(context);publish();
        });
    }

    static void start(Context context,Runnable changed) {
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(()->start(context,changed));return;}
        app=context.getApplicationContext();
        LineLink.INSTANCE.install(app);
        if(changed!=null)onChange=changed;
        if(!CallWatch.on(app) || !LineWire.paired(app)){
            holdDiscovery();main.removeCallbacks(confirmDownTask);
            settled=true;up=false;stale=false;LineWire.forget();connection.clear();
            greeted=false;seenAt=0;rechecks=0;selectedService=null;
            resetNotice(app);publish();return;
        }
        if(watching)return;
        // A new process, including right after an update, rediscovers debugging.
        // That first flap is not a disconnect, so it does not notify.
        if(!greeted){
            greeted=true;
            long now=SystemClock.elapsedRealtime();
            trustUntil=now+20000;ignoreLossUntil=now+20000;
            stale=false;
            // A fresh process may receive no onServiceLost event at all.
            main.postDelayed(confirmDownTask,21000);
        }
        nsd=(NsdManager)app.getSystemService(Context.NSD_SERVICE);
        if(nsd==null){mark(false);return;}
        listen();
    }

    static void detach(Runnable changed){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(()->detach(changed));return;}
        if(onChange==changed)onChange=null;
    }

    /** Lets pairing look up its own port without fighting the connect listener. */
    static void hold() {
        if(Looper.myLooper()==Looper.getMainLooper()){holdDiscovery();return;}
        java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        main.post(()->{
            holdDiscovery();done.countDown();
        });
        try{done.await(2,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ignored){}
    }

    static void resume() {
        main.post(()->{
            if(app==null || watching || !CallWatch.on(app) || !LineWire.paired(app))return;
            if(nsd==null)nsd=(NsdManager)app.getSystemService(Context.NSD_SERVICE);
            listen();
        });
    }

    private static void listen() {
        if(nsd==null || app==null){mark(false);return;}
        NsdManager.DiscoveryListener listener=new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String type) {}
            public void onDiscoveryStopped(String type) {}
            public void onStartDiscoveryFailed(String type,int error){main.post(()->{if(discovery!=this)return;watching=false;mark(false);});}
            public void onStopDiscoveryFailed(String type,int error) {}
            public void onServiceFound(NsdServiceInfo info){main.post(()->{if(discovery==this)resolve(info,this);});}
            public void onServiceLost(NsdServiceInfo info){main.post(()->{if(discovery==this && selectedService!=null && selectedService.equals(info.getServiceName()))lost();});}
        };
        discovery=listener;
        try{nsd.discoverServices("_adb-tls-connect._tcp.",NsdManager.PROTOCOL_DNS_SD,listener);watching=true;}
        catch(RuntimeException error){watching=false;mark(false);}
    }

    private static void resolve(NsdServiceInfo info,NsdManager.DiscoveryListener owner) {
        resolvingQueue.add(info);
        resolveNext(owner);
    }

    /** Older NSD implementations allow only one outstanding resolve request. */
    private static void resolveNext(NsdManager.DiscoveryListener owner) {
        if(nsd==null || discovery!=owner || resolving || resolvingQueue.isEmpty())return;
        NsdServiceInfo info=resolvingQueue.removeFirst();
        resolving=true;
        try {
            nsd.resolveService(info,new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo failed,int error) {
                    main.post(()->{
                        if(discovery!=owner)return;
                        resolving=false;
                        if(error==NsdManager.FAILURE_ALREADY_ACTIVE){resolvingQueue.addLast(info);main.postDelayed(()->resolveNext(owner),500);}
                        else resolveNext(owner);
                    });
                }
                public void onServiceResolved(NsdServiceInfo resolved) {
                    main.post(()->{
                    if(discovery!=owner)return;
                    resolving=false;
                    if(!LineWire.local(resolved.getHost()) || resolved.getPort()<=0){resolveNext(owner);return;}
                    LineWire.End end=new LineWire.End();
                    end.host=resolved.getHost().getHostAddress();
                    end.port=resolved.getPort();
                    LineWire.remember(end);
                    selectedService=resolved.getServiceName();
                    if(connection.select(end.host,end.port)){up=false;settled=false;stale=false;noticeState.outage();}
                    seen(end.host,end.port);
                    int port=end.port;
                    String host=end.host;
                    long wait=Math.max(0,trustUntil-SystemClock.elapsedRealtime());
                    main.postDelayed(()->probe.execute(()->check(host,port)),wait);
                    resolveNext(owner);
                    });
                }
            });
        } catch(RuntimeException ignored) {resolving=false;main.post(()->resolveNext(owner));}
    }

    private static void check(String host,int port){
        if(LineWire.running()){main.postDelayed(()->probe.execute(()->check(host,port)),30000);return;}
        String state=LineLink.INSTANCE.reachable(host,port);
        main.post(()->{
            LineWire.End now=LineWire.current();
            if(app==null || !CallWatch.on(app) || now==null)return;
            LineConnectionState.Result result=connection.checked(host,port,state);
            if(result==LineConnectionState.Result.STALE)return;
            if(result==LineConnectionState.Result.READY){up=true;settled=true;stale=false;healthy(app);publish();return;}
            noticeState.outage();main.removeCallbacks(confirmHealthyTask);
            // A timeout is not a disconnect. Only a rejected key means pairing is dead.
            if(result==LineConnectionState.Result.UNAVAILABLE){up=false;settled=true;publish();lost();return;}
            if(result==LineConnectionState.Result.RETRY_AUTH){main.postDelayed(()->probe.execute(()->check(host,port)),30000);return;}
            stale=true;
            warn(app,"VonoTation is disconnected","Wireless debugging is on, but calls won't hear both sides until you pair again.");
            publish();
        });
    }

    private static void seen(String host,int port){
        seenAt=SystemClock.elapsedRealtime();
        rechecks=0;
        main.removeCallbacks(confirmDownTask);
        publish();
    }

    private static void lost() {
        if(SystemClock.elapsedRealtime()<suppressLossUntil)return;
        noticeState.outage();main.removeCallbacks(confirmHealthyTask);
        // The service re-announces on its own. One loss is not proof it was turned off.
        main.removeCallbacks(confirmDownTask);
        main.postDelayed(confirmDownTask,Math.max(60000,ignoreLossUntil-SystemClock.elapsedRealtime()+1));
    }

    /** Notifies only after a loss stays gone through a fresh search. */
    private static void confirmDown(){
        if(app==null || !CallWatch.on(app) || !LineWire.paired(app))return;
        if(SystemClock.elapsedRealtime()<ignoreLossUntil){main.postDelayed(confirmDownTask,ignoreLossUntil-SystemClock.elapsedRealtime()+1);return;}
        if(LineWire.running()){main.postDelayed(confirmDownTask,60000);return;}
        if(seenAt>0 && SystemClock.elapsedRealtime()-seenAt<20000)return;
        if(rechecks<2){rechecks++;restart();main.postDelayed(confirmDownTask,30000);return;}
        rechecks=0;
        trustUntil=0;
        LineWire.End known=LineWire.current();
        if(known==null){unavailable();return;}
        // mDNS can disappear while the actual local TLS connection still works.
        probe.execute(()->{
            String state=LineLink.INSTANCE.reachable(known.host,known.port);
            main.post(()->{
                if(!connection.matches(known.host,known.port) || app==null || !CallWatch.on(app))return;
                if("ok".equals(state)){seen(known.host,known.port);up=true;settled=true;stale=false;healthy(app);publish();}
                else if("auth".equals(state))checkLater(known);
                else unavailable();
            });
        });
    }

    private static void checkLater(LineWire.End end){probe.execute(()->check(end.host,end.port));}

    private static void unavailable(){
        noticeState.outage();main.removeCallbacks(confirmHealthyTask);
        up=false;settled=true;stale=false;
        LineWire.forget();connection.clear();selectedService=null;
        warn(app,"Call connection unavailable","Turn on wireless debugging on this phone. Your saved pairing will reconnect automatically when available.");
        publish();
    }

    private static void holdDiscovery(){
        NsdManager.DiscoveryListener old=discovery;
        discovery=null;watching=false;
        resolvingQueue.clear();resolving=false;
        if(nsd!=null && old!=null)try{nsd.stopServiceDiscovery(old);}catch(RuntimeException ignored){}
    }

    private static void restart() {
        suppressLossUntil=SystemClock.elapsedRealtime()+4000;
        holdDiscovery();
        if(app!=null && CallWatch.on(app) && LineWire.paired(app))listen();
    }

    private static void mark(boolean live) {
        if(live){seenAt=SystemClock.elapsedRealtime();up=true;settled=true;healthy(app);publish();return;}
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
        noticeState.outage();main.removeCallbacks(confirmHealthyTask);
        if(context==null || !CallWatch.on(context) || !LineWire.paired(context))return;
        if(!LineNoticePolicy.shouldNotify(System.currentTimeMillis(),Store.prefs(context).getLong("line_outage_notified_at",0),Store.bool(context,"line_outage_notified",false)))return;
        NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if(manager==null)return;
        if(android.os.Build.VERSION.SDK_INT>=26){
            // Existing HIGH-importance channels cannot be downgraded programmatically.
            NotificationChannel channel=new NotificationChannel("call_connection_status_v2","Call connection",NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null,null);channel.enableVibration(false);
            channel.setDescription("Quiet connection status for call recording");
            manager.createNotificationChannel(channel);
        }
        Intent open=new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open_calls",true);
        PendingIntent tap=PendingIntent.getActivity(context,NOTICE,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notice=new Notification.Builder(context,"call_connection_status_v2")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setDefaults(0).setSound(null).setVibrate(new long[0])
            .setShowWhen(false)
            .setContentIntent(tap)
            .build();
        try{manager.notify(NOTICE,notice);Store.prefs(context).edit().putBoolean("line_outage_notified",true).putLong("line_outage_notified_at",System.currentTimeMillis()).apply();}catch(SecurityException ignored){}
    }

    private static void healthy(Context context){
        if(noticeState.healthy(SystemClock.elapsedRealtime())){
            if(context!=null)Store.prefs(context).edit().remove("line_outage_notified").apply();
            cancel(context);
            main.removeCallbacks(confirmHealthyTask);
        }else{
            // A single authenticated probe does not rearm a disconnection warning.
            main.removeCallbacks(confirmHealthyTask);
            main.postDelayed(confirmHealthyTask,LineNoticePolicy.HEALTHY_MS);
        }
    }

    private static void confirmHealthy(){
        if(app==null || !CallWatch.on(app) || !LineWire.paired(app))return;
        LineWire.End known=LineWire.current();
        if(known!=null)checkLater(known);
    }

    private static void resetNotice(Context context){
        noticeState.outage();main.removeCallbacks(confirmHealthyTask);
        if(context!=null)Store.prefs(context).edit().remove("line_outage_notified").apply();
        cancel(context);
    }

    private static void cancel(Context context) {
        if(context==null)return;
        NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if(manager!=null)manager.cancel(NOTICE);
    }
}
