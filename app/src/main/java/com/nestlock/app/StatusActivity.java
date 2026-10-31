package com.nestlock.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Title, root action, boot toggle, and the root log. */
public class StatusActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 1;
    private static final long LOG_POLL_MS = 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView logView;
    private ScrollView logScroll;
    private boolean logAtBottom = true;
    private boolean programmaticScroll;
    private TextView statusView;
    private Button rootButton;
    private Switch bootSwitch;
    private boolean syncingSwitch;

    private final Runnable logPoll = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, LOG_POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Notifications.ensureChannel(this);
        setContentView(buildUi());
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        handler.removeCallbacks(logPoll);
        handler.postDelayed(logPoll, LOG_POLL_MS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(logPoll);
        super.onPause();
    }

    private ViewGroup buildUi() {
        int pad = dp(20);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("NestLock");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title);

        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setPadding(0, dp(4), 0, dp(16));
        content.addView(statusView);

        rootButton = new Button(this);
        rootButton.setText("Root");
        rootButton.setOnClickListener(v -> startRoot());
        content.addView(rootButton);

        bootSwitch = new Switch(this);
        bootSwitch.setText("Start on boot");
        bootSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (syncingSwitch) {
                return;
            }
            AppState.setBootEnabled(this, checked);
            if (checked) {
                RootJobService.ensureWatchdog(this);
                requestNotificationsIfNeeded();
            } else {
                RootJobService.cancel(this);
            }
            refresh();
        });
        content.addView(bootSwitch);

        TextView logLabel = new TextView(this);
        logLabel.setText("Root log");
        logLabel.setTextSize(14);
        logLabel.setTypeface(Typeface.DEFAULT_BOLD);
        logLabel.setPadding(0, dp(16), 0, dp(4));
        logLabel.setOnLongClickListener(v -> {
            RootLog.clear(this);
            refresh();
            toast("Root log cleared");
            return true;
        });
        content.addView(logLabel);

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        logView.setBackgroundColor(0x11000000);

        logScroll = new ScrollView(this);
        logScroll.addView(logView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        bindLogScroll();
        return content;
    }

    private void startRoot() {
        if (RootJobService.isRunning(this)) {
            toast("A restore is already running");
            return;
        }
        AppState.setManualRun(this, true);
        AppState.resetFailures(this);
        RootJobService.schedule(this, 3000L);
        RootLog.append(this, "queued manual root attempt");
        AppState.setStatus(this, "Queued");
        refresh();
        if (AppState.bootSucceeded(this)) {
            RootLog.append(this, "root already restored on this boot; a new run is only needed after a reboot");
        }
    }

    private void refresh() {
        statusView.setText(AppState.getStatus(this));
        boolean running = RootJobService.isRunning(this);
        boolean loaded = AppState.bootSucceeded(this);
        rootButton.setEnabled(!running && !loaded);
        rootButton.setText(loaded ? "Rooted" : "Root");
        syncingSwitch = true;
        bootSwitch.setChecked(AppState.isBootEnabled(this));
        syncingSwitch = false;
        String log = RootLog.tail(this);
        String shown = logView.getText().toString();
        if (!log.equals(shown)) {
            if (log.startsWith(shown)) {
                logView.append(log.substring(shown.length()));
            } else {
                int keep = logScroll.getScrollY();
                boolean follow = logAtBottom;
                logView.setText(log);
                if (!follow) {
                    programmaticScroll = true;
                    logScroll.post(() -> {
                        logScroll.scrollTo(0, keep);
                        logScroll.post(() -> programmaticScroll = false);
                    });
                }
            }
            if (logAtBottom) {
                programmaticScroll = true;
                logScroll.post(() -> {
                    logScroll.fullScroll(android.view.View.FOCUS_DOWN);
                    logScroll.post(() -> programmaticScroll = false);
                });
            }
        }
    }

    private void bindLogScroll() {
        logScroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            if (programmaticScroll) {
                return;
            }
            logAtBottom = isLogAtBottom();
        });
        logAtBottom = true;
    }

    private boolean isLogAtBottom() {
        if (logScroll == null || logView.getHeight() <= logScroll.getHeight()) {
            return true;
        }
        int max = logView.getHeight() - logScroll.getHeight() + logScroll.getPaddingBottom();
        return logScroll.getScrollY() >= max - dp(24);
    }

    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_NOTIFICATIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            toast("Notifications enabled");
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
