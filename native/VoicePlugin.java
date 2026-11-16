package com.imal.drivora;

import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Voice for the built-in assistant: one-shot speech recognition and spoken replies.
 *
 * JS: Capacitor.Plugins.Voice
 *   listen({ lang })       -> { text }   (rejects with a plain message when nothing was understood)
 *   stop()                 -> finish listening early
 *   speak({ text, lang })
 *   available()            -> { listen: boolean }
 */
@CapacitorPlugin(name = "Voice", permissions = { @Permission(alias = "mic", strings = { "android.permission.RECORD_AUDIO" }) })
public class VoicePlugin extends Plugin {

    private SpeechRecognizer recognizer;
    private PluginCall pending;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String queuedText;
    private String queuedLang;

    @PluginMethod
    public void available(PluginCall call) {
        JSObject o = new JSObject();
        boolean ok = false;
        try {
            ok = SpeechRecognizer.isRecognitionAvailable(getContext());
        } catch (Exception ignored) {}
        o.put("listen", ok);
        call.resolve(o);
    }

    @PluginMethod
    public void listen(PluginCall call) {
        if (getPermissionState("mic") != PermissionState.GRANTED) {
            requestPermissionForAlias("mic", call, "onMic");
            return;
        }
        doListen(call);
    }

    @PermissionCallback
    private void onMic(PluginCall call) {
        if (getPermissionState("mic") != PermissionState.GRANTED) {
            call.reject("Microphone permission was not allowed.");
            return;
        }
        doListen(call);
    }

    private void doListen(final PluginCall call) {
        final String lang = call.getString("lang", "en-US");
        getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (!SpeechRecognizer.isRecognitionAvailable(getContext())) {
                                call.reject("This device has no speech recognition. Install the Google app, or type the command.");
                                return;
                            }
                            if (pending != null) {
                                PluginCall old = pending;
                                pending = null;
                                old.reject("Cancelled.");
                            }
                            destroyRecognizer();
                            pending = call;
                            recognizer = SpeechRecognizer.createSpeechRecognizer(getContext());
                            recognizer.setRecognitionListener(
                                new RecognitionListener() {
                                    @Override
                                    public void onReadyForSpeech(Bundle params) {}

                                    @Override
                                    public void onBeginningOfSpeech() {}

                                    @Override
                                    public void onRmsChanged(float rmsdB) {}

                                    @Override
                                    public void onBufferReceived(byte[] buffer) {}

                                    @Override
                                    public void onEndOfSpeech() {}

                                    @Override
                                    public void onPartialResults(Bundle partialResults) {}

                                    @Override
                                    public void onEvent(int eventType, Bundle params) {}

                                    @Override
                                    public void onError(int error) {
                                        finish(null, describe(error));
                                    }

                                    @Override
                                    public void onResults(Bundle results) {
                                        ArrayList<String> list = results == null
                                            ? null
                                            : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                                        String text = (list != null && !list.isEmpty()) ? list.get(0) : "";
                                        if (text == null || text.length() == 0) {
                                            finish(null, "I did not hear anything. Tap the mic and try again.");
                                        } else {
                                            finish(text, null);
                                        }
                                    }
                                }
                            );
                            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
                            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
                            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getContext().getPackageName());
                            recognizer.startListening(i);
                        } catch (Exception e) {
                            finish(null, "Could not start listening.");
                        }
                    }
                }
            );
    }

    @PluginMethod
    public void stop(final PluginCall call) {
        getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (recognizer != null) recognizer.stopListening();
                        } catch (Exception ignored) {}
                        call.resolve();
                    }
                }
            );
    }

    /* main thread only */
    private void finish(String text, String error) {
        PluginCall c = pending;
        pending = null;
        destroyRecognizer();
        if (c == null) return;
        if (error != null) {
            c.reject(error);
        } else {
            JSObject o = new JSObject();
            o.put("text", text);
            c.resolve(o);
        }
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Exception ignored) {}
            recognizer = null;
        }
    }

    private static String describe(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "I did not hear anything. Tap the mic and try again.";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                return "Voice needs an internet connection right now.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Microphone permission is off for this app.";
            case SpeechRecognizer.ERROR_AUDIO:
                return "The microphone is not working.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Voice is busy. Try again in a moment.";
            case 12: /* ERROR_LANGUAGE_NOT_SUPPORTED */
            case 13: /* ERROR_LANGUAGE_UNAVAILABLE */
                return "That voice language is not installed on this device.";
            default:
                return "I could not understand that. Try again.";
        }
    }

    @PluginMethod
    public void speak(final PluginCall call) {
        final String text = call.getString("text", "");
        final String lang = call.getString("lang", "en-US");
        getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (tts == null) {
                                queuedText = text;
                                queuedLang = lang;
                                tts =
                                    new TextToSpeech(
                                        getContext(),
                                        new TextToSpeech.OnInitListener() {
                                            @Override
                                            public void onInit(int status) {
                                                ttsReady = (status == TextToSpeech.SUCCESS);
                                                if (ttsReady && queuedText != null) sayNow(queuedText, queuedLang);
                                                queuedText = null;
                                            }
                                        }
                                    );
                            } else if (ttsReady) {
                                sayNow(text, lang);
                            } else {
                                queuedText = text;
                                queuedLang = lang;
                            }
                        } catch (Exception ignored) {}
                        call.resolve();
                    }
                }
            );
    }

    private void sayNow(String text, String lang) {
        try {
            int r = tts.setLanguage(Locale.forLanguageTag(lang == null ? "en-US" : lang));
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-drive");
        } catch (Exception ignored) {}
    }

    @Override
    protected void handleOnDestroy() {
        try {
            if (recognizer != null) recognizer.destroy();
        } catch (Exception ignored) {}
        recognizer = null;
        try {
            if (tts != null) tts.shutdown();
        } catch (Exception ignored) {}
        tts = null;
    }
}
