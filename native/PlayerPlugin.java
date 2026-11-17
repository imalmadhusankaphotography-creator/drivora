package com.imal.drivora;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Music playback outside the WebView.
 *
 * Why: the WebView pauses its audio every time another app takes audio focus. The speech recognizer
 * takes audio focus each time hands-free listening restarts, so music played by the page kept stopping.
 * This player never asks for audio focus, so listening, spoken replies and the wake word cannot pause it.
 * It pauses on its own for phone calls (ringing or in a call) and turns itself down during app calls.
 *
 * JS: Capacitor.Plugins.Player
 *   play({ uri } | { key })    -> { dur }   uri = content:// song on the device, key = a song written with write()
 *   pause() / resume() / stop()
 *   seek({ ms })
 *   volume({ v })              0..1
 *   meta({ title, artist })    shown on the car screen and Bluetooth
 *   has({ key }) -> { ok }     write({ key, data(base64), append }) / drop({ key }) / clear()
 *   events: "tick" { pos, dur, playing, call }   "ended"   "error" { message }
 *           "button" { action: play | pause | next | prev }   (steering-wheel / headset buttons)
 */
@CapacitorPlugin(name = "Player")
public class PlayerPlugin extends Plugin {

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer mp;
    private PluginCall playCall;
    private boolean prepared = false;
    private boolean want = false;
    private boolean callPaused = false;
    private boolean commDucked = false;
    private float vol = 1f;
    private int token = 0;
    private MediaSession session;
    private String title = "JARVIS Drive";
    private String artist = "";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            doTick();
            if (mp != null) main.postDelayed(this, 500);
        }
    };

    /* ---------- playback ---------- */

    @PluginMethod
    public void play(final PluginCall call) {
        final String uri = call.getString("uri", "");
        final String key = call.getString("key", "");
        getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        release();
                        final int my = ++token;
                        want = true;
                        try {
                            MediaPlayer p = new MediaPlayer();
                            mp = p;
                            p.setAudioAttributes(
                                new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build()
                            );
                            if (uri != null && uri.length() > 0) {
                                p.setDataSource(getContext(), Uri.parse(uri));
                            } else if (key != null && key.length() > 0) {
                                File f = file(key);
                                if (!f.exists()) {
                                    release();
                                    call.reject("That song is not ready.");
                                    return;
                                }
                                FileInputStream in = new FileInputStream(f);
                                try {
                                    p.setDataSource(in.getFD());
                                } finally {
                                    in.close();
                                }
                            } else {
                                release();
                                call.reject("Nothing to play.");
                                return;
                            }
                            p.setOnPreparedListener(
                                new MediaPlayer.OnPreparedListener() {
                                    @Override
                                    public void onPrepared(MediaPlayer m) {
                                        if (my != token || m != mp) return;
                                        prepared = true;
                                        callPaused = false;
                                        applyVolume();
                                        try {
                                            /* not if the driver pressed pause while the song was loading */
                                            if (want && !inCall()) m.start();
                                        } catch (Exception ignored) {}
                                        session();
                                        if (playCall != null) {
                                            JSObject o = new JSObject();
                                            o.put("dur", safeDur());
                                            playCall.resolve(o);
                                            playCall = null;
                                        }
                                        main.removeCallbacks(tick);
                                        main.post(tick);
                                    }
                                }
                            );
                            p.setOnCompletionListener(
                                new MediaPlayer.OnCompletionListener() {
                                    @Override
                                    public void onCompletion(MediaPlayer m) {
                                        if (my != token || m != mp) return;
                                        want = false;
                                        state();
                                        notifyListeners("ended", new JSObject());
                                    }
                                }
                            );
                            p.setOnErrorListener(
                                new MediaPlayer.OnErrorListener() {
                                    @Override
                                    public boolean onError(MediaPlayer m, int what, int extra) {
                                        if (my != token) return true;
                                        PluginCall c = playCall;
                                        playCall = null;
                                        release();
                                        if (c != null) {
                                            c.reject("This song could not be played.");
                                        } else {
                                            JSObject o = new JSObject();
                                            o.put("message", "This song could not be played.");
                                            notifyListeners("error", o);
                                        }
                                        return true;
                                    }
                                }
                            );
                            playCall = call;
                            p.prepareAsync();
                        } catch (Exception e) {
                            playCall = null;
                            release();
                            call.reject("This song could not be played.");
                        }
                    }
                }
            );
    }

    @PluginMethod
    public void pause(final PluginCall call) {
        getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        want = false;
                        callPaused = false;
                        try {
                            if (mp != null && prepared && mp.isPlaying()) mp.pause();
                        } catch (Exception ignored) {}
                        state();
                        doTick();
                        call.resolve();
                    }
                }
            );
    }

    @PluginMethod
    public void resume(final PluginCall call) {
        getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        if (mp == null) {
                            call.reject("Nothing is loaded.");
                            return;
                        }
                        want = true;
                        if (prepared && !inCall()) {
                            try {
                                mp.start();
                            } catch (Exception ignored) {}
                        }
                        state();
                        main.removeCallbacks(tick);
                        main.post(tick);
                        call.resolve();
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
                        token++;
                        PluginCall c = playCall;
                        playCall = null;
                        release();
                        if (c != null) c.reject("Stopped.");
                        call.resolve();
                    }
                }
            );
    }

    @PluginMethod
    public void seek(final PluginCall call) {
        final Integer ms = call.getInt("ms", 0);
        getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (mp != null && prepared) mp.seekTo(ms == null ? 0 : Math.max(0, ms));
                        } catch (Exception ignored) {}
                        doTick();
                        call.resolve();
                    }
                }
            );
    }

    @PluginMethod
    public void volume(final PluginCall call) {
        Double v = call.getDouble("v", 1.0);
        vol = (float) Math.max(0.0, Math.min(1.0, v == null ? 1.0 : v));
        getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        applyVolume();
                        call.resolve();
                    }
                }
            );
    }

    @PluginMethod
    public void meta(final PluginCall call) {
        title = call.getString("title", "JARVIS Drive");
        artist = call.getString("artist", "");
        getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        if (session != null) {
                            try {
                                session.setMetadata(
                                    new MediaMetadata.Builder()
                                        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                                        .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                                        .putString(MediaMetadata.METADATA_KEY_ALBUM, "JARVIS Drive")
                                        .putLong(MediaMetadata.METADATA_KEY_DURATION, safeDur())
                                        .build()
                                );
                            } catch (Exception ignored) {}
                        }
                        call.resolve();
                    }
                }
            );
    }

    /* main thread only */
    private void release() {
        main.removeCallbacks(tick);
        prepared = false;
        want = false;
        callPaused = false;
        commDucked = false;
        if (mp != null) {
            try {
                mp.reset();
            } catch (Exception ignored) {}
            try {
                mp.release();
            } catch (Exception ignored) {}
            mp = null;
        }
        state();
    }

    private void applyVolume() {
        if (mp == null) return;
        float v = commDucked ? Math.min(vol, 0.1f) : vol;
        try {
            mp.setVolume(v, v);
        } catch (Exception ignored) {}
    }

    private long safeDur() {
        try {
            if (mp != null && prepared) return Math.max(0, mp.getDuration());
        } catch (Exception ignored) {}
        return 0;
    }

    private int mode() {
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            return am == null ? AudioManager.MODE_NORMAL : am.getMode();
        } catch (Exception e) {
            return AudioManager.MODE_NORMAL;
        }
    }

    /* a phone call is ringing or going on */
    private boolean inCall() {
        int m = mode();
        return m == AudioManager.MODE_RINGTONE || m == AudioManager.MODE_IN_CALL;
    }

    /* main thread only: progress for the page, and pause / resume around phone calls */
    private void doTick() {
        if (mp == null || !prepared) return;
        int m = mode();
        boolean call = m == AudioManager.MODE_RINGTONE || m == AudioManager.MODE_IN_CALL;
        boolean comm = m == AudioManager.MODE_IN_COMMUNICATION;
        try {
            if (call && mp.isPlaying()) {
                mp.pause();
                callPaused = true;
                state();
            } else if (!call && callPaused && want) {
                callPaused = false;
                mp.start();
                state();
            }
        } catch (Exception ignored) {}
        if (comm != commDucked) {
            commDucked = comm;
            applyVolume();
        }
        JSObject o = new JSObject();
        boolean playing = false;
        long pos = 0;
        try {
            playing = mp.isPlaying();
            pos = mp.getCurrentPosition();
        } catch (Exception ignored) {}
        o.put("pos", pos);
        o.put("dur", safeDur());
        o.put("playing", playing);
        o.put("want", want);
        o.put("call", call);
        notifyListeners("tick", o);
    }

    /* ---------- steering-wheel / headset buttons and the car screen ---------- */

    private void session() {
        try {
            if (session == null) {
                session = new MediaSession(getContext(), "JarvisDrive");
                session.setCallback(
                    new MediaSession.Callback() {
                        @Override
                        public void onPlay() {
                            button("play");
                        }

                        @Override
                        public void onPause() {
                            button("pause");
                        }

                        @Override
                        public void onStop() {
                            button("pause");
                        }

                        @Override
                        public void onSkipToNext() {
                            button("next");
                        }

                        @Override
                        public void onSkipToPrevious() {
                            button("prev");
                        }
                    }
                );
            }
            session.setActive(true);
            state();
        } catch (Exception ignored) {}
    }

    private void button(String action) {
        JSObject o = new JSObject();
        o.put("action", action);
        notifyListeners("button", o);
    }

    private void state() {
        if (session == null) return;
        try {
            boolean playing = mp != null && prepared && mp.isPlaying();
            long pos = 0;
            if (mp != null && prepared) pos = mp.getCurrentPosition();
            session.setPlaybackState(
                new PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY |
                        PlaybackState.ACTION_PAUSE |
                        PlaybackState.ACTION_PLAY_PAUSE |
                        PlaybackState.ACTION_STOP |
                        PlaybackState.ACTION_SKIP_TO_NEXT |
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    )
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? 1f : 0f)
                    .build()
            );
        } catch (Exception ignored) {}
    }

    /* ---------- songs added with "Add files": the page writes them here once so this player can open them ---------- */

    private File dir() {
        File d = new File(getContext().getCacheDir(), "music");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private File file(String key) {
        return new File(dir(), key.replaceAll("[^A-Za-z0-9_.-]", "_"));
    }

    @PluginMethod
    public void has(PluginCall call) {
        String key = call.getString("key", "");
        JSObject o = new JSObject();
        File f = (key == null || key.length() == 0) ? null : file(key);
        o.put("ok", f != null && f.exists() && f.length() > 0 && !new File(f.getPath() + ".part").exists());
        call.resolve(o);
    }

    @PluginMethod
    public void write(PluginCall call) {
        String key = call.getString("key", "");
        String data = call.getString("data", "");
        Boolean append = call.getBoolean("append", false);
        Boolean last = call.getBoolean("last", false);
        if (key == null || key.length() == 0) {
            call.reject("No key.");
            return;
        }
        File f = file(key);
        File part = new File(f.getPath() + ".part");
        FileOutputStream out = null;
        try {
            byte[] bytes = Base64.decode(data == null ? "" : data, Base64.DEFAULT);
            boolean add = Boolean.TRUE.equals(append);
            if (!add) part.createNewFile();
            out = new FileOutputStream(f, add);
            out.write(bytes);
            out.close();
            out = null;
            if (Boolean.TRUE.equals(last)) part.delete();
            call.resolve();
        } catch (Exception e) {
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {}
            f.delete();
            part.delete();
            call.reject("Could not prepare the song. Storage may be full.");
        }
    }

    @PluginMethod
    public void drop(PluginCall call) {
        String key = call.getString("key", "");
        if (key != null && key.length() > 0) {
            file(key).delete();
            new File(file(key).getPath() + ".part").delete();
        }
        call.resolve();
    }

    @PluginMethod
    public void clear(PluginCall call) {
        File[] l = dir().listFiles();
        if (l != null) for (File f : l) f.delete();
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        token++;
        playCall = null;
        release();
        try {
            if (session != null) session.release();
        } catch (Exception ignored) {}
        session = null;
    }
}
