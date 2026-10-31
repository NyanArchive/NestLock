package com.nestlock.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Schedules the restore job after boot or after an app update. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            if (!AppState.isBootEnabled(context)) {
                return;
            }
            try {
                AppState.setManualRun(context, false);
            } catch (RuntimeException ignored) {
            }
            RootJobService.schedule(context, 5000L);
        }
    }
}
