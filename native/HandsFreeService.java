package com.imal.drivora;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the app allowed to use the microphone while another app (Google Maps) is in front,
 * so "Hey Jarvis" keeps working during guidance. Shows a "Listening for Hey Jarvis" notification
 * with a Stop button. The listening itself is done by WakeEngine.
 */
public class HandsFreeService extends Service {

    static final String CHANNEL = "handsfree";
    static final int ID = 7;
    static final String ACTION_STOP = "com.imal.drivora.STOP_HANDS_FREE";

    /** set by VoicePlugin: what to do when the notification's Stop button is pressed */
    static volatile Runnable onStopPressed;
    static volatile boolean running = false;
    static volatile String lastError;

    static void start(Context c) {
        Intent i = new Intent(c, HandsFreeService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void stop(Context c) {
        try {
            c.stopService(new Intent(c, HandsFreeService.class));
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Runnable r = onStopPressed;
            if (r != null) r.run();
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            Notification n = build();
            if (Build.VERSION.SDK_INT >= 30) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(ID, n);
            running = true;
            lastError = null;
        } catch (Exception e) {
            /* Android refused (app not in front when starting). Listening still works while the app is open. */
            lastError = e.getClass().getSimpleName();
            running = false;
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification build() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Hey Jarvis listening", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while JARVIS Drive listens for Hey Jarvis");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        int pf = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT;
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent openPi = open == null ? null : PendingIntent.getActivity(this, 1, open, pf);
        Intent stop = new Intent(this, HandsFreeService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop, pf);
        int icon = getResources().getIdentifier("ic_stat_jarvis", "drawable", getPackageName());
        if (icon == 0) icon = getApplicationInfo().icon;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle("JARVIS Drive")
            .setContentText("Listening for “Hey Jarvis”")
            .setSmallIcon(icon)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(0, "Stop listening", stopPi).build());
        if (openPi != null) b.setContentIntent(openPi);
        return b.build();
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
