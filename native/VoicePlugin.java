package com.imal.drivora;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
 *   startHandsFree({ lang }) / stopHandsFree()
 *       keeps listening and sends every sentence it hears as a "heard" { text } event;
 *       the web layer looks for the wake word ("Hey Jarvis") in it.
 *       "handsFree" { on, error } is sent if listening has to stop.
 */
@CapacitorPlugin(name = "Voice", permissions = { @Permission(alias = "mic", strings = { "android.permission.RECORD_AUDIO" }) })
public class VoicePlugin extends Plugin {

    private SpeechRecognizer recognizer;
    private PluginCall pending;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String queuedText;
    private String queuedLang;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean hfOn = false;
    private boolean hfOnDevice = true;
    private String hfLang = "en-US";
    private int hfErrors = 0;
    private final Runnable hfRestart = new Runnable() {
        @Override
        public void run() {
            hfListen();
        }
    };
    private final Runnable hfUnmute = new Runnable() {
        @Override
        public void run() {
            quietBeep(false);
        }
    };

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
        if (hfOn) {
            call.reject("Hands-free listening is already on.");
            return;
        }
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

    /* ---------- hands-free: listen all the time, restart after every sentence or silence ---------- */

    @PluginMethod
    public void startHandsFree(PluginCall call) {
        if (getPermissionState("mic") != PermissionState.GRANTED) {
            requestPermissionForAlias("mic", call, "onMicHandsFree");
            return;
        }
        doStartHandsFree(call);
    }

    @PermissionCallback
    private void onMicHandsFree(PluginCall call) {
        if (getPermissionState("mic") != PermissionState.GRANTED) {
            call.reject("Microphone permission was not allowed.");
            return;
        }
        doStartHandsFree(call);
    }

    private void doStartHandsFree(final PluginCall call) {
        final String lang = call.getString("lang", "en-US");
        getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (!SpeechRecognizer.isRecognitionAvailable(getContext())) {
                                call.reject("This device has no speech recognition. Install the Google app to use Hey Jarvis.");
                                return;
                            }
                            if (pending != null) {
                                PluginCall old = pending;
                                pending = null;
                                old.reject("Cancelled.");
                            }
                            hfLang = lang;
                            hfOn = true;
                            hfOnDevice = true;
                            hfErrors = 0;
                            main.removeCallbacks(hfRestart);
                            hfListen();
                            call.resolve();
                        } catch (Exception e) {
                            hfOn = false;
                            call.reject("Could not start hands-free listening.");
                        }
                    }
                }
            );
    }

    @PluginMethod
    public void stopHandsFree(final PluginCall call) {
        getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        hfStop();
                        call.resolve();
                    }
                }
            );
    }

    /* main thread only */
    private void hfStop() {
        hfOn = false;
        main.removeCallbacks(hfRestart);
        main.removeCallbacks(hfUnmute);
        destroyRecognizer();
        quietBeep(false);
    }

    /* main thread only */
    private void hfListen() {
        if (!hfOn) return;
        try {
            destroyRecognizer();
            boolean onDevice = false;
            if (hfOnDevice && Build.VERSION.SDK_INT >= 31) {
                try {
                    onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(getContext());
                } catch (Exception ignored) {}
            }
            final boolean usedOnDevice = onDevice;
            recognizer = onDevice
                ? SpeechRecognizer.createOnDeviceSpeechRecognizer(getContext())
                : SpeechRecognizer.createSpeechRecognizer(getContext());
            recognizer.setRecognitionListener(
                new RecognitionListener() {
                    @Override
                    public void onReadyForSpeech(Bundle params) {
                        main.removeCallbacks(hfUnmute);
                        main.postDelayed(hfUnmute, 350);
                    }

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
                        if (!hfOn) return;
                        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            hfErrors = 0;
                            hfAgain(200);
                            return;
                        }
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            hfStop();
                            JSObject o = new JSObject();
                            o.put("on", false);
                            o.put("error", describe(error));
                            notifyListeners("handsFree", o);
                            return;
                        }
                        if (usedOnDevice) {
                            /* the offline recognizer cannot do this language: use the normal one */
                            hfOnDevice = false;
                            hfAgain(300);
                            return;
                        }
                        hfErrors++;
                        hfAgain(Math.min(8000, 1000 * hfErrors));
                    }

                    @Override
                    public void onResults(Bundle results) {
                        if (!hfOn) return;
                        hfErrors = 0;
                        ArrayList<String> list = results == null
                            ? null
                            : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (list != null && !list.isEmpty() && list.get(0) != null && list.get(0).length() > 0) {
                            JSObject o = new JSObject();
                            o.put("text", list.get(0));
                            notifyListeners("heard", o);
                        }
                        hfAgain(150);
                    }
                }
            );
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, hfLang);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getContext().getPackageName());
            quietBeep(true);
            main.removeCallbacks(hfUnmute);
            main.postDelayed(hfUnmute, 1500);
            recognizer.startListening(i);
        } catch (Exception e) {
            hfErrors++;
            hfAgain(Math.min(8000, 1000 * hfErrors));
        }
    }

    private void hfAgain(long delayMs) {
        main.removeCallbacks(hfRestart);
        if (hfOn) main.postDelayed(hfRestart, delayMs);
    }

    /* Some phones play a "ding" each time listening starts. Silence the system sound streams for that moment only. */
    private void quietBeep(boolean quiet) {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            int dir = quiet ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, dir, 0);
            am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, dir, 0);
        } catch (Exception ignored) {}
    }

    @Override
    protected void handleOnResume() {
        /* make sure the loop is alive again after the app was in the background */
        if (hfOn) {
            hfErrors = 0;
            hfAgain(400);
        }
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
        hfOn = false;
        main.removeCallbacks(hfRestart);
        main.removeCallbacks(hfUnmute);
        quietBeep(false);
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
