package app.voiceflow.fixture;
import android.app.Activity;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
public class EditorActivity extends Activity {
    static EditorActivity current;
    public void onCreate(Bundle state) {
        super.onCreate(state);current=this; LinearLayout root=new LinearLayout(this); root.setOrientation(1); root.setPadding(24,80,24,24); root.setFocusableInTouchMode(true);
        if(getIntent().getBooleanExtra("auth_fields",false)){
            // This synthetic form has no Leave-input action; its container must not steal field focus on IME resize.
            root.setFocusableInTouchMode(false);root.setFocusable(false);
            TextView heading=new TextView(this);heading.setText("Sign-in field checks");heading.setTextSize(24);root.addView(heading);
            EditText normal=authField(root,12345,"Primary input","Ordinary text",android.text.InputType.TYPE_CLASS_TEXT);
            authField(root,12350,"Email input","Email",android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            authField(root,12351,"Web email input","Web email",android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS);
            authField(root,12352,"Password input","Password",android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            authField(root,12353,"PIN input","Secure PIN",android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            authField(root,12354,"Number input","Ordinary number",android.text.InputType.TYPE_CLASS_NUMBER);
            android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.setFillViewport(true);scroll.addView(root);
            android.view.View viewport=configureAuthInsets(scroll);setContentView(viewport);normal.requestFocus();viewport.requestApplyInsets();return;
        }
        if(getIntent().getBooleanExtra("bottom_composer",false)){
            TextView heading=new TextView(this);heading.setText("Conversation");heading.setTextSize(24);root.addView(heading);
            root.addView(new android.view.View(this),new LinearLayout.LayoutParams(-1,0,1f));
            EditText composer=new EditText(this);composer.setContentDescription("Bottom input");composer.setHint("Write a message");root.addView(composer,new LinearLayout.LayoutParams(-1,70));setContentView(root);composer.requestFocus();return;
        }
        TextView title=new TextView(this); title.setText("Write with your voice"); title.setTextSize(24); root.addView(title);
        EditText editor=new EditText(this); editor.setId(12345); editor.setContentDescription("Primary input"); editor.setText("Before after"); editor.setTextSize(22); editor.setSingleLine(false); root.addView(editor,new LinearLayout.LayoutParams(-1,150));
        EditText second=new EditText(this); second.setId(12346); second.setContentDescription("Second input"); second.setHint("Another message"); root.addView(second,new LinearLayout.LayoutParams(-1,70));
        EditText password=new EditText(this); password.setContentDescription("Password input"); password.setInputType(129); password.setHint("Password"); root.addView(password,new LinearLayout.LayoutParams(-1,70));
        EditText custom=new EditText(this){@Override public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo info){android.view.inputmethod.InputConnection c=super.onCreateInputConnection(info);return c==null?null:new android.view.inputmethod.InputConnectionWrapper(c,false){@Override public boolean setSelection(int start,int end){android.util.Log.i("VFTEST","selection rejected "+start+","+end);return false;}
@Override public android.view.inputmethod.SurroundingText getSurroundingText(int before,int after,int flags){android.view.inputmethod.SurroundingText v=super.getSurroundingText(before,after,flags);android.util.Log.i("VFTEST","read "+(v==null?"null":v.getText()+" sel="+v.getSelectionStart()+","+v.getSelectionEnd()+" offset="+v.getOffset()));return v;}
@Override public boolean deleteSurroundingText(int before,int after){android.util.Log.i("VFTEST","delete "+before+","+after);return super.deleteSurroundingText(before,after);}
@Override public boolean commitText(CharSequence text,int position,android.view.inputmethod.TextAttribute attribute){android.util.Log.i("VFTEST","commit ["+text+"]");return super.commitText(text,position,attribute);}};}};custom.setId(12347);custom.setContentDescription("Custom input");custom.setText("Custom ");custom.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);custom.setAccessibilityDelegate(new android.view.View.AccessibilityDelegate(){@Override public boolean performAccessibilityAction(android.view.View host,int action,Bundle args){if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT || action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION)return false;return super.performAccessibilityAction(host,action,args);}});root.addView(custom,new LinearLayout.LayoutParams(-1,70));
        android.widget.Button leave=new android.widget.Button(this); leave.setText("Leave input"); leave.setContentDescription("Leave input"); leave.setOnClickListener(v->{root.requestFocus();((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0);}); root.addView(leave);
        setContentView(root); editor.requestFocus(); editor.setSelection(7);
    }
    /** Keep this synthetic auth form genuinely visible above Android's typing keyboard. */
    private android.view.View configureAuthInsets(android.widget.ScrollView scroll){
        if(android.os.Build.VERSION.SDK_INT<30)return scroll; // Older devices retain their existing adjustResize behavior.
        getWindow().setDecorFitsSystemWindows(false);
        android.widget.FrameLayout viewport=new android.widget.FrameLayout(this);
        viewport.setClipToPadding(true);
        viewport.addView(scroll,new android.widget.FrameLayout.LayoutParams(-1,-1));
        // Shrink the measured ScrollView height, not just its drawable area, so rectangle requests can scroll.
        scroll.setPadding(0,0,0,0);
        scroll.setClipToPadding(true);
        viewport.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
            android.graphics.Insets ime=insets.getInsets(android.view.WindowInsets.Type.ime());
            view.setPadding(Math.max(bars.left,ime.left),Math.max(bars.top,ime.top),Math.max(bars.right,ime.right),Math.max(bars.bottom,ime.bottom));
            return insets;
        });
        // Insets can change after focus while the IME opens; reveal only the already-focused field.
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(()->{
            android.view.View focused=scroll.findFocus();
            if(!isDestroyed() && focused instanceof EditText && focused.isFocused())
                focused.requestRectangleOnScreen(new android.graphics.Rect(0,0,focused.getWidth(),focused.getHeight()),true);
        });
        return viewport;
    }
    private EditText authField(LinearLayout root,int id,String description,String hint,int type){EditText field=new EditText(this);field.setId(id);field.setContentDescription(description);field.setHint(hint);field.setInputType(type);root.addView(field,new LinearLayout.LayoutParams(-1,70));return field;}
    @Override public void onDestroy(){if(current==this)current=null;super.onDestroy();}
}
