package com.nestlock.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.FileReader;

/** Small persistent UI/run state. */
final class AppState {
    static final int MAX_FAILURES = 3;

    private static final String PREFS = "nestlock";
    private static final String BOOT_PREFS = "nestlock_boot";
    private static final String KEY_STATUS = "last_status";
    private static final String KEY_FAILURES = "consecutive_failures";
    private static final String KEY_PROFILE = "profile_id";
    private static final String KEY_MANUAL = "manual_run";
    private static final String KEY_ATTEMPT_BOOT = "attempt_boot_id";
    private static final String KEY_SUCCESS_BOOT = "success_boot_id";
    private static final String KEY_ATTEMPT_AT = "attempt_elapsed_ms";
    private static final String KEY_SUCCESS_AT = "success_elapsed_ms";
    private static final String KEY_BOOT_ENABLED = "boot_enabled";
    private static final String KEY_ATTEMPT_RUNNING = "attempt_running";
    private static final String KEY_ATTEMPT_RUNNING_AT = "attempt_running_at";
    /* A watchdog wakeup inside this window is treated as the same boot even
     * when boot_id is unreadable. The exploit runs once per boot anyway. */
    private static final long BOOT_GUARD_WINDOW_MS = 30 * 60 * 1000L;

    private AppState() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** BootReceiver can run before credential-encrypted storage exists. */
    private static SharedPreferences bootPrefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE);
    }

    static String getStatus(Context context) {
        return prefs(context).getString(KEY_STATUS, "Not run yet.");
    }

    static int getFailures(Context context) {
        return prefs(context).getInt(KEY_FAILURES, 0);
    }

    static void recordSuccess(Context context) {
        prefs(context).edit().putInt(KEY_FAILURES, 0).apply();
    }

    /**
     * Counts the attempt before any native code runs. A run that reboots the
     * device never reaches recordFailure(), so the count must be persisted up
     * front or the boot-loop guard would never trip.
     */
    static int beginAttempt(Context context) {
        int next = Math.min(MAX_FAILURES, getFailures(context) + 1);
        prefs(context).edit().putInt(KEY_FAILURES, next).apply();
        return next;
    }

    static void recordFailure(Context context) {
        // The attempt was already counted by beginAttempt(). Keep the count so
        // a failed run still contributes to the auto-root pause threshold.
        if (getFailures(context) < 1) {
            prefs(context).edit().putInt(KEY_FAILURES, 1).apply();
        }
    }

    static void resetFailures(Context context) {
        prefs(context).edit().putInt(KEY_FAILURES, 0).apply();
    }

    static String getProfileId(Context context) {
        return prefs(context).getString(KEY_PROFILE, "");
    }

    static void setProfileId(Context context, String id) {
        prefs(context).edit().putString(KEY_PROFILE, id).apply();
    }

    static void setManualRun(Context context, boolean manual) {
        prefs(context).edit().putBoolean(KEY_MANUAL, manual).apply();
    }

    /** Short status only; full output lives in {@link RootLog}. */
    static void setStatus(Context context, String status) {
        String text = status == null ? "" : status.trim();
        int newline = text.indexOf('\n');
        if (newline >= 0) {
            text = text.substring(0, newline).trim();
        }
        if (text.length() > 120) {
            text = text.substring(0, 120);
        }
        prefs(context).edit().putString(KEY_STATUS, text).apply();
    }

    /** Current boot identity, readable without root. Returns "" if unavailable. */
    static String bootId() {
        try (BufferedReader reader = new BufferedReader(
                new FileReader("/proc/sys/kernel/random/boot_id"))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    /** Milliseconds since boot, readable without privileged permissions. */
    static long elapsedMs() {
        return android.os.SystemClock.elapsedRealtime();
    }

    /**
     * True once an automatic attempt has been started for this boot. The
     * vendor blocks third-party boot broadcasts, so a persisted JobScheduler
     * watchdog is the trigger; this keeps repeated wakeups from stacking
     * exploit attempts on the same boot.
     */
    static boolean beginBootAttempt(Context context) {
        SharedPreferences p = prefs(context);
        String boot = bootId();
        long now = elapsedMs();
        if (!boot.isEmpty() && boot.equals(p.getString(KEY_ATTEMPT_BOOT, ""))) {
            return false;
        }
        /* boot_id can be SELinux-unreadable from the app domain. Fall back to
         * elapsedRealtime: a wakeup within the guard window is the same boot,
         * while a real reboot resets elapsedRealtime to near zero. */
        if (boot.isEmpty()) {
            long previous = p.getLong(KEY_ATTEMPT_AT, -1L);
            if (previous >= 0L && now >= previous
                    && now - previous < BOOT_GUARD_WINDOW_MS) {
                return false;
            }
        }
        p.edit()
                .putString(KEY_ATTEMPT_BOOT, boot)
                .putLong(KEY_ATTEMPT_AT, now)
                .commit();
        return true;
    }

    static boolean bootSucceeded(Context context) {
        SharedPreferences p = prefs(context);
        String boot = bootId();
        String saved = p.getString(KEY_SUCCESS_BOOT, "");
        if (!boot.isEmpty() && !saved.isEmpty()) {
            /* A readable boot_id is authoritative. Falling back to elapsed
             * time here would let a success recorded late in the previous
             * boot suppress the next boot's attempt. */
            return boot.equals(saved);
        }
        long successAt = p.getLong(KEY_SUCCESS_AT, -1L);
        long now = elapsedMs();
        return successAt >= 0L && now >= successAt
                && now - successAt < BOOT_GUARD_WINDOW_MS;
    }

    static boolean isBootEnabled(Context context) {
        return bootPrefs(context).getBoolean(KEY_BOOT_ENABLED, true);
    }

    static void setBootEnabled(Context context, boolean enabled) {
        bootPrefs(context).edit().putBoolean(KEY_BOOT_ENABLED, enabled).commit();
    }

    /** Cross-process running flag; expires so a killed process cannot block Root. */
    static void setAttemptRunning(Context context, boolean running) {
        bootPrefs(context).edit()
                .putBoolean(KEY_ATTEMPT_RUNNING, running)
                .putLong(KEY_ATTEMPT_RUNNING_AT, running ? elapsedMs() : 0L)
                .commit();
    }

    static boolean isAttemptActive(Context context) {
        SharedPreferences p = bootPrefs(context);
        if (!p.getBoolean(KEY_ATTEMPT_RUNNING, false)) {
            return false;
        }
        long started = p.getLong(KEY_ATTEMPT_RUNNING_AT, 0L);
        long now = elapsedMs();
        if (started <= 0L || now < started || now - started > 6 * 60 * 1000L) {
            p.edit().putBoolean(KEY_ATTEMPT_RUNNING, false).commit();
            return false;
        }
        return true;
    }

    static void recordBootSuccess(Context context) {
        /* commit(), not apply(): the next watchdog wakeup runs in a fresh
         * process and must see this before it decides to retry. */
        prefs(context).edit()
                .putString(KEY_SUCCESS_BOOT, bootId())
                .putLong(KEY_SUCCESS_AT, elapsedMs())
                .commit();
    }

    static boolean consumeManualRun(Context context) {
        boolean manual = prefs(context).getBoolean(KEY_MANUAL, false);
        if (manual) {
            prefs(context).edit().putBoolean(KEY_MANUAL, false).apply();
        }
        return manual;
    }
}
