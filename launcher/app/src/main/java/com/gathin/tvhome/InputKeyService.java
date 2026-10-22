package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.util.Collections;
import java.util.Map;

/**
 * Maps the remote's Input button to the system input switcher, from any app. Remotes such as the Chromecast voice
 * remote send KEYCODE_TV for it, others (KTC and Kalley Realtek Google TV remotes) KEYCODE_TV_INPUT; on Google TV
 * the stock launcher handles both, so with the stock launcher disabled the button does nothing. Buttons listed in
 * input_key_keycodes (config.xml or a theme) open the input switcher the same way, and app_keys start an app (a
 * YouTube button that only the stock launcher answered). Buttons in all_apps_keycodes open Home's All apps screen
 * (the remote's Apps button). Every other key passes through untouched.
 * Opt-in: INPUT_KEY_SERVICE=1 build-and-install.sh (or -InputKeyService on Windows) enables it.
 */
public class InputKeyService extends AccessibilityService {

    private static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";
    private static final String TAG = "TvHome.Keys";

    private int[] inputKeycodes = new int[0];
    private int[] allAppsKeycodes = new int[0];
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();
    private Map<Integer, String> holdAppKeys = Collections.emptyMap();
    private Map<Integer, String> holdAppScancodes = Collections.emptyMap();
    /** input_key_tv_keys: false on TVs (Sony) whose own TV app already answers KEYCODE_TV and KEYCODE_TV_INPUT. */
    private boolean tvKeys = true;
    private boolean logKeys;

    /** The bound service, for Home's "Restart or power off" row; null while accessibility has it unbound. */
    private static volatile InputKeyService running;

    /**
     * Opens the system's power dialog (Restart / Power off), which the remote's long press on Power showed
     * through the stock launcher. False when this service is not bound, so the caller can fall back.
     */
    static boolean showPowerDialog() {
        InputKeyService service = running;
        return service != null && service.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
    }

    /** Until when (uptime ms) the next power dialog should have its Restart button pressed for the owner. */
    private static volatile long restartUntil;

    /**
     * Restarts the TV: only the system may, so this opens Android's power dialog and presses its Restart button
     * as soon as the dialog's window appears (see onAccessibilityEvent). False when the service is not bound.
     */
    static boolean restart() {
        restartUntil = android.os.SystemClock.uptimeMillis() + 4000;
        if (showPowerDialog()) return true;
        restartUntil = 0;
        return false;
    }

