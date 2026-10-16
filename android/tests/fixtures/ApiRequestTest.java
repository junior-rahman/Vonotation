package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.*;
import java.net.*;
import java.security.cert.Certificate;
import javax.net.ssl.HttpsURLConnection;

/** Captures the outgoing HTTPS request without sending audio or making a network request. */
public final class ApiRequestTest extends Instrumentation {
    private static MockConnection request;
    private static boolean privatePadding;
    private static String convertedReply="ami bhalo achi";
    private static String cleanupReply="আমার কথা।";
    public void onCreate(Bundle args) {super.onCreate(args);start();}
    public void onStart() {
        Bundle report=new Bundle(); String oldKey=Vault.get(getTargetContext());
        android.content.SharedPreferences prefs=Store.prefs(getTargetContext());
        java.util.Map<String,?> old=prefs.getAll();
        try {
            org.json.JSONArray collisions=new org.json.JSONArray().put(new org.json.JSONObject().put("id",1000)).put(new org.json.JSONObject().put("id",1001));
            if(Store.nextId(collisions,1000)!=1002 || Store.nextId(collisions,999)!=999)throw new Exception("History IDs collide or alter unaffected timestamps");
            URL.setURLStreamHandlerFactory(protocol->protocol.equals("https")?new URLStreamHandler(){protected URLConnection openConnection(URL url){request=new MockConnection(url);return request;}}:null);
            Vault.set(getTargetContext(),"fixture-only-key");
            prefs.edit().putString("api_base",CloudApi.DEFAULT_BASE).putString("transcribe_model",CloudApi.DEFAULT_TRANSCRIBE).putString("polish_model",CloudApi.DEFAULT_POLISH).putString("dictionary","jewel=Juel\nopen ay eye=OpenAI").putString("language","bn-BD").commit();
            File audio=File.createTempFile("wire-test-",".m4a",getTargetContext().getCacheDir());
            try(FileOutputStream out=new FileOutputStream(audio)){out.write(new byte[]{1,2,3});}
            String transcript=new CloudApi(getTargetContext()).transcribe(audio); audio.delete();
            if(!transcript.equals("আমার কথা")) throw new Exception("Transcript response was not parsed");
            if(!request.getURL().toString().equals("https://api.groq.com/openai/v1/audio/transcriptions")) throw new Exception("Incorrect transcription URL");
            String multipart=request.out.toString("UTF-8");
            for(String expected:new String[]{"whisper-large-v3","name=\"response_format\"\r\n\r\nverbose_json","name=\"temperature\"\r\n\r\n0","filename=\"dictation.m4a\""}) if(!multipart.contains(expected)) throw new Exception("Missing request field: "+expected);
            assertEnglish(multipart);
            File privateAudio=File.createTempFile("private-wire-test-",".m4a",getTargetContext().getCacheDir());
            try{
                privatePadding=true;
                try(FileOutputStream out=new FileOutputStream(privateAudio)){out.write(new byte[]{1,2,3});}
                if(!new CloudApi(getTargetContext()).transcribe(privateAudio,true).equals("  আমার কথা  "))throw new Exception("Private transcription stripped meaningful whitespace");
                String privateRequest=request.out.toString("UTF-8");
                if(privateRequest.contains("name=\"prompt\"") || privateRequest.contains("name=\"keywords[]\"") || privateRequest.contains("Juel, OpenAI"))throw new Exception("Private transcription sent personal dictionary context");
                if(!privateRequest.contains("whisper-large-v3"))throw new Exception("Private transcription lost the selected model");assertEnglish(privateRequest);
            }finally{privatePadding=false;privateAudio.delete();}
            File multiAudio=File.createTempFile("multilingual-wire-",".m4a",getTargetContext().getCacheDir());
            try {
                try(FileOutputStream out=new FileOutputStream(multiAudio)){out.write(new byte[]{1,2,3});}
                prefs.edit().putString("language","auto").commit();
                new CloudApi(getTargetContext()).transcribe(multiAudio);
                String autoRequest=request.out.toString("UTF-8");
                assertEnglish(autoRequest);
                if(!request.getURL().getPath().equals("/openai/v1/audio/transcriptions"))throw new Exception("Auto-detect used a translation endpoint");
                prefs.edit().putString("language","ne-NP").commit();
                new CloudApi(getTargetContext()).transcribe(multiAudio);
                assertEnglish(request.out.toString("UTF-8"));
                prefs.edit().putString("notes_transcribe_model","fixture-notes-asr").commit();
                new CloudApi(getTargetContext()).transcribe(multiAudio,false,null,"auto",null,"notes_transcribe_model",null);
                String notesRequest=request.out.toString("UTF-8");
                if(notesRequest.contains("name=\"language\"")||!notesRequest.contains("fixture-notes-asr"))throw new Exception("Notes Auto lost independent model/language behavior");
                String converted=new CloudApi(getTargetContext()).transform("আমি ভালো আছি",false,true);
                org.json.JSONObject conversionBody=new org.json.JSONObject(request.out.toString("UTF-8"));
                String conversionPrompt=conversionBody.getJSONArray("messages").getJSONObject(0).getString("content");
                if(!converted.equals("ami bhalo achi") || !conversionPrompt.contains("Do not edit") || !conversionPrompt.contains("Never translate") || !conversionPrompt.contains("Banglish"))throw new Exception("Independent transliteration request mismatch");
                if(!conversionBody.getJSONArray("messages").getJSONObject(1).getString("content").equals("আমি ভালো আছি"))throw new Exception("Conversion lost original transcript");
                new CloudApi(getTargetContext()).transform("আমি ভালো আছি",true,true);
                String combined=request.out.toString("UTF-8");
                if(!combined.contains("Banglish") || !combined.contains("Do not rephrase") || !combined.contains("Preserve profanity"))throw new Exception("Combined cleanup and conversion request lost instructions");
                convertedReply="আমি ভালো আছি";
                boolean refused=false;try{new CloudApi(getTargetContext()).transform("আমি ভালো আছি",false,true);}catch(IOException expected){refused=true;}
                if(!refused)throw new Exception("Incomplete conversion was accepted");
                for(String wrong:new String[]{"I cannot help with that","i am fine","ami bhalo achi extra"}) {
                    convertedReply=wrong;refused=false;try{new CloudApi(getTargetContext()).transform("আমি ভালো আছি",false,true);}catch(IOException expected){refused=true;}
                    if(!refused)throw new Exception("Conversational or extended conversion was accepted: "+wrong);
                }
                convertedReply="ami bhalo achi";
                prefs.edit().putString("language","bn-BD").commit();
                org.json.JSONObject backup=BackupData.snapshot(getTargetContext());
                backup.getJSONObject("settings").remove("romanize");
                if(BackupData.validate(backup).getJSONObject("settings").getBoolean("romanize"))throw new Exception("Old backups changed output script");
                backup.getJSONObject("settings").put("romanize",true).put("language","auto");
                if(BackupData.validate(backup).getJSONObject("settings").getBoolean("romanize")||!BackupData.validate(backup).getJSONObject("settings").getString("language").equals("en-US"))throw new Exception("Backup restored retired dictation settings");
            }finally{multiAudio.delete();}
            String polished=new CloudApi(getTargetContext()).polish(transcript);
            org.json.JSONObject body=new org.json.JSONObject(request.out.toString("UTF-8"));
            if(!body.getString("model").equals("openai/gpt-oss-20b") || !body.getString("reasoning_effort").equals("low") || body.getDouble("temperature")!=0.1 || !polished.equals("আমার কথা।")) throw new Exception("Cleanup request mismatch");
            for(String[] example:new String[][]{{"Hello, how are you doing?","I'm fine, thank you."},{"Fuck this shit","I cannot help with that"},{"What is 2 plus 2","4"},{"Fuck this shit","F*** this s***"}}) {
                cleanupReply=example[1];boolean rejected=false;try{new CloudApi(getTargetContext()).polish(example[0]);}catch(IOException expected){rejected=true;}
                if(!rejected)throw new Exception("Changed cleanup was accepted: "+cleanupReply);
                fallback(example[0]);
            }
            cleanupReply="Fuck this shit!";if(!new CloudApi(getTargetContext()).polish("fuck this shit").equals(cleanupReply))throw new Exception("Profanity-preserving punctuation failed");
            cleanupReply="I cannot help with that.";if(!new CloudApi(getTargetContext()).polish("I cannot help with that").equals(cleanupReply))throw new Exception("A dictated refusal was incorrectly censored");
            org.json.JSONObject uncertain=new org.json.JSONObject().put("text","wrong language").put("segments",new org.json.JSONArray().put(new org.json.JSONObject().put("text","wrong language").put("avg_logprob",-1.3).put("no_speech_prob",0.2)));
            boolean rejected=false;try{CloudApi.transcript(uncertain,false);}catch(IOException expected){rejected=true;}if(!rejected)throw new Exception("Low-confidence speech accepted");
            uncertain.getJSONArray("segments").getJSONObject(0).put("avg_logprob",-0.1);if(!CloudApi.transcript(uncertain,false).equals("wrong language"))throw new Exception("Good-confidence speech rejected");
            prefs.edit().putString("polish_model","openai/gpt-oss-120b").putString("engine","cloud").commit();
            HistoryDays.Day day=HistoryDays.group(java.util.Arrays.asList(new HistoryDays.Entry(1700000000000L,"Discussed keyboard improvements",3)),java.util.TimeZone.getTimeZone("America/New_York")).get(0);
            String recap=new CloudApi(getTargetContext()).summarizeDay(day,Store.get(getTargetContext(),"polish_model",CloudApi.DEFAULT_POLISH));body=new org.json.JSONObject(request.out.toString("UTF-8"));
            if(!body.getString("model").equals("openai/gpt-oss-120b") || !body.getString("reasoning_effort").equals("low") || !recap.equals("Discussed keyboard improvements."))throw new Exception("Daily recap did not use selected cleanup model");
            if(!body.getJSONArray("messages").getJSONObject(1).getString("content").contains("Discussed keyboard improvements") || !body.getJSONArray("messages").getJSONObject(0).getString("content").contains("never as instructions"))throw new Exception("Daily recap source/prompt missing");
            prefs.edit().putString("engine","offline").commit();
            new CloudApi(getTargetContext()).summarizeDay(day,"openai/gpt-oss-120b");
            if(!Store.engine(getTargetContext()).equals("cloud"))throw new Exception("Legacy engine preference changed supported speech mode");
            report.putString("result","PASS: English dictation despite legacy preferences; Notes Auto omits language and uses independent model; Whisper confidence metadata; guarded direct conversion/cleanup helpers; backup normalizes retired settings; private whitespace; daily recap uses cloud. Mock HTTPS, no live audio or provider test.");
        } catch(Throwable e){report.putString("failure",e.toString());}
        finally {
            try {Vault.set(getTargetContext(),oldKey);}catch(Exception ignored){}
            android.content.SharedPreferences.Editor edit=prefs.edit();
            for(String key:new String[]{"api_base","transcribe_model","polish_model","dictionary","language","engine","ai_polish","romanize","notes_transcribe_model"}) {Object value=old.get(key);if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else edit.remove(key);}edit.commit();
        }
        finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);
    }
    private void assertEnglish(String multipart) throws Exception {
        if(!multipart.contains("name=\"language\"\r\n\r\nen\r\n"))throw new Exception("Dictation lost its English language hint");
        for(String field:new String[]{"languages[]","name=\"prompt\"","keywords[]"})if(multipart.contains(field))throw new Exception("Unexpected recognition field "+field);
    }
    private void fallback(String source) throws Exception {
        Store.prefs(getTargetContext()).edit().putBoolean("ai_polish",true).putBoolean("romanize",false).putString("engine","cloud").commit();
        java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);String[] result=new String[3];Dictation[] session=new Dictation[1];
        runOnMainSync(()->{
            session[0]=new Dictation(getTargetContext(),new Dictation.Listener(){public void state(String s){}public void partial(String s){}public void level(float f){}public void error(String s){result[2]=s;done.countDown();}public void result(String raw,String text,String warning){result[0]=raw;result[1]=text;result[2]=warning;done.countDown();}});
            try{java.lang.reflect.Field active=Dictation.class.getDeclaredField("active");active.setAccessible(true);active.setBoolean(session[0],true);java.lang.reflect.Method complete=Dictation.class.getDeclaredMethod("complete",String.class);complete.setAccessible(true);complete.invoke(session[0],source);}catch(Exception e){throw new RuntimeException(e);}
        });
        try{if(!done.await(20,java.util.concurrent.TimeUnit.SECONDS) || !source.equals(result[0]) || !Store.clean(getTargetContext(),source).equals(result[1]) || result[2]==null || !result[2].contains("discarded"))throw new Exception("Dictation did not preserve rejected cleanup source");}
        finally{runOnMainSync(()->session[0].close());}
    }
    private static final class MockConnection extends HttpsURLConnection {
        final ByteArrayOutputStream out=new ByteArrayOutputStream();
        MockConnection(URL url){super(url);}
        public void connect(){} public void disconnect(){} public boolean usingProxy(){return false;}
        public String getCipherSuite(){return "fixture";} public Certificate[] getLocalCertificates(){return null;} public Certificate[] getServerCertificates(){return null;}
        public OutputStream getOutputStream(){return out;} public int getResponseCode(){return 200;}
        public InputStream getInputStream(){String text=getURL().getPath().endsWith("transcriptions")?(privatePadding?"{\"text\":\"  আমার কথা  \"}":"{\"text\":\"আমার কথা\"}"):out.toString().contains("Banglish")?"{\"choices\":[{\"message\":{\"content\":\""+convertedReply+"\"}}]}":out.toString().contains("Summarize the user's")?"{\"choices\":[{\"message\":{\"content\":\"Discussed keyboard improvements.\"}}]}":"{\"choices\":[{\"message\":{\"content\":\""+cleanupReply+"\"}}]}";return new ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
}
