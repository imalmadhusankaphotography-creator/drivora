package com.imal.drivora;

import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * Lists the music stored on the device (internal storage, SD card, USB drives that Android has indexed).
 *
 * JS: Capacitor.Plugins.MediaLib.scan() -> { tracks: [{ id, title, artist, duration, uri }] }
 * The web layer plays a track with Capacitor.convertFileSrc(uri).
 */
@CapacitorPlugin(
    name = "MediaLib",
    permissions = {
        @Permission(alias = "audio", strings = { "android.permission.READ_MEDIA_AUDIO" }),
        @Permission(alias = "storage", strings = { "android.permission.READ_EXTERNAL_STORAGE" })
    }
)
public class MediaLibPlugin extends Plugin {

    private String alias() {
        return Build.VERSION.SDK_INT >= 33 ? "audio" : "storage";
    }

    @PluginMethod
    public void scan(PluginCall call) {
        if (getPermissionState(alias()) != PermissionState.GRANTED) {
            requestPermissionForAlias(alias(), call, "onPerm");
            return;
        }
        doScan(call);
    }

    @PermissionCallback
    private void onPerm(PluginCall call) {
        if (getPermissionState(alias()) != PermissionState.GRANTED) {
            call.reject("Permission to read music was not allowed.");
            return;
        }
        doScan(call);
    }

    private void doScan(PluginCall call) {
        JSArray list = new JSArray();
        Cursor c = null;
        try {
            Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] cols = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION
            };
            c =
                getContext()
                    .getContentResolver()
                    .query(base, cols, MediaStore.Audio.Media.IS_MUSIC + " != 0", null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC");
            if (c != null) {
                int iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                while (c.moveToNext()) {
                    long id = c.getLong(iId);
                    String title = c.getString(iTitle);
                    String artist = c.getString(iArtist);
                    JSObject o = new JSObject();
                    o.put("id", String.valueOf(id));
                    o.put("title", title == null ? "" : title);
                    o.put("artist", (artist == null || "<unknown>".equals(artist)) ? "" : artist);
                    o.put("duration", c.getLong(iDur));
                    o.put("uri", ContentUris.withAppendedId(base, id).toString());
                    list.put(o);
                }
            }
            JSObject ret = new JSObject();
            ret.put("tracks", list);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Could not read the music on this device.");
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }
}
