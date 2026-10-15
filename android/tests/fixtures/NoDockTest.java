package app.voiceflow.fixture;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityWindowInfo;

/** The disabled floating service must never bring back the old docked bar. */
public final class NoDockTest extends Instrumentation {
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){Bundle report=new Bundle();try{
        UiAutomation ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        android.accessibilityservice.AccessibilityServiceInfo info=ui.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(info);
        getTargetContext().startActivity(new Intent().setClassName("app.voiceflow.fixture","app.voiceflow.fixture.EditorActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        android.view.inputmethod.InputMethodManager ime=(android.view.inputmethod.InputMethodManager)getTargetContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        long deadline=SystemClock.elapsedRealtime()+45000;while(!ime.isAcceptingText() && SystemClock.elapsedRealtime()<deadline)Thread.sleep(150);
        if(!ime.isAcceptingText())throw new Exception("Fixture input connection did not become active");
        Thread.sleep(600);
        for(AccessibilityWindowInfo window:ui.getWindows())if(window.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD){Rect r=new Rect();window.getBoundsInScreen(r);if(r.height()>0)throw new Exception("A docked keyboard appeared with floating access disabled: "+r);}
        report.putString("result","PASS: selected voice keyboard with floating access disabled reserves no bottom keyboard panel");finish(Activity.RESULT_OK,report);
    }catch(Throwable e){report.putString("failure",e.toString());finish(Activity.RESULT_CANCELED,report);}}
}
