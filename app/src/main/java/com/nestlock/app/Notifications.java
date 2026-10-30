package com.nestlock.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/** Minimal progress/result notifications. */
final class Notifications {
    static final int ID = 0x4E4C;
    private static final String CHANNEL = "root";

    private Notifications() {
    }

    static void ensureChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Root restore", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Per-boot root restore status");
        manager.createNotificationChannel(channel);
    }

    static void show(Context context, String title, String text, PendingIntent intent) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        ensureChannel(context);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true);
        if (intent != null) {
            builder.setContentIntent(intent);
        }
        manager.notify(ID, builder.build());
    }

    static PendingIntent activityIntent(Context context, Intent intent) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        /* Request code must differ per target, otherwise FLAG_UPDATE_CURRENT
         * makes the most recent intent overwrite an older pending one. */
        int requestCode = 31 * ID + (intent.getComponent() == null
                ? 0
                : intent.getComponent().flattenToString().hashCode());
        return PendingIntent.getActivity(context, requestCode, intent, flags);
    }

    static PendingIntent statusIntent(Context context) {
        Intent intent = new Intent(context, StatusActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return activityIntent(context, intent);
    }

    static PendingIntent managerIntent(Context context, ManagerProfile profile) {
        if (profile == null || !profile.hasManager()) {
            return statusIntent(context);
        }
        Intent intent;
        if (!profile.managerActivity.isEmpty()) {
            intent = new Intent();
            intent.setClassName(profile.managerPackage, profile.managerActivity);
        } else {
            intent = context.getPackageManager().getLaunchIntentForPackage(profile.managerPackage);
        }
        if (intent == null) {
            return statusIntent(context);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return activityIntent(context, intent);
    }
}
