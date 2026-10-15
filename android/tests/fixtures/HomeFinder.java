package app.voiceflow.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;

/** Isolated-emulator launcher with an always-focused Finder, like the reported home screen. */
public final class HomeFinder extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(24,80,24,24);
        TextView title=new TextView(this);title.setText("Home");title.setTextSize(24);root.addView(title);
        EditText finder=new EditText(this);finder.setHint("Find apps");finder.setContentDescription("Launcher finder");finder.setShowSoftInputOnFocus(false);root.addView(finder,new LinearLayout.LayoutParams(-1,70));
        finder.setOnClickListener(v->{title.setText("Finder");finder.setShowSoftInputOnFocus(true);finder.requestFocus();((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(finder,0);});
        Button close=new Button(this);close.setText("Home");close.setContentDescription("Close finder");close.setFocusable(false);close.setOnClickListener(v->{title.setText("Home");finder.setShowSoftInputOnFocus(false);((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(finder.getWindowToken(),0);});root.addView(close);
        setContentView(root);finder.requestFocus();
    }
}
