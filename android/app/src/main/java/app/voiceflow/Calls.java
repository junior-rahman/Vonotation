package app.voiceflow;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.LinearLayout;
import java.util.ArrayList;

/** Call recording, separate from notes. On means every connected call is saved and written up. */
final class Calls {
    private static boolean setup,arming;
    private Calls() {}

    static void show(Activity activity,LinearLayout body,android.widget.FrameLayout dock,Runnable refresh) {
        Notes.callsMode(true);
        if(arming){arming=false;if(ready(activity)){Store.prefs(activity).edit().putBoolean("record_calls",true).apply();battery(activity);CallWatch.wake(activity);}}
        if(Notes.opened()){Notes.show(activity,body,refresh);return;}
        boolean on=CallWatch.on(activity);
        boolean paired=LineWire.paired(activity);
        boolean stale=on && paired && LineWatch.needsReconnect();
        boolean down=on && paired && !stale && LineWatch.settled() && !LineWatch.up();
        if(down || stale){
            LinearLayout downCard=Daybook.card(activity);
            downCard.setPadding(Ui.dp(activity,16),Ui.dp(activity,14),Ui.dp(activity,16),Ui.dp(activity,14));
            downCard.addView(Daybook.text(activity,down?"Call connection unavailable.":"VonoTation is disconnected.",16,Daybook.ink(activity),true));
            Ui.space(downCard,6);
            downCard.addView(Daybook.text(activity,down?"Turn on wireless debugging. Your saved pairing will reconnect automatically when it becomes available. Until then calls use speakerphone.":"This phone rejected the saved pairing. Pair again to record through the earpiece; until then calls use speakerphone.",13,Daybook.muted(activity),false));
            Ui.space(downCard,10);
            downCard.addView(Daybook.button(activity,"Open wireless debugging",true,v->{try{activity.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));}catch(RuntimeException error){android.widget.Toast.makeText(activity,"Turn on developer options first. Settings, About phone, tap Build number.",android.widget.Toast.LENGTH_LONG).show();}}),new LinearLayout.LayoutParams(-1,Ui.dp(activity,48)));
            if(!down){
                Ui.space(downCard,8);
                downCard.addView(Daybook.text(activity,"Leave Pair device with pairing code open, then enter the code.",13,Daybook.muted(activity),false));
                Ui.space(downCard,8);
                android.widget.EditText code=Daybook.field(activity,codeDraft,"6 digit code",false);
                code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                code.setSingleLine(true);
                code.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){} public void onTextChanged(CharSequence s,int a,int b,int c){codeDraft=s.toString();} public void afterTextChanged(android.text.Editable s){}});
                downCard.addView(code);
                Ui.space(downCard,8);
                downCard.addView(Daybook.button(activity,pairing?"Pairing…":"Pair again",true,v->pair(activity,refresh)),new LinearLayout.LayoutParams(-1,Ui.dp(activity,48)));
            }
            body.addView(downCard);Ui.space(body,12);
        }
        LinearLayout card=Daybook.card(activity);
        card.setPadding(Ui.dp(activity,16),Ui.dp(activity,16),Ui.dp(activity,16),Ui.dp(activity,16));
        card.addView(Daybook.text(activity,on?"A call writes itself down here, after you hang up.":"Turn it on, and the call is kept without opening VonoTation.",14,Daybook.muted(activity),false));
        Ui.space(card,8);
        String quiet=down?"Wireless debugging is unavailable; calls use speakerphone.":stale?"Pairing needs attention; calls use speakerphone.":"Both sides, with the phone at your ear. VonoTation sleeps between calls.";
        card.addView(Daybook.text(activity,paired?quiet:"Until earpiece access is paired, a call plays out loud so both sides are saved.",13,Daybook.muted(activity),false));
        if(dock!=null){
            dock.removeAllViews();
            android.widget.Button toggle=Daybook.button(activity,on?"Turn off":"Record calls",!on,v->{
                if(on){Store.prefs(activity).edit().putBoolean("record_calls",false).apply();CallWatch.quit(activity);LineWatch.start(activity,null);arming=false;refresh.run();}
                else enable(activity,refresh);
            });
            Daybook.pill(toggle,!on);
            dock.addView(toggle,new android.widget.FrameLayout.LayoutParams(-2,Ui.dp(activity,52)));
        }
        if(on && !paired){
            Ui.space(card,10);
            android.widget.TextView setupLine=Daybook.text(activity,setup?"Hide earpiece setup":"Earpiece setup",14,Daybook.accent(activity),true);
            setupLine.setOnClickListener(v->{Daybook.feedback(v);setup=!setup;refresh.run();});
            card.addView(setupLine);
            if(setup){
                Ui.space(card,8);
                card.addView(Daybook.text(activity,"Turn on wireless debugging. Tap Pair device with pairing code and leave that screen open.",13,Daybook.muted(activity),false));
                Ui.space(card,8);
                android.widget.EditText code=Daybook.field(activity,codeDraft,"6 digit code",false);
                code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                code.setSingleLine(true);
                code.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){} public void onTextChanged(CharSequence s,int a,int b,int c){codeDraft=s.toString();} public void afterTextChanged(android.text.Editable s){}});
                card.addView(code);
                Ui.space(card,8);
                card.addView(Daybook.button(activity,"Open wireless debugging",false,v->{try{activity.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));}catch(RuntimeException error){android.widget.Toast.makeText(activity,"Turn on developer options first. Settings, About phone, tap Build number.",android.widget.Toast.LENGTH_LONG).show();}}),new LinearLayout.LayoutParams(-1,Ui.dp(activity,48)));
                Ui.space(card,8);
                card.addView(Daybook.button(activity,pairing?"Pairing…":"Pair",true,v->pair(activity,refresh)),new LinearLayout.LayoutParams(-1,Ui.dp(activity,48)));
            }
        }
        body.addView(card);
        Ui.space(body,14);
        Notes.show(activity,body,refresh);
    }

    private static String codeDraft="";
    private static boolean pairing;
    private static final java.util.concurrent.ExecutorService pairWork=java.util.concurrent.Executors.newSingleThreadExecutor();

    private static void pair(Activity activity,Runnable refresh) {
        String code=codeDraft.trim();
        if(code.length()<6 || pairing)return;
        pairing=true;refresh.run();
        pairWork.execute(()->{
            String message;
            try{LineWire.pair(activity.getApplicationContext(),code);codeDraft="";message="Both sides are on. Nothing runs until a call.";}
            catch(Exception error){message=error.getMessage()==null?"Pairing didn't work. Leave the pairing code on screen and try again.":error.getMessage();}
            String say=message;
            activity.runOnUiThread(()->{pairing=false;android.widget.Toast.makeText(activity,say,android.widget.Toast.LENGTH_LONG).show();if(!activity.isDestroyed())refresh.run();});
        });
    }

    private static void enable(Activity activity,Runnable refresh) {
        ArrayList<String> need=new ArrayList<>();
        if(activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)need.add(android.Manifest.permission.RECORD_AUDIO);
        if(activity.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)!=android.content.pm.PackageManager.PERMISSION_GRANTED)need.add(android.Manifest.permission.READ_PHONE_STATE);
        if(activity.checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)need.add(android.Manifest.permission.ANSWER_PHONE_CALLS);
        if(activity.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)!=android.content.pm.PackageManager.PERMISSION_GRANTED)need.add(android.Manifest.permission.READ_CALL_LOG);
        if(!need.isEmpty()){arming=true;activity.requestPermissions(need.toArray(new String[0]),23);return;}
        arming=false;
        Store.prefs(activity).edit().putBoolean("record_calls",true).apply();
        LineWatch.start(activity,null);
        battery(activity);
        refresh.run();
    }

    private static boolean ready(Activity activity) {
        return activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED
            && activity.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)==android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private static void battery(Activity activity) {
        PowerManager power=(PowerManager)activity.getSystemService(Activity.POWER_SERVICE);
        if(power==null || power.isIgnoringBatteryOptimizations(activity.getPackageName()))return;
        try{activity.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+activity.getPackageName())));}
        catch(RuntimeException ignored){}
    }
}
