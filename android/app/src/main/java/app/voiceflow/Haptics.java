package app.voiceflow;

import android.content.Context;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/** Direct vibrator haptics: does not depend on View.performHapticFeedback or the system "touch feedback" switch. */
final class Haptics {
    static final int PRESS=0,RELEASE=1,POP=2,STICK=3;
    private Haptics(){}
    static boolean enabled(Context c){return Store.bool(c,"haptic_feedback",true);}
    static Vibrator vibrator(Context c){
        if(Build.VERSION.SDK_INT>=31){VibratorManager m=(VibratorManager)c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);if(m!=null)return m.getDefaultVibrator();}
        return (Vibrator)c.getSystemService(Context.VIBRATOR_SERVICE);
    }
    static void fire(Context c,int kind){fire(c,kind,false);}
    static void fire(Context c,int kind,boolean force){
        if(!force&&!enabled(c))return;
        try{
            Vibrator v=vibrator(c);if(v==null||!v.hasVibrator())return;
            int predefined=kind==RELEASE?VibrationEffect.EFFECT_TICK:kind==POP||kind==STICK?VibrationEffect.EFFECT_HEAVY_CLICK:VibrationEffect.EFFECT_CLICK;
            long ms=kind==RELEASE?18:kind==POP?35:kind==STICK?0:26;
            VibrationEffect effect=null;
            if(kind==STICK)effect=VibrationEffect.createWaveform(new long[]{0,28,36,42},-1);
            else if(Build.VERSION.SDK_INT>=30&&v.areAllEffectsSupported(predefined)==Vibrator.VIBRATION_EFFECT_SUPPORT_YES)effect=VibrationEffect.createPredefined(predefined);
            if(effect==null)effect=VibrationEffect.createOneShot(ms,VibrationEffect.DEFAULT_AMPLITUDE);
            if(Build.VERSION.SDK_INT>=33)v.vibrate(effect,VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY));
            else v.vibrate(effect);
        }catch(RuntimeException ignored){}
    }
}
