package com.nestlock.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** One restore attempt per boot; no battery gate and no Shizuku dependency. */
public class RootJobService extends JobService {
    private static final int JOB_ID = 0x4E4C;
    private static final int WATCHDOG_JOB_ID = 0x4E4D;
    private static final long WATCHDOG_DELAY_MS = 5 * 60 * 1000L;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private ExecutorService executor;
    private volatile Future<?> future;

    static void schedule(Context context, long delayMs) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            return;
        }
        JobInfo.Builder builder = new JobInfo.Builder(
                JOB_ID, new ComponentName(context, RootJobService.class))
                .setMinimumLatency(Math.max(0L, delayMs))
                .setOverrideDeadline(Math.max(1000L, delayMs + 1000L))
                .setPersisted(true);
        scheduler.schedule(builder.build());
        ensureWatchdog(context);
    }

    static void ensureWatchdog(Context context) {
        if (!AppState.isBootEnabled(context)) {
            return;
        }
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            return;
        }
        JobInfo.Builder builder = new JobInfo.Builder(
                WATCHDOG_JOB_ID, new ComponentName(context, RootJobService.class))
                .setMinimumLatency(WATCHDOG_DELAY_MS)
                .setOverrideDeadline(WATCHDOG_DELAY_MS + 5000L)
                .setPersisted(true);
        scheduler.schedule(builder.build());
    }

    static boolean isRunning() {
        return RUNNING.get();
    }

    static boolean isRunning(Context context) {
        return RUNNING.get() || AppState.isAttemptActive(context);
    }

    static void publishRunning(Context context, boolean running) {
        AppState.setAttemptRunning(context, running);
    }

    static void cancel(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            return;
        }
        scheduler.cancel(JOB_ID);
        scheduler.cancel(WATCHDOG_JOB_ID);
    }

    static void disarmIfDisabled(Context context) {
        if (!AppState.isBootEnabled(context)) {
            cancel(context);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final Context app = getApplicationContext();
        if (params.getJobId() == WATCHDOG_JOB_ID) {
            if (!AppState.isBootEnabled(app)) {
                jobFinished(params, false);
                return false;
            }
            ensureWatchdog(app);
            final ManagerProfile profile = ProfileStore.selected(app);
            if (profile == null
                    || AppState.bootSucceeded(app)
                    || AppState.getFailures(app) >= AppState.MAX_FAILURES) {
                jobFinished(params, false);
                return false;
            }
            if (!AppState.beginBootAttempt(app)) {
                jobFinished(params, false);
                return false;
            }
            return startAttempt(app, profile, params);
        }

        final ManagerProfile profile = ProfileStore.selected(app);
        final boolean manual = AppState.consumeManualRun(app);
        final int failures = AppState.getFailures(app);

        if (profile == null) {
            finish(app, params, "NestLock", "No profile is available.");
            return false;
        }
        if (AppState.bootSucceeded(app)) {
            AppState.setStatus(app, "Root already restored on this boot.");
            RUNNING.set(false);
            jobFinished(params, false);
            return false;
        }
        if (!manual) {
            if (!AppState.beginBootAttempt(app)) {
                jobFinished(params, false);
                return false;
            }
        }
        if (!manual && failures >= AppState.MAX_FAILURES) {
            finish(app, params,
                    "NestLock paused",
                    "Auto-root stopped after " + AppState.MAX_FAILURES
                            + " failed boots. Open NestLock and tap Run now.");
            return false;
        }
        if (manual) {
            AppState.resetFailures(app);
        }
        return startAttempt(app, profile, params);
    }

    private boolean startAttempt(Context app, ManagerProfile profile, JobParameters params) {
        if (!RUNNING.compareAndSet(false, true)) {
            jobFinished(params, false);
            return false;
        }
        publishRunning(app, true);
        AppState.beginAttempt(app);

        AppState.setStatus(app, "Running profile " + profile.id + "...");
        Notifications.show(app, "NestLock", "Restoring per-boot root...",
                Notifications.statusIntent(app));
        RootLog.append(app, "starting " + profile.id);

        executor = Executors.newSingleThreadExecutor();
        future = executor.submit(() -> {
            try {
                RootRunner.Result result = RootRunner.run(app, profile, line -> {
                    String text = stripAnsi(line);
                    if (text.isEmpty()) {
                        return;
                    }
                    RootLog.append(app, text);
                    if (text.startsWith("[+]") || text.startsWith("[*] stage")
                            || text.startsWith("[*] handoff")) {
                        AppState.setStatus(app, text);
                    }
                });
                AppState.setStatus(app, result.success
                        ? "Root ready"
                        : result.message);
                if (result.success) {
                    AppState.recordSuccess(app);
                    AppState.recordBootSuccess(app);
                } else {
                    AppState.recordFailure(app);
                    /* The module may be loaded even when the post-checks fail;
                     * record it so the guard blocks a second run this boot. */
                    if (result.status.contains("module loaded")) {
                        AppState.recordBootSuccess(app);
                    }
                }
                RootLog.append(app, result.title + ": " + result.message);
                Notifications.show(app, result.title, result.message,
                        result.success
                                ? Notifications.managerIntent(app, profile)
                                : Notifications.statusIntent(app));
            } catch (Throwable error) {
                AppState.recordFailure(app);
                AppState.setStatus(app, "Internal error");
                RootLog.append(app, "internal error: " + error);
                Notifications.show(app, "Root restore failed",
                        "Internal error: " + error, Notifications.statusIntent(app));
            } finally {
                publishRunning(app, false);
                RUNNING.set(false);
                jobFinished(params, false);
                ExecutorService service = executor;
                if (service != null) {
                    service.shutdown();
                }
            }
        });
        return true;
    }

    private void finish(Context app, JobParameters params, String title, String message) {
        AppState.setStatus(app, message);
        RootLog.append(app, title + ": " + message);
        Notifications.show(app, title, message, Notifications.statusIntent(app));
        publishRunning(app, false);
        RUNNING.set(false);
        jobFinished(params, false);
    }

    /** Strips the colour escapes the native log uses. */
    private static String stripAnsi(String line) {
        if (line == null) {
            return "";
        }
        return line.replaceAll("\u001B\\[[;\\d]*m", "").trim();
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Future<?> running = future;
        if (running != null) {
            running.cancel(true);
        }
        return false;
    }
}
