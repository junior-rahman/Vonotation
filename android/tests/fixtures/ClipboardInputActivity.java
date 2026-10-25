package app.voiceflow.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

/** External disposable editor: runs in the fixture APK, not the Voitation process. */
public final class ClipboardInputActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout column=new LinearLayout(this);column.setOrientation(1);column.setPadding(30,100,30,30);
        column.setFocusableInTouchMode(true);
        EditText normal=new EditText(this);normal.setText("Before after");normal.setContentDescription("Recovery normal input");
        column.addView(normal,new LinearLayout.LayoutParams(-1,140));
        EditText secret=new EditText(this);secret.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);secret.setText("untouched");secret.setContentDescription("Recovery password input");
        column.addView(secret,new LinearLayout.LayoutParams(-1,140));
        EditText rejecting=new EditText(this){
            @Override public InputConnection onCreateInputConnection(EditorInfo info){
                InputConnection original=super.onCreateInputConnection(info);
                return original==null?null:new InputConnectionWrapper(original,false){
                    @Override public boolean commitText(CharSequence text,int position){return false;}
                    @Override public boolean commitText(CharSequence text,int position,android.view.inputmethod.TextAttribute attribute){return false;}
                };
            }
        };
        rejecting.setText("Keep");rejecting.setContentDescription("Recovery rejecting input");
        rejecting.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override public boolean performAccessibilityAction(View host,int action,Bundle args){
                if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT)return false;
                return super.performAccessibilityAction(host,action,args);
            }
        });
        column.addView(rejecting,new LinearLayout.LayoutParams(-1,140));
        Button leave=new Button(this);leave.setText("Leave input");leave.setContentDescription("Recovery leave input");
        leave.setOnClickListener(v->{column.requestFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(column.getWindowToken(),0);});
        column.addView(leave,new LinearLayout.LayoutParams(-1,100));
        setContentView(column);normal.requestFocus();normal.setSelection(7);
    }
}
