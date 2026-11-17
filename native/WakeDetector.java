package com.imal.drivora;

import java.util.ArrayList;
import java.util.Random;

/**
 * "Hey Jarvis" wake word, fully on the device. A port of the openWakeWord streaming pipeline
 * (github.com/dscripka/openWakeWord, Apache-2.0):
 *
 *   16 kHz 16-bit audio, 1280 samples (80 ms) at a time
 *     -> melspectrogram model  (last 1280 + 480 samples -> 8 new frames of 32 bands, then x / 10 + 2)
 *     -> embedding model       (last 76 frames -> one 96-number embedding)
 *     -> wake word model       (last 16 embeddings -> score 0..1)
 *
 * No Android classes here, so it can be checked on a desktop JVM against the Python reference.
 */
public final class WakeDetector {

    /** Runs one ONNX model: flat float input with a shape, flat float output. */
    public interface Net {
        float[] run(float[] input, long[] shape) throws Exception;
    }

    public static final int CHUNK = 1280;
    private static final int CONTEXT = 480;
    private static final int BANDS = 32;
    private static final int WINDOW = 76;
    private static final int EMB = 96;
    private static final int FEATURES = 16;
    private static final int WARMUP = 5;

    private final Net mel;
    private final Net emb;
    private final Net word;
    private final short[] raw = new short[CHUNK + CONTEXT];
    private int rawLen = 0;
    private final ArrayList<float[]> frames = new ArrayList<float[]>();
    private final ArrayList<float[]> features = new ArrayList<float[]>();
    private int calls = 0;

    public WakeDetector(Net mel, Net emb, Net word) throws Exception {
        this.mel = mel;
        this.emb = emb;
        this.word = word;
        reset();
    }

    /** Back to the start state: mel buffer of ones, features from 4 s of quiet noise (as the reference does). */
    public void reset() throws Exception {
        rawLen = 0;
        calls = 0;
        frames.clear();
        for (int i = 0; i < WINDOW; i++) {
            float[] f = new float[BANDS];
            java.util.Arrays.fill(f, 1f);
            frames.add(f);
        }
        features.clear();
        Random rnd = new Random(7);
        short[] noise = new short[16000 * 4];
        for (int i = 0; i < noise.length; i++) noise[i] = (short) (rnd.nextInt(2000) - 1000);
        float[][] spec = melFrames(noise, 0, noise.length);
        int n = 0;
        for (int i = 0; i + WINDOW <= spec.length; i += 8) n++;
        if (n > 0) {
            float[] batch = new float[n * WINDOW * BANDS];
            int b = 0;
            for (int i = 0; i + WINDOW <= spec.length; i += 8) {
                for (int r = 0; r < WINDOW; r++) {
                    System.arraycopy(spec[i + r], 0, batch, (b * WINDOW + r) * BANDS, BANDS);
                }
                b++;
            }
            float[] out = emb.run(batch, new long[] { n, WINDOW, BANDS, 1 });
            for (int i = Math.max(0, n - FEATURES); i < n; i++) {
                float[] e = new float[EMB];
                System.arraycopy(out, i * EMB, e, 0, EMB);
                features.add(e);
            }
        }
    }

    /** Feed exactly CHUNK samples. Returns the wake word score for this moment (0..1). */
    public float process(short[] chunk) throws Exception {
        if (chunk.length != CHUNK) throw new IllegalArgumentException("need " + CHUNK + " samples");
        // keep the last CHUNK + CONTEXT samples
        if (rawLen + CHUNK > raw.length) {
            int keep = raw.length - CHUNK;
            System.arraycopy(raw, rawLen - keep, raw, 0, keep);
            rawLen = keep;
        }
        System.arraycopy(chunk, 0, raw, rawLen, CHUNK);
        rawLen += CHUNK;

        float[][] spec = melFrames(raw, 0, rawLen);
        for (float[] f : spec) frames.add(f);
        while (frames.size() > WINDOW) frames.remove(0);

        if (frames.size() == WINDOW) {
            float[] in = new float[WINDOW * BANDS];
            for (int r = 0; r < WINDOW; r++) System.arraycopy(frames.get(r), 0, in, r * BANDS, BANDS);
            float[] e = emb.run(in, new long[] { 1, WINDOW, BANDS, 1 });
            float[] copy = new float[EMB];
            System.arraycopy(e, 0, copy, 0, EMB);
            features.add(copy);
            while (features.size() > FEATURES) features.remove(0);
        }

        calls++;
        if (features.size() < FEATURES) return 0f;
        float[] in = new float[FEATURES * EMB];
        for (int i = 0; i < FEATURES; i++) System.arraycopy(features.get(i), 0, in, i * EMB, EMB);
        float[] s = word.run(in, new long[] { 1, FEATURES, EMB });
        if (calls <= WARMUP) return 0f;
        return s.length > 0 ? s[0] : 0f;
    }

    private float[][] melFrames(short[] a, int off, int len) throws Exception {
        float[] in = new float[len];
        for (int i = 0; i < len; i++) in[i] = a[off + i];
        float[] out = mel.run(in, new long[] { 1, len });
        int n = out.length / BANDS;
        float[][] f = new float[n][BANDS];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < BANDS; j++) f[i][j] = out[i * BANDS + j] / 10f + 2f;
        }
        return f;
    }
}
