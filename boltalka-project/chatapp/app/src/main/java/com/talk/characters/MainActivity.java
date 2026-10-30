package com.talk.characters;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer recognizer;
    private String pendingListenLang = null;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");

        tts = new TextToSpeech(this, status -> ttsReady = (status == TextToSpeech.SUCCESS));
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { js("window.__ttsStart&&__ttsStart()"); }
            @Override public void onDone(String id) { js("window.__ttsEnd&&__ttsEnd()"); }
            @Override public void onError(String id) { js("window.__ttsEnd&&__ttsEnd()"); }
        });
    }

    private void js(final String code) {
        ui.post(() -> web.evaluateJavascript(code, null));
    }

    private static String q(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    private Locale locale(String tag) {
        return Locale.forLanguageTag(tag);
    }

    private void startListening(String lang) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            js("window.__sttError&&__sttError(" + q("На телефоне нет распознавания речи (нужно приложение Google).") + ")");
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                public void onResults(Bundle r) {
                    ArrayList<String> list = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String t = (list != null && !list.isEmpty()) ? list.get(0) : "";
                    js("window.__sttResult&&__sttResult(" + q(t) + ")");
                }
                public void onError(int e) {
                    String m = (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                            ? "Не расслышал, попробуй ещё раз." : "Ошибка микрофона (" + e + ")";
                    js("window.__sttError&&__sttError(" + q(m) + ")");
                }
                public void onReadyForSpeech(Bundle p) {}
                public void onBeginningOfSpeech() {}
                public void onRmsChanged(float v) {}
                public void onBufferReceived(byte[] buf) {}
                public void onEndOfSpeech() {}
                public void onPartialResults(Bundle p) {}
                public void onEvent(int t, Bundle p) {}
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        recognizer.startListening(i);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 7 && pendingListenLang != null) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startListening(pendingListenLang);
            else js("window.__sttError&&__sttError(" + q("Разреши доступ к микрофону в настройках приложения.") + ")");
            pendingListenLang = null;
        }
    }

    @Override
    public void onBackPressed() {
        js("window.__back?__back():null");
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        if (recognizer != null) recognizer.destroy();
        super.onDestroy();
    }

    public class Bridge {
        @JavascriptInterface public boolean ok() { return true; }

        @JavascriptInterface
        public void speak(String text, String lang, float pitch, float rate) {
            if (!ttsReady) { js("window.__ttsEnd&&__ttsEnd()"); return; }
            tts.setLanguage(locale(lang));
            tts.setPitch(pitch);
            tts.setSpeechRate(rate);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.currentTimeMillis());
        }

        @JavascriptInterface
        public void stop() { if (tts != null) tts.stop(); }

        @JavascriptInterface
        public void listen(final String lang) {
            ui.post(() -> {
                if (tts != null) tts.stop();
                if (Build.VERSION.SDK_INT >= 23 &&
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingListenLang = lang;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
                } else {
                    startListening(lang);
                }
            });
        }

        @JavascriptInterface
        public void stopListen() { ui.post(() -> { if (recognizer != null) recognizer.stopListening(); }); }

        @JavascriptInterface
        public void vibrate() {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 400, 200, 400}, -1));
            else v.vibrate(800);
        }

        @JavascriptInterface
        public void exit() { ui.post(MainActivity.this::finish); }
    }
}
