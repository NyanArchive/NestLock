package com.nestlock.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs the app-context GhostLock exploit and its profile loader. */
final class RootRunner {
    private static final String STATUS_MARKER = "\u001eGLK_STATUS";
    private static final String STATUS_ACK = "\u001eGLK_STATUS_ACK\n";
    private static final long RUN_TIMEOUT_MS = 300_000L;

    interface Listener {
        void onLog(String line);
    }

    static final class Result {
        final boolean success;
        final String title;
        final String message;
        final String status;

        Result(boolean success, String title, String message, String status) {
            this.success = success;
            this.title = title;
            this.message = message;
            this.status = status;
        }
    }

    private RootRunner() {
    }

    static Result run(Context context, ManagerProfile profile, Listener listener) {
        if (AppState.bootSucceeded(context)) {
            if (listener != null) {
                listener.onLog("root already restored on this boot; exploit skipped");
            }
            return new Result(true, "Root already present",
                    "Root was already restored on this boot. Reboot for a fresh run.",
                    "root already present\nmodule loaded\nsu=uid=0\nenforce=Enforcing\ndone\n");
        }
        Process process = null;
        DataOutputStream stdin = null;
        Thread reader = null;
        File statusFile = null;
        try {
            AssetInstaller.Installed installed = AssetInstaller.install(context, profile);
            statusFile = installed.status;
            try (OutputStream statusOut = new FileOutputStream(statusFile, false)) {
                statusOut.write(new byte[0]);
            }
            android.system.Os.chmod(statusFile.getAbsolutePath(), 0666);
            try (OutputStream nativeOut = new FileOutputStream(installed.nativeLog, false)) {
                nativeOut.write(new byte[0]);
            }

            ApplicationInfo appInfo = context.getApplicationInfo();
            File nativeBinary = new File(appInfo.nativeLibraryDir, profile.nativeLib);
            if (!nativeBinary.isFile()) {
                return new Result(false, "NestLock", "missing native binary: " + nativeBinary,
                        "missing native binary");
            }

            byte[] profileBlob = AssetInstaller.readAsset(context, profile.profileAsset);
            List<String> argv = new ArrayList<>(Arrays.asList(
                    nativeBinary.getAbsolutePath(),
                    "--ghostlock-app-call",
                    "--enable-status-record"));

            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.directory(installed.base);
            builder.redirectErrorStream(true);
            Map<String, String> env = builder.environment();
            env.put("GHOSTLOCK_HOME", installed.base.getAbsolutePath());
            env.put("TMPDIR", installed.base.getAbsolutePath());
            env.put("HOME", installed.base.getAbsolutePath());
            env.put("GHOSTLOCK_KSU_LOG", installed.ksuLog.getAbsolutePath());
            env.put("GHOSTLOCK_KSUD", installed.loadScript.getAbsolutePath());
            if (installed.binDir != null) {
                env.put("PATH", installed.binDir.getAbsolutePath() + ":/system/bin:/system/xbin");
            }

            process = builder.start();
            stdin = new DataOutputStream(process.getOutputStream());
            stdin.writeInt(profileBlob.length);
            stdin.write(profileBlob);
            stdin.flush();

            final DataOutputStream ack = stdin;
            final Process running = process;
            reader = new Thread(() -> readOutput(running, ack, installed.nativeLog, listener),
                    "nestlock-native-output");
            reader.setDaemon(true);
            reader.start();

            final File nativeLog = installed.nativeLog;
            boolean statusDone = false;
            boolean statusFailed = false;
            long processExitedAt = 0L;
            long deadline = System.currentTimeMillis() + RUN_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                String text = AssetInstaller.readText(statusFile);
                statusDone = hasLine(text, "done");
                statusFailed = hasFailure(text);
                String nativeText = AssetInstaller.readText(nativeLog);
                if (nativeReady(nativeText) || nativeFailed(nativeText)) {
                    break;
                }
                if (statusDone || statusFailed) {
                    break;
                }
                if (!process.isAlive()) {
                    if (processExitedAt == 0L) {
                        processExitedAt = System.currentTimeMillis();
                    }
                    long exitedFor = System.currentTimeMillis() - processExitedAt;
                    if (text.trim().isEmpty() && exitedFor > 60_000L) {
                        break;
                    }
                    if (!text.trim().isEmpty() && exitedFor > 120_000L) {
                        break;
                    }
                }
                Thread.sleep(500);
            }

            String status = AssetInstaller.readText(statusFile);
            String nativeText = AssetInstaller.readText(nativeLog);
            boolean nativeOk = nativeReady(nativeText);
            statusDone = hasLine(status, "done");
            statusFailed = hasFailure(status);
            if (nativeOk) {
                /* Verified by the native handoff probe from its own log. */
                statusDone = true;
                statusFailed = false;
            }
            boolean moduleLoaded = nativeOk || moduleLoaded(profile.moduleName)
                    || status.contains("module loaded")
                    || status.contains("module already loaded");

            boolean suOk = nativeOk || lineStartsWith(status, "su=uid=0");
            boolean enforcing = nativeOk
                    ? !nativePermissive(nativeText)
                    : enforcementOk(status);
            boolean registered = nativeOk || registrationOk(status)
                    || !profile.hasManager()
                    || profile.builtinManager;

            boolean success = moduleLoaded && statusDone && !statusFailed && suOk
                    && enforcing && registered;
            String message;
            if (success) {
                message = "Root restored; SELinux Enforcing; manager registered.";
            } else if (moduleLoaded) {
                message = "Module loaded, but verification failed (su=" + suOk
                        + ", enforcing=" + enforcing + ", registered=" + registered + ").";
            } else if (statusFailed) {
                message = "Loader failed. Open NestLock for the log.";
            } else if (statusDone) {
                message = "Loader finished, but the module is not loaded.";
            } else {
                message = "Timed out or the native exploit exited before root.";
            }

            if (listener != null) {
                for (String line : status.split("\n")) {
                    if (isStatusNoise(line)) {
                        continue;
                    }
                    listener.onLog(line);
                }
            }
            return new Result(success, success ? "Root ready" : "Root restore failed",
                    message, status);
        } catch (Exception e) {
            String status = statusFile == null ? "" : AssetInstaller.readText(statusFile);
            return new Result(false, "Root restore failed",
                    e.getClass().getSimpleName() + ": " + e.getMessage(), status);
        } finally {
            if (stdin != null) {
                try {
                    stdin.close();
                } catch (Exception ignored) {
                }
            }
            if (process != null) {
                try {
                    process.destroy();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (Exception ignored) {
                    process.destroyForcibly();
                }
            }
            if (reader != null) {
                try {
                    reader.join(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static void readOutput(Process process, OutputStream stdin, File log, Listener listener) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(STATUS_MARKER)) {
                    if (!line.contains("GLK_STATUS_DISABLED")) {
                        try {
                            stdin.write(STATUS_ACK.getBytes(StandardCharsets.UTF_8));
                            stdin.flush();
                        } catch (Exception ignored) {
                        }
                    }
                }
                appendLog(log, line);
                if (listener != null) {
                    listener.onLog(line);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean isStatusNoise(String line) {
        String text = line.trim();
        if (text.isEmpty()) {
            return true;
        }
        return text.startsWith("NormalPerm(")
                || text.startsWith("Type(")
                || text.startsWith("TypeAttr(")
                || text.startsWith("TypeAttribute(")
                || text.startsWith("Perm(")
                || text.startsWith("Class(")
                || text.startsWith("Common(")
                || text.startsWith("Sensitivity(")
                || text.startsWith("Category(")
                || text.startsWith("Role(")
                || text.startsWith("Bool(");
    }

    private static void appendLog(File file, String line) {        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    static boolean moduleLoaded(String moduleName) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream("/proc/modules"), StandardCharsets.UTF_8))) {
            String line;
            String prefix = moduleName + " ";
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(prefix)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // /proc/modules is not readable in every app domain; sysfs is a
            // reliable fallback because insmod creates /sys/module/<name>.
        }
        return new File("/sys/module/" + moduleName).isDirectory();
    }

    private static boolean enforcementOk(String status) {
        String line = findLine(status, "enforce=");
        if (line == null) {
            return true;
        }
        if (line.contains("unknown")) {
            return true;
        }
        return line.contains("Enforcing");
    }

    private static boolean registrationOk(String status) {
        if (status.contains("manager not installed:")) {
            return false;
        }
        String line = findLine(status, "register rc=");
        if (line != null) {
            return line.endsWith("rc=0");
        }
        return status.contains("register skipped") || !status.contains("register ");
    }

    private static boolean hasLine(String text, String expected) {
        for (String line : text.split("\n")) {
            if (line.trim().equals(expected)) {
                return true;
            }
        }
        return false;
    }

    /** Native parent log markers: handoff probe saw the module load. */
    private static boolean nativeReady(String text) {
        return text.contains("KernelSU ready");
    }

    private static boolean nativePermissive(String text) {
        return text.contains("enforce=0 (still permissive)");
    }

    /** Native parent log markers: the handoff probe saw a definitive failure. */
    private static boolean nativeFailed(String text) {
        return text.contains("KernelSU module load failed")
                || text.contains("KernelSU module not loaded");
    }

    private static boolean hasFailure(String text) {
        for (String line : text.split("\n")) {
            String value = line.trim();
            if (value.equals("failed") || value.startsWith("failed: ")) {
                return true;
            }
        }
        return false;
    }

    private static boolean lineStartsWith(String text, String prefix) {
        for (String line : text.split("\n")) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String findLine(String text, String prefix) {
        for (String line : text.split("\n")) {
            if (line.startsWith(prefix)) {
                return line;
            }
        }
        return null;
    }
}
