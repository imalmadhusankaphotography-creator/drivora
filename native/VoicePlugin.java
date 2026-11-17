package com.imal.drivora;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
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
 *   "speech" { on } is sent when a spoken reply starts and ends, so the page can turn the music down.
 *   setNoise({ on })       -> { on, supported, suppressor, echo, gain }
 *   noiseInfo()            -> same
 *       Noise cancellation: the app records the microphone itself, runs the phone's noise suppressor,
 *       echo canceller and gain control plus a low-rumble filter over it, and hands the cleaned sound
 *       to the speech recognizer. Needs Android 13+. "noise" { on:false, error } is sent if the
 *       phone's speech service cannot take the cleaned sound, and listening carries on without it.
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
    private boolean noiseOn = false;
    private boolean noiseActive = false;
    private Capture capture;

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
                                        if (noiseActive && error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                                            noiseFailed();
                                            finish(null, "Noise cancellation does not work with this phone's speech service. It is off now. Try again.");
                                            return;
                                        }
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
                        if (noiseActive) {
                            /* the speech service could not take our cleaned sound: carry on with the plain microphone */
                            noiseFailed();
                            hfAgain(300);
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
            attachNoise(i);
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
        stopCapture();
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Exception ignored) {}
            recognizer = null;
        }
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
                        noiseOn = on && Build.VERSION.SDK_INT >= 33;
                        if (hfOn) hfAgain(100);
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
        o.put("supported", Build.VERSION.SDK_INT >= 33);
        o.put("suppressor", ns);
        o.put("echo", aec);
        o.put("gain", agc);
        return o;
    }

    /* main thread only. Gives the recognizer our own cleaned microphone sound instead of the raw microphone. */
    private void attachNoise(Intent i) {
        noiseActive = false;
        if (!noiseOn || Build.VERSION.SDK_INT < 33) return;
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
            noiseFailed();
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

    /* main thread only */
    private void noiseFailed() {
        noiseOn = false;
        stopCapture();
        JSObject o = new JSObject();
        o.put("on", false);
        o.put("error", "Noise cancellation does not work with this phone's speech service. It is off now.");
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

    @Override
    protected void handleOnDestroy() {
        hfOn = false;
        main.removeCallbacks(hfRestart);
        main.removeCallbacks(hfUnmute);
        quietBeep(false);
        stopCapture();
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
