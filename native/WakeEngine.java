package com.imal.drivora;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Listens to the microphone all the time and runs the on-device "Hey Jarvis" model on it.
 * No speech service, no network, no restart sounds. Only after the wake word is heard does the
 * app start the speech recognizer for the command, and on Android 13+ it feeds the recognizer
 * this same audio (including the moment right after "Jarvis"), so the microphone is never handed over.
 */
final class WakeEngine {

    interface Listener {
        /** main thread */
        void onReady(String error);

        /** main thread */
        void onWake(float score);

        /** main thread: the microphone is busy or silent */
        void onProblem(String message);
    }

    static final int RATE = 16000;
    private static final int RING = RATE * 2;

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean run = false;
    private volatile int gen = 0;
    private volatile boolean paused = false;
    private volatile boolean reopen = false;
    private volatile boolean effects = false;
    private volatile float threshold = 0.5f;
    private volatile boolean listening = false;
    private Thread thread;

    private final short[] ring = new short[RING];
    private long total = 0;
    private Tee tee;

    WakeEngine(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    boolean running() {
        return run;
    }

    void setThreshold(float t) {
        threshold = Math.max(0.1f, Math.min(0.95f, t));
    }

    void setEffects(boolean on) {
        if (on != effects) {
            effects = on;
            reopen = true;
        }
    }

    /** true once the microphone is open and the model is running */
    boolean listening() {
        return listening && !paused;
    }

    void start() {
        if (run) {
            post(null);
            return;
        }
        run = true;
        paused = false;
        final int my = ++gen;
        thread = new Thread(
            new Runnable() {
                @Override
                public void run() {
                    loop(my);
                }
            },
            "jarvis-wake"
        );
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        run = false;
        gen++;
        stopTee();
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
    }

    /** let go of the microphone for a while (another recognizer needs it), or take it back */
    void suspendMic(boolean on) {
        paused = on;
    }

    /** Send the microphone sound, starting prerollMs before now, into out until stopTee(). */
    synchronized void startTee(OutputStream out, int prerollMs) {
        stopTeeLocked();
        Tee t = new Tee(out);
        int n = (int) Math.min(Math.min(total, RING), (long) RATE * prerollMs / 1000);
        if (n > 0) {
            short[] pre = new short[n];
            int start = (int) ((total - n) % RING);
            for (int i = 0; i < n; i++) pre[i] = ring[(start + i) % RING];
            t.offer(pre, n);
        }
        tee = t;
        t.start();
    }

    synchronized void stopTee() {
        stopTeeLocked();
    }

    private void stopTeeLocked() {
        if (tee != null) {
            tee.finish();
            tee = null;
        }
    }

    private synchronized void feed(short[] chunk, int n) {
        for (int i = 0; i < n; i++) ring[(int) ((total + i) % RING)] = chunk[i];
        total += n;
        if (tee != null && !tee.offer(chunk, n)) tee = null;
    }

    private synchronized boolean teeing() {
        return tee != null;
    }

    /* ---------- the listening thread ---------- */

    /* true while this thread is the current one: a quick off-and-on never leaves two threads on the microphone */
    private boolean alive(int my) {
        return run && my == gen;
    }

    private void loop(int my) {
        OrtEnvironment env = null;
        OrtNet mel = null, emb = null, word = null;
        WakeDetector det;
        try {
            env = OrtEnvironment.getEnvironment();
            mel = new OrtNet(env, asset("wake/melspectrogram.onnx"));
            emb = new OrtNet(env, asset("wake/embedding_model.onnx"));
            word = new OrtNet(env, asset("wake/hey_jarvis_v0.1.onnx"));
            det = new WakeDetector(mel, emb, word);
        } catch (Throwable e) {
            if (my == gen) run = false;
            close(mel, emb, word);
            post("The Hey Jarvis model could not load on this device (" + e.getClass().getSimpleName() + ").");
            return;
        }

        AudioRecord rec = null;
        Effects fx = null;
        boolean told = false;
        long quietSince = 0, lastProblem = 0, lastWake = 0;
        short[] chunk = new short[WakeDetector.CHUNK];
        try {
            while (alive(my)) {
                if (paused || reopen) {
                    listening = false;
                    if (fx != null) fx.release();
                    fx = null;
                    release(rec);
                    rec = null;
                    reopen = false;
                    if (paused) {
                        sleep(80);
                        continue;
                    }
                }
                if (rec == null) {
                    rec = open();
                    if (rec == null) {
                        if (!told) {
                            told = true;
                            post(null);
                        }
                        long now = System.currentTimeMillis();
                        if (now - lastProblem > 30000) {
                            lastProblem = now;
                            problem("The microphone is busy. Another app may be using it.");
                        }
                        sleep(1500);
                        continue;
                    }
                    if (effects) fx = new Effects(rec.getAudioSessionId());
                    det.reset();
                    quietSince = System.currentTimeMillis();
                    listening = true;
                    if (!told) {
                        told = true;
                        post(null);
                    }
                }
                int got = 0;
                while (got < chunk.length && alive(my) && !paused && !reopen) {
                    int n = rec.read(chunk, got, chunk.length - got);
                    if (n <= 0) break;
                    got += n;
                }
                if (got < chunk.length) {
                    if (alive(my) && !paused && !reopen) {
                        /* the microphone stopped giving sound: open it again */
                        listening = false;
                        if (fx != null) fx.release();
                        fx = null;
                        release(rec);
                        rec = null;
                        sleep(500);
                    }
                    continue;
                }
                feed(chunk, got);

                /* a microphone that gives only zeros is being held by another app */
                int peak = 0;
                for (int i = 0; i < got; i++) {
                    int v = Math.abs((int) chunk[i]);
                    if (v > peak) peak = v;
                }
                long now = System.currentTimeMillis();
                if (peak > 3) quietSince = now;
                else if (now - quietSince > 8000 && now - lastProblem > 60000) {
                    lastProblem = now;
                    problem("The microphone gives no sound. Another app may be using it, or it is muted.");
                }

                float score = det.process(chunk);
                if (score >= threshold && now - lastWake > 2000 && !teeing()) {
                    lastWake = now;
                    final float s = score;
                    main.post(
                        new Runnable() {
                            @Override
                            public void run() {
                                listener.onWake(s);
                            }
                        }
                    );
                    det.reset();
                }
            }
        } catch (Throwable e) {
            if (alive(my)) {
                run = false;
                post("Hey Jarvis stopped: " + e.getClass().getSimpleName());
            }
        } finally {
            if (my == gen) listening = false;
            if (fx != null) fx.release();
            release(rec);
            close(mel, emb, word);
        }
    }

    private AudioRecord open() {
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) return null;
            AudioRecord r = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min * 4, WakeDetector.CHUNK * 2 * 8)
            );
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                return null;
            }
            r.startRecording();
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                r.release();
                return null;
            }
            return r;
        } catch (Throwable e) {
            return null;
        }
    }

    private static void release(AudioRecord r) {
        if (r == null) return;
        try {
            r.stop();
        } catch (Throwable ignored) {}
        try {
            r.release();
        } catch (Throwable ignored) {}
    }

    private void post(final String error) {
        main.post(
            new Runnable() {
                @Override
                public void run() {
                    listener.onReady(error);
                }
            }
        );
    }

    private void problem(final String msg) {
        main.post(
            new Runnable() {
                @Override
                public void run() {
                    listener.onProblem(msg);
                }
            }
        );
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {}
    }

    private byte[] asset(String name) throws Exception {
        InputStream in = ctx.getAssets().open(name);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void close(OrtNet... nets) {
        for (OrtNet n : nets) if (n != null) n.close();
    }

    /** the phone's voice filters, when "Noise cancellation" is on */
    private static final class Effects {

        private NoiseSuppressor ns;
        private AcousticEchoCanceler aec;
        private AutomaticGainControl agc;

        Effects(int sid) {
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
        }

        void release() {
            try {
                if (ns != null) ns.release();
            } catch (Throwable ignored) {}
            try {
                if (aec != null) aec.release();
            } catch (Throwable ignored) {}
            try {
                if (agc != null) agc.release();
            } catch (Throwable ignored) {}
        }
    }

    /** Writes audio to the recognizer on its own thread, so a slow reader never stalls the wake word. */
    private static final class Tee implements Runnable {

        private final OutputStream out;
        private final LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<byte[]>(400);
        private volatile boolean open = true;
        private static final byte[] END = new byte[0];

        Tee(OutputStream out) {
            this.out = out;
        }

        void start() {
            Thread t = new Thread(this, "jarvis-tee");
            t.setDaemon(true);
            t.start();
        }

        boolean offer(short[] s, int n) {
            if (!open) return false;
            byte[] b = new byte[n * 2];
            for (int i = 0; i < n; i++) {
                b[2 * i] = (byte) (s[i] & 0xff);
                b[2 * i + 1] = (byte) ((s[i] >> 8) & 0xff);
            }
            q.offer(b);
            return true;
        }

        void finish() {
            open = false;
            q.offer(END);
        }

        @Override
        public void run() {
            try {
                while (true) {
                    byte[] b = q.poll(15, TimeUnit.SECONDS);
                    if (b == null || b == END) break;
                    out.write(b);
                }
            } catch (Throwable ignored) {
                /* the recognizer closed its end */
            } finally {
                open = false;
                try {
                    out.close();
                } catch (Throwable ignored) {}
            }
        }
    }

    /** one ONNX model through ONNX Runtime */
    private static final class OrtNet implements WakeDetector.Net {

        private final OrtEnvironment env;
        private final OrtSession session;
        private final String input;

        OrtNet(OrtEnvironment env, byte[] model) throws Exception {
            this.env = env;
            OrtSession.SessionOptions o = new OrtSession.SessionOptions();
            o.setIntraOpNumThreads(1);
            o.setInterOpNumThreads(1);
            session = env.createSession(model, o);
            input = session.getInputNames().iterator().next();
        }

        @Override
        public float[] run(float[] x, long[] shape) throws Exception {
            OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), shape);
            try {
                OrtSession.Result r = session.run(Collections.singletonMap(input, t));
                try {
                    FloatBuffer fb = ((OnnxTensor) r.get(0)).getFloatBuffer();
                    float[] out = new float[fb.remaining()];
                    fb.get(out);
                    return out;
                } finally {
                    r.close();
                }
            } finally {
                t.close();
            }
        }

        void close() {
            try {
                session.close();
            } catch (Throwable ignored) {}
        }
    }
}
