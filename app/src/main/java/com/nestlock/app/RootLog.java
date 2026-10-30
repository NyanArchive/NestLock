package com.nestlock.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/** Bounded on-disk root log; the short status stays in SharedPreferences. */
final class RootLog {
    private static final String FILE = "root.log";
    private static final String OLD = "root.log.1";
    private static final long MAX_BYTES = 256 * 1024L;
    private static final int TAIL_LINES = 400;
    private static final Object LOCK = new Object();

    private RootLog() {
    }

    static void append(Context context, String line) {
        if (line == null) {
            return;
        }
        String text = line.trim();
        if (text.isEmpty()) {
            return;
        }
        String stamped = new SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(new Date()) + "  " + text + "\n";
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), FILE);
            try {
                if (file.length() > MAX_BYTES) {
                    File old = new File(context.getFilesDir(), OLD);
                    if (old.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        old.delete();
                    }
                    //noinspection ResultOfMethodCallIgnored
                    file.renameTo(old);
                }
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write(stamped.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
                // Logging must never break an attempt.
            }
        }
    }

    static void clear(Context context) {
        synchronized (LOCK) {
            //noinspection ResultOfMethodCallIgnored
            new File(context.getFilesDir(), FILE).delete();
            //noinspection ResultOfMethodCallIgnored
            new File(context.getFilesDir(), OLD).delete();
        }
    }

    static String tail(Context context) {
        Deque<String> lines = new ArrayDeque<>();
        synchronized (LOCK) {
            readTail(new File(context.getFilesDir(), OLD), lines);
            readTail(new File(context.getFilesDir(), FILE), lines);
        }
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static void readTail(File file, Deque<String> lines) {
        if (!file.isFile()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.addLast(line);
                while (lines.size() > TAIL_LINES) {
                    lines.removeFirst();
                }
            }
        } catch (Exception ignored) {
        }
    }
}
