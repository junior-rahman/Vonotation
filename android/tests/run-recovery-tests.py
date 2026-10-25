#!/usr/bin/env python3
"""Execute real recovery controllers with synthetic framework/provider boundaries."""
from pathlib import Path
import shutil, subprocess

root=Path(__file__).resolve().parents[1]/"app/build/recovery-tests"
root.mkdir(parents=True,exist_ok=True)
src=root/'src'
def write(path, text):
    p=src/path; p.parent.mkdir(parents=True,exist_ok=True); p.write_text(text)
production=Path(__file__).resolve().parents[1]/'app/src/main/java/app/voiceflow'
for name in ['Dictation','RetryBuffer','CaptureGate']:
    write(Path('app/voiceflow')/(name+'.java'), (production/(name+'.java')).read_text())
stubs={
'android/util/AtomicFile.java': 'package android.util; import java.io.*;import java.nio.file.*; public class AtomicFile {private File file;public AtomicFile(File f){file=f;}public FileOutputStream startWrite() throws IOException{return new FileOutputStream(file);}public void finishWrite(FileOutputStream out)throws IOException{out.getFD().sync();out.close();}public void failWrite(FileOutputStream out)throws IOException{out.close();file.delete();}public byte[] readFully()throws IOException{return Files.readAllBytes(file.toPath());}public void delete(){file.delete();}}',
'android/Manifest.java': 'package android; public class Manifest { public static class permission { public static final String RECORD_AUDIO="mic"; } }',
'android/content/Context.java': '''package android.content; import java.io.*; public class Context { private final File root; public Context(File r){root=r;root.mkdirs();} public Context getApplicationContext(){return this;} public int checkSelfPermission(String p){return 0;} public File getNoBackupFilesDir(){return root;} public File getCacheDir(){return root;} }''',
'android/content/Intent.java': 'package android.content; public class Intent { public Intent(String s){} public Intent putExtra(String k,Object v){return this;} }',
'android/content/pm/PackageManager.java': 'package android.content.pm; public class PackageManager { public static final int PERMISSION_GRANTED=0; }',
'android/os/Build.java': 'package android.os; public class Build { public static class VERSION { public static final int SDK_INT=35; } }',
'android/os/Bundle.java': 'package android.os; public class Bundle { public java.util.ArrayList<String> getStringArrayList(String k){return null;} }',
'android/os/Looper.java': 'package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }',
'android/os/Handler.java': '''package android.os; import java.util.*; public class Handler { public static final List<Handler> all=new ArrayList<>(); private final Queue<Runnable> q=new java.util.concurrent.ConcurrentLinkedQueue<>(); public Handler(Looper l){synchronized(all){all.add(this);}} public void post(Runnable r){q.add(r);} public void postDelayed(Runnable r,long ms){q.add(r);} public void removeCallbacks(Runnable r){q.remove(r);} public void removeCallbacksAndMessages(Object o){q.clear();} public static void drain(){List<Handler> hs; synchronized(all){hs=new ArrayList<>(all);} for(Handler h:hs){Runnable r;int n=0;while((r=h.q.poll())!=null&&n++<100)r.run();}} }''',
'android/media/MediaRecorder.java': '''package android.media; public class MediaRecorder { public MediaRecorder(){} public MediaRecorder(android.content.Context c){} public static class AudioSource {public static final int MIC=1;} public static class OutputFormat {public static final int MPEG_4=1;} public static class AudioEncoder {public static final int AAC=1;} public void setAudioSource(int n){} public void setOutputFormat(int n){} public void setAudioEncoder(int n){} public void setAudioSamplingRate(int n){} public void setAudioEncodingBitRate(int n){} public void setOutputFile(String s){} public static final int MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED=801;public interface OnInfoListener {void onInfo(MediaRecorder r,int what,int extra);}public void setOnInfoListener(OnInfoListener l){}public void setMaxFileSize(long n){}public void prepare(){} public void start(){} public void stop(){} public void release(){} public int getMaxAmplitude(){return 0;} }''',
'android/speech/RecognitionListener.java': '''package android.speech; import android.os.Bundle; public interface RecognitionListener {void onReadyForSpeech(Bundle p); void onBeginningOfSpeech();void onRmsChanged(float r);void onBufferReceived(byte[] b);void onEndOfSpeech();void onError(int e);void onResults(Bundle b);void onPartialResults(Bundle b);void onEvent(int t,Bundle b);}''',
'android/speech/RecognizerIntent.java': '''package android.speech; public class RecognizerIntent { public static final String ACTION_RECOGNIZE_SPEECH="",EXTRA_LANGUAGE_MODEL="",LANGUAGE_MODEL_FREE_FORM="",EXTRA_PARTIAL_RESULTS="",EXTRA_MAX_RESULTS="",EXTRA_MASK_OFFENSIVE_WORDS="",EXTRA_ENABLE_LANGUAGE_DETECTION="",EXTRA_ENABLE_LANGUAGE_SWITCH="",LANGUAGE_SWITCH_HIGH_PRECISION="",EXTRA_PREFER_OFFLINE=""; }''',
'android/speech/SpeechRecognizer.java': '''package android.speech; public class SpeechRecognizer {public static final int ERROR_NO_MATCH=7,ERROR_SPEECH_TIMEOUT=6;public static final String RESULTS_RECOGNITION="";public static boolean isOnDeviceRecognitionAvailable(android.content.Context c){return false;}public static boolean isRecognitionAvailable(android.content.Context c){return false;}public static SpeechRecognizer createOnDeviceSpeechRecognizer(android.content.Context c){return new SpeechRecognizer();}public static SpeechRecognizer createSpeechRecognizer(android.content.Context c){return new SpeechRecognizer();}public void setRecognitionListener(RecognitionListener l){}public void startListening(android.content.Intent i){}public void stopListening(){}public void cancel(){}public void destroy(){}}''',
'android/widget/Toast.java': '''package android.widget; public class Toast {public static final int LENGTH_LONG=1;public static Toast makeText(android.content.Context c,String s,int n){return new Toast();}public void show(){}}''',
'app/voiceflow/Stubs.java': '''package app.voiceflow; import android.content.Context; import java.io.File;
class CloudApi {static volatile String response="some recovered words"; CloudApi(Context c){} String transcribe(File f,boolean p,Runnable r){if(r!=null)r.run();return response;}String transform(String s,boolean a,boolean b){return s;}void cancel(){} }
class Store {static String engine(Context c){return "cloud";}static boolean verbatim(Context c){return false;}static boolean romanize(Context c){return false;}static boolean quickThoughts(Context c){return true;}static String clean(Context c,String s){return s;}static boolean bool(Context c,String s,boolean b){return b;}}
class Vault {static String get(Context c){return "synthetic-test-key";}}
class VoiceTake {static boolean updateBusy(){return false;}}
class Translate {static boolean going(){return false;}}
class LineWire {static boolean updateBusy(){return false;}}
class RecordingAwake {RecordingAwake(Context c){} void start(){} void close(){}}
class CaptureBarrier {static CaptureGate gate=new CaptureGate(); static CaptureGate.Lease begin(Context c){return gate.begin();}}
class FlowAccessibility {static FlowAccessibility instance;void updateBubble(){}}
class LanguagePolicy {static boolean needsRomanization(String s){return false;}}
class Cleaner {static String verbatim(String s){return s;}}
class Recovery {static boolean keep(Context c,String a,String b,long d,boolean e,boolean f){return true;}}
class Notes {static long keepThought(Context c,long id,String r,long d){return id;}static void formatThought(Context c,long id){}}
''',
'app/voiceflow/RecoveryAudit.java': '''package app.voiceflow;
import java.io.*; import java.nio.file.*; import android.os.Handler; import android.content.Context;
public class RecoveryAudit {
 static class Listener implements Dictation.Listener {int results,errors;String error="";public void state(String s){}public void partial(String s){}public void level(float v){}public void result(String r,String t,String w){results++;}public void error(String e){errors++;error=e;}}
 static void waitFor(java.util.function.BooleanSupplier ready,boolean drain) throws Exception {long end=System.nanoTime()+2_000_000_000L;while(!ready.getAsBoolean()&&System.nanoTime()<end){if(drain)Handler.drain();Thread.sleep(1);}if(!ready.getAsBoolean())throw new AssertionError("worker did not finish");}
 public static void main(String[] args) throws Exception {
  File root=Files.createTempDirectory("voitation-recovery-").toFile();Context c=new Context(root);File dir=RetryBuffer.dir(c);long now=System.currentTimeMillis();
  for(int i=5;i>=1;i--){File f=new File(dir,"retry-"+(now-i*60000)+".m4a");Files.write(f.toPath(),new byte[]{1,2,3});}
  File input=new File(root,"new.m4a");Files.write(input.toPath(),new byte[]{1,2,3});RetryBuffer.keep(c,input);
  if(RetryBuffer.list(c).size()!=6)throw new AssertionError("unexpired recordings were evicted");
  File retry=new File(root,"cancel-case.m4a");Files.write(retry.toPath(),new byte[]{1,2,3});Listener a=new Listener();Dictation da=new Dictation(c,a);da.retry(retry);
  waitFor(()->!RetryBuffer.transcript(retry).isEmpty(),false);da.cancel();Handler.drain();
  if(a.results!=0||!retry.exists()||RetryBuffer.transcript(retry).isEmpty())throw new AssertionError("cancel lost source or receipt");
  File empty=new File(root,"empty-case.m4a");Files.write(empty.toPath(),new byte[]{1,2,3});CloudApi.response="";Listener b=new Listener();Dictation db=new Dictation(c,b);db.retry(empty);waitFor(()->b.errors>0,true);db.close();
  if(b.results!=0||!empty.exists())throw new AssertionError("empty response consumed audio");
  Listener restored=new Listener();Dictation dr=new Dictation(c,restored);dr.retry(retry);waitFor(()->restored.results>0,true);
  if(!retry.exists())throw new AssertionError("delivery is not a durable save");
  dr.acknowledgeSaved();dr.close();if(retry.exists()||!RetryBuffer.transcript(retry).isEmpty())throw new AssertionError("acknowledgment did not remove source and receipt");
  File expired=new File(dir,"retry-"+(now-RetryBuffer.KEEP_MS-1000)+".m4a");Files.write(expired.toPath(),new byte[]{1});RetryBuffer.pin(expired);RetryBuffer.prune(c);if(!expired.exists())throw new AssertionError("active retry expired");RetryBuffer.unpin(expired);RetryBuffer.prune(c);if(expired.exists())throw new AssertionError("expired retry retained");
  System.out.println("PASS: unlimited unexpired queue, cancel-before-delivery, empty response, receipt-only retry, explicit durable acknowledgment and active expiry protection");
 }
}'''
}
for path,text in stubs.items():write(Path(path),text)
out=root/'classes';out.mkdir(exist_ok=True)
subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','-d',str(out)]+[str(p) for p in src.rglob('*.java')],check=True)
subprocess.run(['java','-ea','-cp',str(out),'app.voiceflow.RecoveryAudit'],check=True,timeout=10)
