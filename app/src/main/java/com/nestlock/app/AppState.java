package com.nestlock.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class AppState {
    static final int MAX_FAILURES = 3;

    private static final String PREFS = "nestlock";
    private static final String BOOT_PREFS = "nestlock_boot";
    private static final String KEY_STATUS = "last_status";
    private static final String KEY_FAILURES = "consecutive_failures";
    private static final String KEY_PROFILE = "profile_id";
    private static final String KEY_ATTEMPT_BOOT = "attempt_boot_id";
    private static final String KEY_SUCCESS_BOOT = "success_boot_id";
    private static final String KEY_ATTEMPT_AT = "attempt_elapsed_ms";
    private static final String KEY_SUCCESS_AT = "success_elapsed_ms";
    private static final String KEY_BOOT_ENABLED = "boot_enabled";

    private static final String FILE_STATUS = "status.txt";
    private static final String FILE_RUNNING = "running.txt";
    private static final String FILE_MANUAL = "manual.txt";
    private static final String FILE_FAILURES = "failures.txt";
    private static final String FILE_SUCCESS = "success.txt";

    private static final long RUNNING_TTL_MS = 6 * 60 * 1000L;
    private static final long BOOT_GUARD_WINDOW_MS = 30 * 60 * 1000L;

    private AppState() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences bootPrefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE);
    }

    private static File file(Context context, String name) {
        return new File(context.getFilesDir(), name);
    }

    private static void writeFile(Context context, String name, String value) {
        try (FileOutputStream out = new FileOutputStream(file(context, name), false)) {
            out.write((value + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static String readFile(Context context, String name) {
        File f = file(context, name);
        if (!f.isFile()) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void deleteFile(Context context, String name) {
        // noinspection ResultOfMethodCallIgnored
        file(context, name).delete();
    }

    private static long readLong(Context context, String name, long fallback) {
        try {
            return Long.parseLong(readFile(context, name));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    // ---------------------------------------------------------------- status

    static String getStatus(Context context) {
        String status = readFile(context, FILE_STATUS);
        if (status.isEmpty()) {
            status = prefs(context).getString(KEY_STATUS, "Not run yet.");
        }
        if (isStaleStatus(context, status)) {
            return "Not run yet.";
        }
        if (status.startsWith("Running") && !isAttemptActive(context)) {
            return "Not run yet.";
        }
        if (status.startsWith("Starting") && !isAttemptActive(context)
                && !isManualRunPending(context)) {
            return "Not run yet.";
        }
        return status;
    }

    private static boolean isManualRunPending(Context context) {
        return "1".equals(readFile(context, FILE_MANUAL));
    }

    private static boolean isStaleStatus(Context context, String status) {
        if (!status.startsWith("Root ready") && !status.startsWith("Running")) {
            return false;
        }
        String boot = bootId();
        return !boot.isEmpty() && !boot.equals(readFile(context, FILE_SUCCESS));
    }

    static void setStatus(Context context, String status) {
        String text = status == null ? "" : status.trim();
        int newline = text.indexOf('\n');
        if (newline >= 0) {
            text = text.substring(0, newline).trim();
        }
        if (text.length() > 120) {
            text = text.substring(0, 120);
        }
        writeFile(context, FILE_STATUS, text);
    }

    // ------------------------------------------------------------- manual run

    static void setManualRun(Context context, boolean manual) {
        if (manual) {
            writeFile(context, FILE_MANUAL, "1");
        } else {
            deleteFile(context, FILE_MANUAL);
        }
    }

    static boolean consumeManualRun(Context context) {
        if (!"1".equals(readFile(context, FILE_MANUAL))) {
            return false;
        }
        deleteFile(context, FILE_MANUAL);
        return true;
    }

    // --------------------------------------------------------------- failures

    static int getFailures(Context context) {
        long stored = readLong(context, FILE_FAILURES, -1L);
        if (stored >= 0L) {
            return (int) stored;
        }
        return prefs(context).getInt(KEY_FAILURES, 0);
    }

    private static void setFailures(Context context, int value) {
        writeFile(context, FILE_FAILURES, Integer.toString(value));
    }

    static int beginAttempt(Context context) {
        int next = Math.min(MAX_FAILURES, getFailures(context) + 1);
        setFailures(context, next);
        return next;
    }

    static void recordFailure(Context context) {
        if (getFailures(context) < 1) {
            setFailures(context, 1);
        }
    }

    static void resetFailures(Context context) {
        setFailures(context, 0);
    }

    static void recordSuccess(Context context) {
        setFailures(context, 0);
    }

    // ---------------------------------------------------------------- profile

    static String getProfileId(Context context) {
        return prefs(context).getString(KEY_PROFILE, "");
    }

    static void setProfileId(Context context, String id) {
        prefs(context).edit().putString(KEY_PROFILE, id).apply();
    }

    // ------------------------------------------------------------ boot guards

    static String bootId() {
        try (BufferedReader reader = new BufferedReader(
                new FileReader("/proc/sys/kernel/random/boot_id"))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    static long elapsedMs() {
        return android.os.SystemClock.elapsedRealtime();
    }

    static boolean beginBootAttempt(Context context) {
        String boot = bootId();
        long now = elapsedMs();
        SharedPreferences p = prefs(context);
        if (!boot.isEmpty() && boot.equals(p.getString(KEY_ATTEMPT_BOOT, ""))) {
            return false;
        }
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

    static void recordBootSuccess(Context context) {
        String boot = bootId();
        writeFile(context, FILE_SUCCESS, boot);
        prefs(context).edit()
                .putString(KEY_SUCCESS_BOOT, boot)
                .putLong(KEY_SUCCESS_AT, elapsedMs())
                .commit();
    }

    static boolean bootSucceeded(Context context) {
        String boot = bootId();
        String saved = readFile(context, FILE_SUCCESS);
        if (saved.isEmpty()) {
            saved = prefs(context).getString(KEY_SUCCESS_BOOT, "");
        }
        if (!boot.isEmpty() && !saved.isEmpty()) {
            return boot.equals(saved);
        }
        SharedPreferences p = prefs(context);
        long successAt = p.getLong(KEY_SUCCESS_AT, -1L);
        long now = elapsedMs();
        return successAt >= 0L && now >= successAt
                && now - successAt < BOOT_GUARD_WINDOW_MS;
    }

    // ------------------------------------------------------------ boot toggle

    static boolean isBootEnabled(Context context) {
        return bootPrefs(context).getBoolean(KEY_BOOT_ENABLED, false);
    }

    static void setBootEnabled(Context context, boolean enabled) {
        bootPrefs(context).edit().putBoolean(KEY_BOOT_ENABLED, enabled).commit();
    }

    // ------------------------------------------------------------- run flag

    static void setAttemptRunning(Context context, boolean running) {
        if (running) {
            writeFile(context, FILE_RUNNING, Long.toString(elapsedMs()));
        } else {
            deleteFile(context, FILE_RUNNING);
        }
    }

    static boolean isAttemptActive(Context context) {
        long started = readLong(context, FILE_RUNNING, 0L);
        if (started <= 0L) {
            return false;
        }
        long now = elapsedMs();
        if (now < started || now - started > RUNNING_TTL_MS) {
            deleteFile(context, FILE_RUNNING);
            return false;
        }
        return true;
    }
}
