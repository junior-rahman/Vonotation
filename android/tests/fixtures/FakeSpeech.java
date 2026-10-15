package app.voiceflow.fixture;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

/** Synthetic speech for Android UI integration testing; never shipped in VoiceFlow.apk. */
public class FakeSpeech extends RecognitionService {
    private Callback current;
    private int timeoutRound;
    private int emptyRound;
    private boolean emptyOnStop;
    static volatile boolean composeMode;
    static volatile String credentialText;
    private Bundle words(String text) { Bundle b=new Bundle(); ArrayList<String> words=new ArrayList<>(); words.add(text); b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,words); return b; }
    protected void onStartListening(Intent intent,Callback listener) {
        if(android.os.Build.VERSION.SDK_INT>=31) createContext(new android.content.ContextParams.Builder().setNextAttributionSource(listener.getCallingAttributionSource()).build());
        current=listener;
        String language=intent.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE);
        emptyOnStop="fixture-empty-stop".equals(language);
        if(emptyOnStop || "fixture-empty-results".equals(language)){
            final int round=emptyOnStop?1:++emptyRound;
            try{listener.readyForSpeech(new Bundle());}catch(Exception ignored){}
            if(round==1){
                new Handler(getMainLooper()).postDelayed(()->{try{if(current==listener)listener.partialResults(words(emptyOnStop?"um recovered when stopped":"um recovered from empty results"));}catch(Exception ignored){}},100);
                new Handler(getMainLooper()).postDelayed(()->{try{if(current==listener)listener.partialResults(words(""));}catch(Exception ignored){}},180);
                if(!emptyOnStop)new Handler(getMainLooper()).postDelayed(()->{try{if(current==listener){listener.results(words(""));current=null;}}catch(Exception ignored){}},260);
            }else new Handler(getMainLooper()).postDelayed(()->{try{if(current==listener){listener.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT);current=null;}}catch(Exception ignored){}},200);
            return;
        }
        emptyRound=0;
        if("fixture-timeouts".equals(intent.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE))){
            final int round=++timeoutRound;
            try{listener.readyForSpeech(new Bundle());}catch(Exception ignored){}
            new Handler(getMainLooper()).postDelayed(()->{try{if(current==listener){if(round<=2)listener.partialResults(words(round==1?"um recovered":"words"));listener.error(round<=2?SpeechRecognizer.ERROR_NO_MATCH:SpeechRecognizer.ERROR_SPEECH_TIMEOUT);current=null;}}catch(Exception ignored){}},200);
            return;
        }
        timeoutRound=0;
        final String credential=credentialText;
        try { listener.readyForSpeech(new Bundle()); listener.beginningOfSpeech(); } catch(Exception ignored) {}
        new Handler(getMainLooper()).postDelayed(()->{try {if(current==listener)listener.partialResults(words(credential==null?"um hello":credential));}catch(Exception ignored){}},200);
        if(!composeMode)new Handler(getMainLooper()).postDelayed(()->{try {if(current==listener)listener.partialResults(words(credential==null?"um hello comma world":credential));}catch(Exception ignored){}},700);
    }
    protected void onStopListening(Callback listener) { try { listener.endOfSpeech(); listener.results(words(emptyOnStop?"":credentialText==null?"um hello comma world":credentialText)); } catch(Exception ignored) {} current=null; }
    protected void onCancel(Callback listener) { current=null; }
}
