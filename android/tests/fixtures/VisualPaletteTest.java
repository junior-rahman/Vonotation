package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ComponentName;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Visual-only evidence from actual production Activities on a disposable emulator.
 * Page selection uses the production Activity's saved-state restoration, not fake views.
 * This is not live transcription, account authorization, or a full interaction test.
 */
public final class VisualPaletteTest extends Instrumentation {
    private static final String CLIP = "Synthetic QA: a temporary copied note, kept privately for 30 days.";
    private static final String THOUGHT_TITLE = "Room for a quiet morning";
    private Activity activity;
    private int page, checks;
    private String appearance;
    private Context context;
    private String requestedProduct;
    private String legacyVoice, legacyCall;
    private File legacyAudio;
    private final AtomicInteger requests = new AtomicInteger();

    @Override public void onCreate(Bundle arguments) {
        appearance = arguments == null ? "" : arguments.getString("appearance", "");
        requestedProduct = arguments == null ? "" : arguments.getString("product", "");
        super.onCreate(arguments); start();
    }
    @Override public void callActivityOnCreate(Activity target, Bundle state) {
        if (target instanceof MainActivity) {
            state = new Bundle();
            state.putInt("daybook_part", page);
            state.putString("daybook_panel", "");
            activity = target;
        }
        super.callActivityOnCreate(target, state);
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); context = getTargetContext();
        SharedPreferences preferences = Store.prefs(context);
        Map<String, ?> previous = preferences.getAll();
        try {
            require(appearance.equals("light") || appearance.equals("dark"), "Explicit light/dark mode required");
            require(requestedProduct.equals(AppProduct.isNotes()?"notes":"vonotation"), "Instrumentation product does not match app variant");
            require(Vault.get(context).isEmpty(), "Only a disposable installation without a provider key may run this fixture");
            require(Store.get(context, "history", "[]").equals("[]") && Store.get(context, "notes", "[]").equals("[]"), "Only a fresh disposable installation may run this fixture");
            URL.setURLStreamHandlerFactory(protocol -> protocol.equals("http") || protocol.equals("https") ? new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) throws IOException {
                    requests.incrementAndGet(); throw new IOException("Palette fixture blocks network requests");
                }
            } : null);
            checks+=RecordingTransferChecks.run(context,AppProduct.isNotes());
            verifyRecordingGrowth();
            seed(preferences);
            verifyProductManifest();
            setInTouchMode(true);
            int[] pages = AppProduct.isNotes()?new int[]{0,1}:new int[]{3,4,5};
            String[] names = AppProduct.isNotes()?new String[]{"voice-notes","calls"}:new String[]{"dictations","thoughts","clipboard"};
            String[] headings = AppProduct.isNotes()?new String[]{"Voice notes","Call Recordings"}:new String[]{"Dictation","Thoughts","Clipboard"};
            for (int i = 0; i < pages.length; i++) {
                page = pages[i];
                activity = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                waitForIdleSync(); awaitFocusedWindow();
                boolean dark = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
                require(dark == appearance.equals("dark"), "Native appearance does not match requested theme");
                require(findText(headings[i]) != null, "Missing production card heading: " + headings[i]);
                String[] excluded = AppProduct.isNotes()?new String[]{"Dictation","Thoughts","Clipboard","Translate"}:new String[]{"Voice notes","Call","Call Recordings","Translate"};
                for(String heading:excluded)require(findText(heading)==null,"Excluded card leaked into the product: "+heading);
                if (pages[i] == 4) require(findText(THOUGHT_TITLE) != null, "Synthetic Thought did not appear in the production timeline");
                if (pages[i] == 5) require(findText(ClipboardText.preview(CLIP)) != null, "Synthetic Clipboard copy did not appear in the production timeline");
                capture(names[i]);
                verifyLegacyRetention();
                if (i == 0) {
                    View settings = findDescription("Settings");
                    tap(settings); waitForIdleSync(); SystemClock.sleep(300);
                    require(findDescription("Back to Home") != null, "One physical Settings tap did not open production Settings");
                    require(findText("Settings") != null, "Production Settings heading is absent");
                    capture("settings");
                }
                finishActivity();
            }
            require(requests.get() == 0, "Unexpected external request attempted");
            result.putInt("executed_checks", checks);
            result.putInt("native_screenshots", pages.length+1);
            result.putString("product", requestedProduct);
            result.putString("appearance", appearance);
            result.putString("capture_directory", captureDirectory().getAbsolutePath());
            result.putString("result", "PASS: " + (pages.length+1) + " actual production native screens for " + requestedProduct + " in " + appearance + "; manifest/card separation and retained legacy data; synthetic QA data; saved-state card selection; one physical Settings tap; no provider calls. Split and visual fixture only.");
        } catch (Throwable failure) {
            result.putString("failure", android.util.Log.getStackTraceString(failure));
            result.putInt("executed_checks", checks);
        } finally {
            try { finishActivity(); restore(preferences, previous); }
            catch (Throwable cleanup) { result.putString("failure", result.getString("failure", "") + "\nCleanup: " + cleanup); }
        }
        finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
    }
    /** Actual Notes save/delete paths must preserve an imported library above the old 100-row cap. */
    private void verifyRecordingGrowth() throws Exception {
        String before=Store.get(context,"notes","[]"),deleted=Store.get(context,"note_deletions","[]"),thoughtDeleted=Store.get(context,"thought_deletions","[]");
        File directory=Notes.sourceDirectory(context);
        java.util.List<File> created=new java.util.ArrayList<>();
        final long first=1000000L,capture=first+2000;
        final byte[] source={0x51,0x41,0x2d,0x47};
        try {
            JSONArray imported=new JSONArray();
            for(int i=0;i<1000;i++){
                long id=first+i;String name="growth-qa-"+id+".m4a";
                File audio=new File(directory,name);require(!audio.exists(),"Recording retention fixture source already exists");created.add(audio);
                try(FileOutputStream output=new FileOutputStream(audio)){output.write(source);}
                imported.put(new JSONObject().put("id",id).put("created",id).put("audio",name).put("raw","Imported source "+i).put("text","Imported source "+i).put("pending",false).put("recording",false).put("revision",1L));
            }
            require(Store.prefs(context).edit().putString("notes",imported.toString()).commit(),"Could not stage 1,000 imported recordings for retention check");
            if(AppProduct.isNotes()){
                File audio=new File(directory,"growth-qa-capture.m4a");require(!audio.exists(),"Capture retention fixture source already exists");created.add(audio);
                try(FileOutputStream output=new FileOutputStream(audio)){output.write(source);}
                java.lang.reflect.Method save=Notes.class.getDeclaredMethod("save",Context.class,long.class,String.class,String.class,boolean.class,String.class,String.class,String.class,long.class,String.class,boolean.class,JSONArray.class);
                save.setAccessible(true);
                save.invoke(null,context,capture,"","",false,"auto","",audio.getName(),0L,"",true,new JSONArray().put(audio.getName()));
                verifyImportedGrowth(first,1001,source);
                save.invoke(null,context,capture,"Saved capture","Saved capture",false,"auto","",audio.getName(),0L,"",false,new JSONArray().put(audio.getName()));
                verifyImportedGrowth(first,1001,source);
            }else{
                require(Notes.keepThought(context,capture,"Synthetic thought after retaining the previous recording library.",0L)==capture,"Could not save new thought beside retained legacy recordings");
                verifyImportedGrowth(first,1001,source);
            }
            // Cancellation/deletion removes only the newly owned entry. This
            // exercises the same production metadata commit and audio cleanup.
            java.lang.reflect.Method remove=Notes.class.getDeclaredMethod("remove",Context.class,long.class);remove.setAccessible(true);
            remove.invoke(null,context,capture);
            verifyImportedGrowth(first,1000,source);
        } finally {
            require(Store.prefs(context).edit().putString("notes",before).putString("note_deletions",deleted).putString("thought_deletions",thoughtDeleted).commit(),"Recording retention fixture preferences were not restored");
            for(File file:created)if(file.exists())require(file.delete(),"Recording retention fixture source was not removed");
        }
    }
    private void verifyImportedGrowth(long first,int expected,byte[] source) throws Exception {
        JSONArray saved=new JSONArray(Store.get(context,"notes","[]"));require(saved.length()==expected,"Saving or cancelling a new recording evicted imported rows");
        java.util.Set<Long> ids=new java.util.HashSet<>();
        for(int i=0;i<saved.length();i++){
            JSONObject item=saved.getJSONObject(i);long id=item.optLong("id");
            if(id<first||id>=first+1000)continue;
            require(ids.add(id),"Imported recording id was duplicated");
            String expectedName="growth-qa-"+id+".m4a";
            require(expectedName.equals(item.optString("audio"))&&("Imported source "+(id-first)).equals(item.optString("raw")),"Imported recording metadata changed while saving another capture");
            File audio=new File(Notes.sourceDirectory(context),expectedName);
            require(audio.isFile()&&java.util.Arrays.equals(java.nio.file.Files.readAllBytes(audio.toPath()),source),"Saving or cancelling a capture deleted imported source audio");
        }
        require(ids.size()==1000,"An imported recording disappeared from the retained library");
    }
    private void seed(SharedPreferences preferences) throws Exception {
        long now = System.currentTimeMillis();
        String raw = "Synthetic QA: I want to make room for a quiet morning. First I will review the week's notes and choose one useful task. I may take a walk after lunch if the weather stays clear. I want to keep the afternoon open instead of adding more plans.";
        JSONObject thought = new JSONObject().put("id", now - 1000).put("created", now - 1000).put("thought", true)
            .put("title", THOUGHT_TITLE).put("raw", raw).put("text", raw)
            .put("summary", "Review the week's notes, choose one task, and leave the afternoon open. A walk remains optional.")
            .put("summary_version", QuickThoughtPolicy.SUMMARY_VERSION).put("summary_local", false).put("pending", false).put("revision", 1L).put("user_edited", true);
        JSONObject note = new JSONObject().put("id", now - 2000).put("created", now - 2000)
            .put("text", "Synthetic QA: finish the project outline, then share it tomorrow.").put("raw", "Synthetic QA: finish the project outline, then share it tomorrow.")
            .put("summary", "Project outline and tomorrow's follow-up.").put("pending", false).put("audio","legacy-voice.m4a");
        JSONObject call = new JSONObject().put("id", now - 4000).put("created", now - 4000).put("call",true)
            .put("text","Synthetic QA: an earlier call recording.").put("raw","Synthetic QA: an earlier call recording.")
            .put("summary","Earlier call.").put("pending",false).put("audio","legacy-voice.m4a");
        legacyVoice=note.toString();legacyCall=call.toString();
        File sources=new File(context.getFilesDir(),"notes");require(sources.isDirectory()||sources.mkdirs(),"Could not create synthetic legacy source directory");
        legacyAudio=new File(sources,"legacy-voice.m4a");
        // A retained binary source marker, not a microphone recording or playback fixture.
        try(FileOutputStream out=new FileOutputStream(legacyAudio)){out.write(new byte[]{0x51,0x41,0x2d,0x31});}
        JSONObject dictation = new JSONObject().put("id", now - 3000).put("text", "Synthetic QA: a clear place for everything I want to remember.")
            .put("raw", "Synthetic QA: a clear place for everything I want to remember.").put("duration", 1200).put("words", 14);
        require(preferences.edit().clear().putBoolean("quick_thoughts", true).putBoolean("bubble", false)
            .putBoolean("daily_recaps_opt_in", false).putString("backup_frequency", "off")
            .putString("history", new JSONArray().put(dictation).toString()).putString("notes", new JSONArray().put(thought).put(note).put(call).toString()).commit(), "Could not seed synthetic visual data");
        require(ClipList.days(context).isEmpty(), "Only an empty disposable Clipboard archive may run this fixture");
        if(!AppProduct.isNotes()){
            ClipList.remember(context, CLIP);
            require(!ClipList.days(context).isEmpty(), "Could not seed the current SQLite Clipboard archive");
        }
    }
    private void verifyProductManifest() throws Exception {
        String expected=AppProduct.isNotes()?"app.vonotation.notes":"app.voiceflow";
        require(expected.equals(context.getPackageName()),"Product application ID changed");
        File own=context.getFilesDir().getCanonicalFile();
        require(own.getPath().contains("/"+expected+"/"),"Product storage is not inside its own application directory");
        PackageInfo info=context.getPackageManager().getPackageInfo(expected,PackageManager.GET_PERMISSIONS);
        java.util.Set<String> permissions=new java.util.HashSet<>();
        if(info.requestedPermissions!=null)java.util.Collections.addAll(permissions,info.requestedPermissions);
        require(!hasService("CallService"),"Legacy unused call service became an application component");
        requireServiceType("PlayWatch",ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        if(AppProduct.isNotes()){
            require(!hasService("FlowAccessibility")&&!hasService("FlowKeyboard"),"Notes app exposes dictation accessibility or keyboard");
            require(!hasService("ClipboardExpiryJob")&&!hasService("ThoughtJob"),"Notes app exposes thought or clipboard jobs");
            require(hasService("NoteRecordingService")&&hasService("CallWatch"),"Notes app is missing its recording services");
            requireServiceType("NoteRecordingService",ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            requireServiceType("CallWatch",ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE|ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        }else{
            for(String permission:new String[]{"READ_PHONE_STATE","ANSWER_PHONE_CALLS","READ_CALL_LOG","FOREGROUND_SERVICE_SPECIAL_USE","CHANGE_WIFI_MULTICAST_STATE"})
                require(!permissions.contains("android.permission."+permission),"Dictation app retains call-only permission: "+permission);
            require(hasService("FlowAccessibility")&&hasService("FlowKeyboard"),"Dictation app is missing its input services");
            requireServiceType("FlowAccessibility",ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            requireServiceType("FlowKeyboard",ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            require(!hasService("CallWatch")&&!hasService("NoteRecordingService"),"Dictation app exposes ordinary/call recording services");
        }
    }
    private boolean hasService(String name) throws Exception {
        try{context.getPackageManager().getServiceInfo(new ComponentName(context.getPackageName(),"app.voiceflow."+name),0);return true;}
        catch(PackageManager.NameNotFoundException absent){return false;}
    }
    private void requireServiceType(String name,int expected) throws Exception {
        ServiceInfo service=context.getPackageManager().getServiceInfo(new ComponentName(context.getPackageName(),"app.voiceflow."+name),0);
        require(service.getForegroundServiceType()==expected,"Merged manifest foreground service type is incorrect for "+name);
    }
    private void verifyLegacyRetention() throws Exception {
        JSONArray saved=new JSONArray(Store.get(context,"notes","[]"));boolean voice=false,call=false;
        long voiceId=new JSONObject(legacyVoice).getLong("id"),callId=new JSONObject(legacyCall).getLong("id");
        for(int i=0;i<saved.length();i++){JSONObject note=saved.optJSONObject(i);if(note==null)continue;if(note.optLong("id")==voiceId)voice=legacyVoice.equals(note.toString());if(note.optLong("id")==callId)call=legacyCall.equals(note.toString());}
        require(voice&&call,"Opening a split product mutated or removed historical recordings");
        require(legacyAudio.isFile()&&java.util.Arrays.equals(java.nio.file.Files.readAllBytes(legacyAudio.toPath()),new byte[]{0x51,0x41,0x2d,0x31}),"Opening a split product lost historical source audio");
        if(AppProduct.isNotes())require(ClipList.days(context).isEmpty(),"Notes app collected Clipboard data");
    }
    private void finishActivity() {
        if (activity != null && !activity.isDestroyed()) runOnMainSync(() -> activity.finish());
        waitForIdleSync(); activity = null;
    }
    private View findDescription(String description) { return find(description, false); }
    private View findText(String text) { return find(text, true); }
    private View find(String value, boolean text) {
        View[] found = {null}; runOnMainSync(() -> found[0] = find(activity.getWindow().getDecorView(), value, text)); return found[0];
    }
    private View find(View view, String value, boolean text) {
        if ((text && view instanceof TextView && value.contentEquals(((TextView) view).getText())) || (!text && value.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()))) return view;
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i=0; i<group.getChildCount(); i++) { View candidate = find(group.getChildAt(i), value, text); if (candidate != null) return candidate; } }
        return null;
    }
    private void tap(View target) throws Exception {
        require(target != null, "Settings control is missing");
        awaitFocusedWindow();
        Rect bounds = new Rect(); runOnMainSync(() -> target.getGlobalVisibleRect(bounds));
        require(bounds.width() >= Ui.dp(activity, 44) && bounds.height() >= Ui.dp(activity, 44), "Settings touch target is smaller than 44dp");
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0);
        MotionEvent up = MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN); up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            require(getUiAutomation().injectInputEvent(down, true), "Settings touch down was rejected");
            require(getUiAutomation().injectInputEvent(up, true), "Settings touch up was rejected");
        } finally { down.recycle(); up.recycle(); }
    }
    private void awaitFocusedWindow() throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15000;
        boolean[] focused = {false};
        do {
            runOnMainSync(() -> focused[0] = activity != null && !activity.isFinishing()
                && activity.getWindow().getDecorView().isShown() && activity.hasWindowFocus());
            if (focused[0]) break;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        require(focused[0], "Production Activity is obscured or unfocused; refusing to capture or tap a system window");
        waitForIdleSync();
    }
    private File captureDirectory() throws Exception {
        File[] locations = context.getExternalMediaDirs();
        require(locations != null && locations.length > 0 && locations[0] != null, "External media path unavailable");
        File output = new File(locations[0], "visual-" + appearance);
        require(output.isDirectory() || output.mkdirs(), "Could not create native screenshot directory"); return output;
    }
    private void capture(String name) throws Exception {
        awaitFocusedWindow();
        Bitmap screen = getUiAutomation().takeScreenshot(); require(screen != null, "Native screenshot unavailable");
        try (FileOutputStream output = new FileOutputStream(new File(captureDirectory(), name + ".png"))) { require(screen.compress(Bitmap.CompressFormat.PNG, 100, output), "Native screenshot could not be exported"); }
        finally { screen.recycle(); }
    }
    private void require(boolean condition, String message) throws Exception { checks++; if (!condition) throw new Exception(message); }
    private void restore(SharedPreferences preferences, Map<String, ?> previous) throws Exception {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : previous.entrySet()) {
            Object value = entry.getValue(); String key = entry.getKey();
            if (value instanceof String) editor.putString(key, (String)value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean)value);
            else if (value instanceof Integer) editor.putInt(key, (Integer)value);
            else if (value instanceof Long) editor.putLong(key, (Long)value);
            else if (value instanceof Float) editor.putFloat(key, (Float)value);
            else if (value instanceof java.util.Set) editor.putStringSet(key, (java.util.Set<String>)value);
        }
        require(editor.commit() && previous.equals(preferences.getAll()), "Original preferences were not restored");
    }
}