    /** Presses the dialog's Restart button; true once it was found and clicked. */
    private boolean pressRestart() {
        android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
        // Never Home's own menu, which has a Restart row of its own: only the system dialog.
        if (root == null || getPackageName().contentEquals(String.valueOf(root.getPackageName()))) return false;
        for (String label : new String[]{"Restart", "Reboot"}) {
            for (android.view.accessibility.AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label)) {
                // The label is a TextView inside the clickable button: click the nearest clickable ancestor.
                for (android.view.accessibility.AccessibilityNodeInfo n = node; n != null; n = n.getParent()) {
                    if (n.isClickable()) return n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
                }
            }
        }
        return false;
    }

    /** Standby, like a short press on Power: Android's lock-screen action puts the screen to sleep. */
    static boolean sleep() {
        InputKeyService service = running;
        return service != null && service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    @Override
    public void onDestroy() {
        running = null;
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(screenReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDestroy();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = this;
        // From config.xml or a theme's config.json; theme.sh rebinds this service after a push.
        Config config = Config.load(this);
        inputKeycodes = config.inputKeycodes;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        RemoteKeys.applyKeyFilter(this, config.keyFilter);
        holdAppKeys = RemoteKeys.parseHoldAppKeys(config.appKeys);
        holdAppScancodes = RemoteKeys.parseHoldAppScancodes(config.appKeys);
        allAppsKeycodes = getResources().getIntArray(R.array.all_apps_keycodes);
        tvKeys = getResources().getBoolean(R.bool.input_key_tv_keys);
        logKeys = getResources().getBoolean(R.bool.log_keys);
        tvPackage = getString(R.string.live_tv_package);
        // Read before any window event of this boot can overwrite it: Home comes up first at every boot.
        String target = resumeTarget();
        ignoreUntil = android.os.SystemClock.uptimeMillis() + 15_000;
        if (target != null && android.os.SystemClock.elapsedRealtime() < 5 * 60_000) {
            openWhenReady(target);   // just booted: back to what was on screen, like Google TV did for inputs
        }
        registerReceiver(screenReceiver, new android.content.IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    // ---- power on: back to the last input or app ----------------------------------------------------
    // Google TV's "Power on: last used input" lived in the stock launcher, which is disabled. This keeps track of
    // what was on screen (the TV app with the HDMI inputs and the tuner, another app, or Home) and opens it again at
    // power-on when Home came up instead, as Launcher settings' "Power on to" asks. Whatever was on screen comes back
    // by itself after a short standby; this only acts when the TV booted, or woke into Home.

    private static final String PREF_WATCHING = "watching_input";
    /** The app on screen when the TV went off, or Home's own package, for "Power on to: Last app". */
    private static final String PREF_LAST_APP = "last_app";
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private String tvPackage = "";
    /** Window events right after a boot are Home starting, not the owner's choice: they must not clear the flag. */
    private long ignoreUntil;
    private String foreground = "";

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("resume_input", MODE_PRIVATE);
    }

    /** The package Launcher settings' "Power on to" asks to open, from what was on screen at power-off; null for Home. */
    private String resumeTarget() {
        switch (Config.powerOnTo(this)) {
            case Config.POWER_ON_INPUT:
                return !tvPackage.isEmpty() && prefs().getBoolean(PREF_WATCHING, false) ? tvPackage : null;
            case Config.POWER_ON_APP:
                String app = prefs().getString(PREF_LAST_APP, "");
                return app.isEmpty() || app.equals(getPackageName()) ? null : app;
            default:
                return null;
        }
    }

    /**
     * At boot, opens the app only once Android's TV provider is up. Sony's TV app reads that provider on its main
     * thread, so opened 1.5 s after boot it froze waiting for it and showed "TV isn't responding". Acquiring the
     * provider waits until its process has started (WRITE_EPG_DATA, a normal permission, allows it).
     */
    private void openWhenReady(String pkg) {
        new Thread(() -> {
            long delay = 1500;
            try (android.content.ContentProviderClient tv = getContentResolver()
                    .acquireUnstableContentProviderClient(android.media.tv.TvContract.AUTHORITY)) {
                if (tv == null) delay = 20_000;   // no TV provider to wait for: give the boot time instead
            } catch (SecurityException e) {
                delay = 20_000;
            }
            handler.postDelayed(() -> open(pkg), delay);
        }, "resume-input").start();
    }

    private void open(String pkg) {
        if (!foreground.isEmpty() && !foreground.equals(getPackageName())) return;   // already somewhere else
        Intent launch = launchIntent(pkg);
        if (launch == null) return;
        try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    /** The TV entry point, or the ordinary one for an app without it (opened from All apps). */
    private Intent launchIntent(String pkg) {
        Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        return launch != null ? launch : getPackageManager().getLaunchIntentForPackage(pkg);
    }

    private final android.content.BroadcastReceiver screenReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            // Woke into Home although something else was on screen when it went off (a long standby).
            ignoreUntil = android.os.SystemClock.uptimeMillis() + 3000;
            handler.postDelayed(() -> {
                String target = resumeTarget();
                if (target != null && getPackageName().equals(foreground)) open(target);
            }, 2000);
        }
    };

    /** Remembers what the owner chose to have on screen: the TV app, another app, or Home. */
    private void trackForeground(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        boolean tv = !tvPackage.isEmpty() && p.equals(tvPackage);
        boolean choice = tv || p.equals(getPackageName()) || launchIntent(p) != null;
        if (!choice) return;   // system UI, volume bar, keyboard, dialogs: not a change of what is being watched
        foreground = p;
        if (android.os.SystemClock.uptimeMillis() < ignoreUntil && !tv) return;
        android.content.SharedPreferences prefs = prefs();
        if (prefs.getBoolean(PREF_WATCHING, false) != tv || !p.equals(prefs.getString(PREF_LAST_APP, ""))) {
            prefs.edit().putBoolean(PREF_WATCHING, tv).putString(PREF_LAST_APP, p).apply();
        }
    }

    private static boolean contains(int[] codes, int keyCode) {
        for (int code : codes) if (keyCode == code) return true;
        return false;
    }

    private boolean isInputKey(int keyCode) {
        if (tvKeys && (keyCode == KeyEvent.KEYCODE_TV || keyCode == KeyEvent.KEYCODE_TV_INPUT)) return true;
        return contains(inputKeycodes, keyCode);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (logKeys && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            Log.i(TAG, "key " + KeyEvent.keyCodeToString(keyCode) + " (" + keyCode + ") scan " + event.getScanCode());
        }
        // A scancode entry is more specific than any keycode, so it goes first.
        if (RemoteKeys.handle(this, event, appScancodes.get(event.getScanCode()),
                holdAppScancodes.get(event.getScanCode()))) return true;
        if (RemoteKeys.handle(this, event, appKeys.get(keyCode), holdAppKeys.get(keyCode))) return true;
        boolean allApps = contains(allAppsKeycodes, keyCode);
        if (!allApps && !isInputKey(keyCode)) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            if (allApps) {
                // Straight to All apps, on top of Home's task: going through Home first showed Home for a moment.
                // CLEAR_TOP and SINGLE_TOP reuse an All apps screen that is already open instead of stacking one.
                startActivity(new Intent(this, AllAppsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            } else {
                try {
                    startActivity(new Intent(ACTION_VIEW_INPUTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException ignored) {
                }
            }
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        trackForeground(event);
        if (restartUntil == 0) return;
        if (android.os.SystemClock.uptimeMillis() > restartUntil) {
            restartUntil = 0;
            return;
        }
        // The dialog's window may take a frame to fill in; try now and once more shortly after.
        if (pressRestart()) {
            restartUntil = 0;
        } else {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (restartUntil != 0 && pressRestart()) restartUntil = 0;
            }, 300);
        }
    }

    @Override
    public void onInterrupt() {
    }
}
