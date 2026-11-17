package com.imal.drivora;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Voice for the built-in assistant.
 *
 * JS: Capacitor.Plugins.Voice
 *   listen({ lang })        -> { text }  one sentence from the speech recognizer (mic button).
 *                                         While hands-free is on it starts a command instead, see below.
 *   stop()
 *   speak({ text, lang })   "speech" { on } is sent when a reply starts and ends (the page turns music down)
 *   available()             -> { listen }
 *
 *   startHandsFree({ lang, threshold, nc })  -> { background }
 *   stopHandsFree()
 *   setWake({ threshold, lang })
 *       An on-device "Hey Jarvis" model listens all the time (WakeEngine), without the speech service,
 *       so there are no restart sounds and no data use while waiting. A foreground service keeps it
 *       working while Google Maps is in front. When the wake word is heard:
 *         "wake" { score }          a short tone plays, and the speech recognizer listens for the command
 *         "command" { texts:[...] } what was said, best guess first
 *         "commandEnd" { error }    always sent at the end; error is "" after a command, "nothing" if nothing was said
 *       "micProblem" { message }    the microphone is busy or silent
 *       "handsFree" { on:false, error } listening stopped (error is empty when stopped from the notification)
 *
 *   setNoise({ on }) / noiseInfo()  -> { on, supported, suppressor, echo, gain }
 *       The phone's noise suppressor, echo canceller and gain control on the microphone.
 *       For the mic button this needs Android 13+; hands-free uses them on any version.
 */
@CapacitorPlugin(
    name = "Voice",
    permissions = {
        @Permission(alias = "mic", strings = { "android.permission.RECORD_AUDIO" }),
        @Permission(alias = "notify", strings = { "android.permission.POST_NOTIFICATIONS" })
    }
)
public class VoicePlugin extends Plugin implements WakeEngine.Listener {

    private final Handler main = new Handler(Looper.getMainLooper());

    private SpeechRecognizer recognizer;
    private PluginCall pending;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String queuedText;
    private String queuedLang;

    /* hands-free */
    private WakeEngine engine;
    private boolean hfOn = false;
    private String hfLang = "en-US";
    private PluginCall hfCall;
    private boolean capturing = false;
    private boolean feedOk = Build.VERSION.SDK_INT >= 33;
    private boolean usingFeed = false;
    private boolean gotSpeech = false;
    private int feedEmpty = 0;
    private ParcelFileDescriptor feedRead;
    private ToneGenerator tone;
    private final Runnable capTimeout = new Runnable() {
        @Override
        public void run() {
            try {
                if (recognizer != null) recognizer.cancel();
            } catch (Exception ignored) {}
            endCommand("nothing");
        }
    };
    private final Runnable unmute = new Runnable() {
        @Override
        public void run() {
            quietBeep(false);
        }
    };

    /* noise cancellation for the mic button */
    private boolean noiseOn = false;
    private boolean noiseFeedOk = true;
    private boolean noiseActive = false;
    private Capture capture;

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

