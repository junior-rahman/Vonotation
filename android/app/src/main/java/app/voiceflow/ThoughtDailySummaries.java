package app.voiceflow;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground-only thought recaps. Source snapshots and cache commits are validated together. */
final class ThoughtDailySummaries {
    static final String SCOPE="thought";
    private static final Object gate=new Object();
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final Map<String,Request> requests=new HashMap<>();
    private static final Map<String,String> failures=new HashMap<>();
    private static volatile boolean resumed;
    private static long generation;
    private static SharedPreferences.OnSharedPreferenceChangeListener listener;
    private static android.net.ConnectivityManager.NetworkCallback network;

    private static final class Request {
        final Context app;
        final HistoryDays.Day day;
        final String model,endpoint,fingerprint,credential;
        final long generation;
        volatile CloudApi api;
        volatile boolean cancelled;
        Request(Context app,HistoryDays.Day day,String model,String endpoint,String credential,long generation){this.app=app;this.day=day;this.model=model;this.endpoint=endpoint;this.generation=generation;this.credential=credential;fingerprint=HistoryDays.fingerprint(day,model,endpoint,SCOPE);}
        void cancel(){cancelled=true;CloudApi current=api;if(current!=null)current.cancel();}
    }

    static String model(Context context){String chosen=Store.get(context,"notes_polish_model","");return chosen.isEmpty()?"openai/gpt-oss-120b":chosen;}
    static String endpoint(Context context){return Store.get(context,"api_base",CloudApi.DEFAULT_BASE);}
    private static String credential(Context context){return Store.get(context,"api_secret","")+":"+Store.get(context,"api_iv","");}
    private static boolean enabled(Context context){return Store.quickThoughts(context) && Store.bool(context,"daily_recaps_opt_in",false);}
    static boolean active(){return resumed;}
    static boolean online(Context context){
        try{android.net.ConnectivityManager manager=(android.net.ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);android.net.Network current=manager==null?null:manager.getActiveNetwork();android.net.NetworkCapabilities caps=current==null?null:manager.getNetworkCapabilities(current);return caps!=null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);}
        catch(RuntimeException unavailable){return false;}
    }
    static void resume(Context context){
        final Context app=context.getApplicationContext();
        synchronized(gate){resumed=true;}
        if(listener==null){
            listener=(prefs,key)->{if("notes".equals(key)||"quick_thoughts".equals(key)||"daily_recaps_opt_in".equals(key)||"notes_polish_model".equals(key)||"api_base".equals(key)||"api_secret".equals(key)||"api_iv".equals(key))main.post(()->{if(!"notes".equals(key))synchronized(gate){failures.clear();}reconcile(app);if(resumed)Notes.refreshThoughtSummaries();});};
            Store.prefs(app).registerOnSharedPreferenceChangeListener(listener);
        }
        if(network==null)try{
            android.net.ConnectivityManager manager=(android.net.ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if(manager!=null){network=new android.net.ConnectivityManager.NetworkCallback(){
                private android.net.Network validated;
                private void ready(){main.post(()->{synchronized(gate){failures.clear();}if(resumed)Notes.refreshThoughtSummaries();});}
                @Override public void onAvailable(android.net.Network connected){ready();}
                @Override public void onCapabilitiesChanged(android.net.Network connected,android.net.NetworkCapabilities capabilities){if(capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)){if(!connected.equals(validated)){validated=connected;ready();}}else if(connected.equals(validated))validated=null;}
                @Override public void onLost(android.net.Network lost){if(lost.equals(validated))validated=null;}
            };manager.registerDefaultNetworkCallback(network);}
        }catch(RuntimeException unavailable){network=null;}
    }
    static void pause(){
        List<Request> cancelled;
        synchronized(gate){resumed=false;generation++;cancelled=new ArrayList<>(requests.values());requests.clear();}
        for(Request request:cancelled)request.cancel();
    }
    private static void reconcile(Context app){
        String model=model(app),endpoint=endpoint(app);java.util.HashSet<String> active=new java.util.HashSet<>();
        for(HistoryDays.Day day:Notes.thoughtRecapDays(app))active.add(HistoryDays.fingerprint(day,model,endpoint,SCOPE));
        List<Request> snapshot;
        synchronized(gate){snapshot=new ArrayList<>(requests.values());failures.keySet().retainAll(active);}
        for(Request request:snapshot)if(!valid(request)){
            synchronized(gate){if(requests.get(request.day.key)==request)requests.remove(request.day.key);}
            request.cancel();
        }
    }

    static String failure(HistoryDays.Day day,String model,String endpoint){synchronized(gate){String value=failures.get(HistoryDays.fingerprint(day,model,endpoint,SCOPE));return value==null?"":value;}}
    static void retry(Context context,HistoryDays.Day day){
        String fingerprint=HistoryDays.fingerprint(day,model(context),endpoint(context),SCOPE);
        synchronized(gate){failures.remove(fingerprint);}
        request(context,java.util.Collections.singletonList(day));
    }
    static void request(Context context,List<HistoryDays.Day> days){
        final Context app=context.getApplicationContext();
        if(!resumed || !enabled(app) || Vault.get(app).isEmpty() || !online(app))return;
        String model=model(app),endpoint=endpoint(app),credential=credential(app);
        for(HistoryDays.Day day:days){
            if(day==null || !DailySummaryCache.get(app,SCOPE,day,model,endpoint).isEmpty())continue;
            String fingerprint=HistoryDays.fingerprint(day,model,endpoint,SCOPE);Request request,old;
            // No preference/cache reads while holding gate: Store callbacks only queue reconciliation.
            synchronized(gate){
                if(!resumed || failures.containsKey(fingerprint))continue;
                old=requests.get(day.key);if(old!=null && old.fingerprint.equals(fingerprint))continue;
                request=new Request(app,day,model,endpoint,credential,generation);requests.put(day.key,request);
            }
            if(old!=null)old.cancel();
            worker.execute(()->create(request));
        }
    }
    private static boolean valid(Request request){
        if(request.cancelled || !resumed || !enabled(request.app) || !model(request.app).equals(request.model) || !endpoint(request.app).equals(request.endpoint) || !credential(request.app).equals(request.credential))return false;
        synchronized(gate){if(request.generation!=generation || requests.get(request.day.key)!=request)return false;}
        for(HistoryDays.Day current:Notes.thoughtRecapDays(request.app))if(current.key.equals(request.day.key))return HistoryDays.fingerprint(current,request.model,request.endpoint,SCOPE).equals(request.fingerprint);
        return false;
    }
    private static void create(Request request){
        try{
            synchronized(Store.class){if(!valid(request))return;}
            CloudApi api=new CloudApi(request.app);request.api=api;
            synchronized(Store.class){if(!valid(request)){api.cancel();return;}}
            // Network work never holds Store, Notes or the worker state lock.
            String recap=api.summarizeThoughtDay(request.day,request.model);
            synchronized(Store.class){if(valid(request))DailySummaryCache.put(request.app,SCOPE,request.day,request.model,request.endpoint,recap);}
        }catch(Exception error){
            synchronized(Store.class){if(valid(request)){
                String message=error.getMessage();if(message==null || message.trim().isEmpty())message="Check your connection and provider settings.";
                synchronized(gate){failures.put(request.fingerprint,message);}
            }}
        }finally{
            synchronized(gate){if(requests.get(request.day.key)==request)requests.remove(request.day.key);}
            main.post(()->{if(resumed)Notes.refreshThoughtSummaries();});
        }
    }
    private ThoughtDailySummaries(){}
}
