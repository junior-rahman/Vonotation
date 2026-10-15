package app.voiceflow;

import android.app.*;
import android.os.Bundle;

/** Configures only the isolated emulator for synthetic floating-control checks. */
public final class ControlSetup extends Instrumentation {
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){Store.prefs(getTargetContext()).edit().putString("engine","system").putString("language","en-US").putString("dictionary","").putString("bubble_size","small").putBoolean("clean",true).putBoolean("ai_polish",false).putBoolean("bubble",true).putBoolean("typing_visible",false).commit();Bundle result=new Bundle();result.putString("result","Prepared synthetic speech controls");finish(Activity.RESULT_OK,result);}
}