    /* ---------- mic button: one sentence ---------- */

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
                        if (hfOn && engine != null) {
                            /* hands-free is on: the button works like saying "Hey Jarvis" */
                            hfLang = lang;
                            JSObject o = new JSObject();
                            o.put("handsFree", true);
                            call.resolve(o);
                            if (!capturing) {
                                JSObject w = new JSObject();
                                w.put("score", 1);
                                notifyListeners("wake", w);
                                beginCommand();
                            }
                            return;
                        }
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
                                new Listener() {
                                    @Override
                                    public void onError(int error) {
                                        if (noiseActive && error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                                            noiseFailed();
                                            finish(null, "Noise cancellation does not work with this phone's speech service. It is off now. Try again.");
                                            return;
                                        }
                                        finish(null, describe(error));
                                    }

                                    @Override
                                    public void onResults(Bundle results) {
                                        String text = first(results);
                                        if (text.length() == 0) finish(null, "I did not hear anything. Tap the mic and try again.");
                                        else finish(text, null);
                                    }
                                }
                            );
                            Intent i = intent(lang, 3);
                            attachNoise(i);
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

    /* ---------- hands-free ---------- */

    @PluginMethod
    public void startHandsFree(PluginCall call) {
        boolean mic = getPermissionState("mic") == PermissionState.GRANTED;
        boolean askNotify = Build.VERSION.SDK_INT >= 33 && getPermissionState("notify") == PermissionState.PROMPT;
        if (!mic || askNotify) {
            requestPermissionForAliases(mic ? new String[] { "notify" } : (askNotify ? new String[] { "mic", "notify" } : new String[] { "mic" }), call, "onMicHandsFree");
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
        final Double thr = call.getDouble("threshold", 0.5);
        final Boolean nc = call.getBoolean("nc", false);
        getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        hfLang = lang;
                        if (engine == null) engine = new WakeEngine(getContext(), VoicePlugin.this);
                        engine.setThreshold(thr == null ? 0.5f : thr.floatValue());
                        engine.setEffects(Boolean.TRUE.equals(nc) || noiseOn);
                        HandsFreeService.onStopPressed = new Runnable() {
                            @Override
                            public void run() {
                                main.post(
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            if (!hfOn) return;
                                            hfStop();
                                            JSObject o = new JSObject();
                                            o.put("on", false);
                                            o.put("error", "");
                                            notifyListeners("handsFree", o);
                                        }
                                    }
                                );
                            }
                        };
                        try {
                            HandsFreeService.start(getContext());
                        } catch (Exception ignored) {
                            /* listening still works while the app is open */
                        }
                        if (hfCall != null && hfCall != call) {
                            PluginCall old = hfCall;
                            hfCall = null;
                            old.resolve(state());
                        }
                        hfOn = true;
                        if (engine.running()) {
                            call.resolve(state());
                            return;
                        }
                        hfCall = call;
                        engine.start();
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

    @PluginMethod
    public void setWake(final PluginCall call) {
        final Double thr = call.getDouble("threshold", null);
        final String lang = call.getString("lang", null);
        getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        if (lang != null && lang.length() > 0) hfLang = lang;
                        if (thr != null && engine != null) engine.setThreshold(thr.floatValue());
                        call.resolve(state());
                    }
                }
            );
    }

    private JSObject state() {
        JSObject o = new JSObject();
        o.put("on", hfOn);
        o.put("background", HandsFreeService.running);
        o.put("listening", engine != null && engine.listening());
        o.put("feed", feedOk);
        return o;
    }

    /* main thread only */
    private void hfStop() {
        hfOn = false;
        if (capturing) endCommand("stopped");
        if (engine != null) engine.stop();
        HandsFreeService.stop(getContext());
        if (hfCall != null) {
            PluginCall c = hfCall;
            hfCall = null;
            c.reject("Hands-free was turned off.");
        }
    }

    @Override
    public void onReady(String error) {
        if (error != null) {
            boolean was = hfOn;
            hfOn = false;
            HandsFreeService.stop(getContext());
            if (hfCall != null) {
                PluginCall c = hfCall;
                hfCall = null;
                c.reject(error);
            } else if (was) {
                JSObject o = new JSObject();
                o.put("on", false);
                o.put("error", error);
                notifyListeners("handsFree", o);
            }
            return;
        }
        if (hfCall != null) {
            PluginCall c = hfCall;
            hfCall = null;
            c.resolve(state());
        }
    }

    @Override
    public void onWake(float score) {
        if (!hfOn || capturing) return;
        JSObject o = new JSObject();
        o.put("score", score);
        notifyListeners("wake", o);
        beginCommand();
    }

    @Override
    public void onProblem(String message) {
        JSObject o = new JSObject();
        o.put("message", message);
        notifyListeners("micProblem", o);
    }

    /* main thread only: listen for the command that follows "Hey Jarvis" */
    private void beginCommand() {
        capturing = true;
        gotSpeech = false;
        chime();
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(getContext())) {
                endCommand("This device has no speech recognition. Install the Google app.");
                return;
            }
        } catch (Exception e) {
            endCommand("This device has no speech recognition. Install the Google app.");
            return;
        }
        startRecognizer(feedOk && Build.VERSION.SDK_INT >= 33 && engine != null && engine.listening());
    }

    private void startRecognizer(final boolean feed) {
        destroyRecognizer();
        closeFeed();
        usingFeed = feed;
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(getContext());
            recognizer.setRecognitionListener(
                new Listener() {
                    @Override
                    public void onBeginningOfSpeech() {
                        gotSpeech = true;
                    }

                    @Override
                    public void onReadyForSpeech(Bundle params) {
                        main.removeCallbacks(unmute);
                        main.postDelayed(unmute, 350);
                    }

                    @Override
                    public void onError(int error) {
                        if (!capturing) return;
                        boolean empty = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                        if (usingFeed && !empty && feedProblem(error)) {
                            /* this speech service cannot take our audio: let it use the microphone itself from now on */
                            feedOk = false;
                            startRecognizer(false);
                            return;
                        }
                        if (usingFeed && empty && !gotSpeech) {
                            /* twice nothing at all through our audio: the service may be ignoring it */
                            if (++feedEmpty >= 2) feedOk = false;
                        }
                        endCommand(empty ? "nothing" : describe(error));
                    }

                    @Override
                    public void onResults(Bundle results) {
                        if (!capturing) return;
                        if (usingFeed) feedEmpty = 0;
                        ArrayList<String> list = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        JSArray a = new JSArray();
                        if (list != null) for (String s : list) if (s != null && s.trim().length() > 0) a.put(s);
                        if (list == null || list.isEmpty() || first(results).length() == 0) {
                            endCommand("nothing");
                            return;
                        }
                        JSObject o = new JSObject();
                        o.put("texts", a);
                        notifyListeners("command", o);
                        endCommand("");
                    }
                }
            );
            final Intent i = intent(hfLang, 5);
            if (feed) {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                feedRead = pipe[0];
                OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
                engine.startTee(out, 250);
                i.putExtra("android.speech.extra.AUDIO_SOURCE", pipe[0]);
                i.putExtra("android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT", 1);
                i.putExtra("android.speech.extra.AUDIO_SOURCE_ENCODING", AudioFormat.ENCODING_PCM_16BIT);
                i.putExtra("android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE", WakeEngine.RATE);
                quietBeep(true);
                recognizer.startListening(i);
            } else {
                /* give the microphone to the speech service, take it back when the command is done */
                if (engine != null) engine.suspendMic(true);
                quietBeep(true);
                main.postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            if (!capturing || recognizer == null) return;
                            try {
                                recognizer.startListening(i);
                            } catch (Exception e) {
                                endCommand("Could not start listening.");
                            }
                        }
                    },
                    180
                );
            }
            main.removeCallbacks(unmute);
            main.postDelayed(unmute, 1500);
            main.removeCallbacks(capTimeout);
            main.postDelayed(capTimeout, 12000);
        } catch (Exception e) {
            if (feed) {
                feedOk = false;
                startRecognizer(false);
                return;
            }
            endCommand("Could not start listening.");
        }
    }

    private static boolean feedProblem(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
            case 12: /* language not supported */
            case 13: /* language unavailable */
                return false;
            default:
                return true;
        }
    }

    /* main thread only */
    private void endCommand(String error) {
        if (!capturing) return;
        capturing = false;
        main.removeCallbacks(capTimeout);
        if (engine != null) engine.stopTee();
        destroyRecognizer();
        closeFeed();
        if (engine != null) engine.suspendMic(false);
        quietBeep(false);
        JSObject o = new JSObject();
        o.put("error", error == null ? "" : error);
        notifyListeners("commandEnd", o);
    }

    private void closeFeed() {
        if (feedRead != null) {
            try {
                feedRead.close();
            } catch (Exception ignored) {}
            feedRead = null;
        }
    }

    private void chime() {
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 160);
        } catch (Exception ignored) {}
    }

    /* Some phones play a "ding" each time the speech service starts. Silence the system sounds for that moment. */
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

    private Intent intent(String lang, int max) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang == null ? "en-US" : lang);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, max);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getContext().getPackageName());
        return i;
    }

    private static String first(Bundle results) {
        ArrayList<String> list = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String t = (list != null && !list.isEmpty()) ? list.get(0) : "";
        return t == null ? "" : t.trim();
    }

    private void destroyRecognizer() {
        stopCapture();
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Exception ignored) {}
            recognizer = null;
        }
    }

    @Override
    protected void handleOnResume() {
        /* back in front: make sure the background permission is in place again */
        if (hfOn && !HandsFreeService.running) {
            try {
                HandsFreeService.start(getContext());
            } catch (Exception ignored) {}
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

    /** RecognitionListener with empty defaults */
    private abstract static class Listener implements RecognitionListener {

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
    }

    /* ---------- spoken replies ---------- */

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
                                                if (ttsReady) watchSpeech();
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

    private void watchSpeech() {
        try {
            tts.setOnUtteranceProgressListener(
                new UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {
                        speech(true);
                    }

                    @Override
                    public void onDone(String id) {
                        speech(false);
                    }

                    @Override
                    public void onError(String id) {
                        speech(false);
                    }

                    /* a newer reply cut this one off: without this the music stayed turned down */
                    @Override
                    public void onStop(String id, boolean interrupted) {
                        speech(false);
                    }
                }
            );
        } catch (Exception ignored) {}
    }

    private void speech(boolean on) {
        JSObject o = new JSObject();
        o.put("on", on);
        notifyListeners("speech", o);
    }

    private void sayNow(String text, String lang) {
        try {
            int r = tts.setLanguage(Locale.forLanguageTag(lang == null ? "en-US" : lang));
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-drive");
        } catch (Exception ignored) {}
    }

    /* ---------- noise cancellation ---------- */

    @PluginMethod
    public void setNoise(final PluginCall call) {
        final boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        noiseOn = on;
                        if (engine != null) engine.setEffects(on);
                        call.resolve(noiseState());
                    }
                }
            );
    }

    @PluginMethod
    public void noiseInfo(PluginCall call) {
        call.resolve(noiseState());
    }

    private JSObject noiseState() {
        JSObject o = new JSObject();
        boolean ns = false, aec = false, agc = false;
        try {
            ns = NoiseSuppressor.isAvailable();
        } catch (Throwable ignored) {}
        try {
            aec = AcousticEchoCanceler.isAvailable();
        } catch (Throwable ignored) {}
        try {
            agc = AutomaticGainControl.isAvailable();
        } catch (Throwable ignored) {}
        o.put("on", noiseOn);
        o.put("supported", true);
        o.put("suppressor", ns);
        o.put("echo", aec);
        o.put("gain", agc);
        return o;
    }

    /* main thread only. Mic button: give the recognizer our own cleaned microphone sound (Android 13+). */
    private void attachNoise(Intent i) {
        noiseActive = false;
        if (!noiseOn || !noiseFeedOk || Build.VERSION.SDK_INT < 33) return;
        Capture c = new Capture();
        try {
            ParcelFileDescriptor fd = c.start();
            i.putExtra("android.speech.extra.AUDIO_SOURCE", fd);
            i.putExtra("android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT", 1);
            i.putExtra("android.speech.extra.AUDIO_SOURCE_ENCODING", AudioFormat.ENCODING_PCM_16BIT);
            i.putExtra("android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE", Capture.RATE);
            capture = c;
            noiseActive = true;
        } catch (Throwable e) {
            c.stop(main);
        }
    }

    /* main thread only */
    private void stopCapture() {
        noiseActive = false;
        if (capture != null) {
            capture.stop(main);
            capture = null;
        }
    }

    /* main thread only: the mic button's speech service cannot take the cleaned sound */
    private void noiseFailed() {
        noiseFeedOk = false;
        stopCapture();
        JSObject o = new JSObject();
        o.put("on", true);
        o.put("note", "Noise cancellation does not work with this phone's speech service for the mic button. It still works for Hey Jarvis.");
        notifyListeners("noise", o);
    }

    /** Records the microphone with the phone's voice filters on and writes the cleaned sound into a pipe. */
    private static final class Capture {

        static final int RATE = 16000;
        private AudioRecord rec;
        private NoiseSuppressor ns;
        private AcousticEchoCanceler aec;
        private AutomaticGainControl agc;
        private ParcelFileDescriptor readEnd;
        private volatile boolean run = false;
        private boolean released = false;

        ParcelFileDescriptor start() throws Exception {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalStateException("no mic format");
            rec = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min * 4, 16384)
            );
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("mic busy");
            int sid = rec.getAudioSessionId();
            try {
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(sid);
                    if (ns != null) ns.setEnabled(true);
                }
            } catch (Throwable ignored) {}
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(sid);
                    if (aec != null) aec.setEnabled(true);
                }
            } catch (Throwable ignored) {}
            try {
                if (AutomaticGainControl.isAvailable()) {
                    agc = AutomaticGainControl.create(sid);
                    if (agc != null) agc.setEnabled(true);
                }
            } catch (Throwable ignored) {}
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            readEnd = pipe[0];
            final OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                try {
                    out.close();
                } catch (Exception ignored) {}
                throw new IllegalStateException("mic busy");
            }
            run = true;
            Thread t = new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        short[] buf = new short[1024];
                        byte[] bytes = new byte[2048];
                        /* first-order high-pass at about 120 Hz: takes out engine and road rumble */
                        final float a = 0.955f;
                        float px = 0f, py = 0f;
                        try {
                            while (run) {
                                int n = rec.read(buf, 0, buf.length);
                                if (n <= 0) break;
                                for (int k = 0; k < n; k++) {
                                    float x = buf[k];
                                    float y = a * (py + x - px);
                                    px = x;
                                    py = y;
                                    int v = Math.round(y);
                                    if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                                    bytes[2 * k] = (byte) (v & 0xff);
                                    bytes[2 * k + 1] = (byte) ((v >> 8) & 0xff);
                                }
                                out.write(bytes, 0, 2 * n);
                            }
                        } catch (Throwable ignored) {
                            /* the recognizer closed its end, or the recorder was stopped */
                        } finally {
                            try {
                                out.close();
                            } catch (Exception ignored) {}
                            release();
                        }
                    }
                },
                "jarvis-noise"
            );
            t.setDaemon(true);
            t.start();
            return readEnd;
        }

        void stop(Handler h) {
            run = false;
            try {
                if (rec != null) rec.stop();
            } catch (Throwable ignored) {}
            try {
                if (readEnd != null) readEnd.close();
            } catch (Throwable ignored) {}
            readEnd = null;
            /* the writer thread lets go of the microphone; make sure of it even if it is stuck on a full pipe */
            h.postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        release();
                    }
                },
                400
            );
        }

        private synchronized void release() {
            if (released) return;
            released = true;
            try {
                if (ns != null) ns.release();
            } catch (Throwable ignored) {}
            try {
                if (aec != null) aec.release();
            } catch (Throwable ignored) {}
            try {
                if (agc != null) agc.release();
            } catch (Throwable ignored) {}
            try {
                if (rec != null) rec.release();
            } catch (Throwable ignored) {}
        }
    }

    @Override
    protected void handleOnDestroy() {
        hfOn = false;
        capturing = false;
        main.removeCallbacks(capTimeout);
        main.removeCallbacks(unmute);
        if (engine != null) engine.stop();
        HandsFreeService.stop(getContext());
        HandsFreeService.onStopPressed = null;
        quietBeep(false);
        destroyRecognizer();
        closeFeed();
        try {
            if (tone != null) tone.release();
        } catch (Exception ignored) {}
        tone = null;
        try {
            if (tts != null) tts.shutdown();
        } catch (Exception ignored) {}
        tts = null;
    }
}
