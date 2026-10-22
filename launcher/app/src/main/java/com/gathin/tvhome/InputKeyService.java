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
        if (resumeInput) {
            handler.removeCallbacksAndMessages(null);
            try {
                unregisterReceiver(screenReceiver);
            } catch (IllegalArgumentException ignored) {
            }
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
        resumeInput = !tvPackage.isEmpty();
        if (resumeInput) {
            // Read before any window event of this boot can overwrite it: Home comes up first at every boot.
            boolean wasWatching = prefs().getBoolean(PREF_WATCHING, false);
            ignoreUntil = android.os.SystemClock.uptimeMillis() + 15_000;
            if (wasWatching && Config.resumeLastInput(this)
                    && android.os.SystemClock.elapsedRealtime() < 5 * 60_000) {
                openTvWhenReady();   // just booted: back to the input, like Google TV did
            }
            android.content.IntentFilter screenOn = new android.content.IntentFilter(Intent.ACTION_SCREEN_ON);
            registerReceiver(screenReceiver, screenOn);
        }
    }

    // ---- power on: back to the last input -----------------------------------------------------------
    // Google TV's "Power on: last used input" lived in the stock launcher, which is disabled. This keeps track
    // of whether the TV app (the HDMI inputs and the tuner) was on screen, and opens it again at power-on when
    // Home came up instead. Whatever was on screen comes back by itself after a short standby; this only acts
    // when the TV booted, or woke into Home.

    private static final String PREF_WATCHING = "watching_input";
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    /**
     * A Live TV app is configured, so what is on screen is tracked. It is tracked even while Launcher settings'
     * "Power on to last input" is off, so switching it on already knows; only opening the TV app checks the switch.
     */
    private boolean resumeInput;
    private String tvPackage = "";
    /** Window events right after a boot are Home starting, not the owner's choice: they must not clear the flag. */
    private long ignoreUntil;
    private String foreground = "";

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("resume_input", MODE_PRIVATE);
    }

    /**
     * At boot, opens the TV app only once Android's TV provider is up. Sony's TV app reads that provider on its main
     * thread, so opened 1.5 s after boot it froze waiting for it and showed "TV isn't responding". Acquiring the
     * provider waits until its process has started (WRITE_EPG_DATA, a normal permission, allows it).
     */
    private void openTvWhenReady() {
        new Thread(() -> {
            long delay = 1500;
            try (android.content.ContentProviderClient tv = getContentResolver()
                    .acquireUnstableContentProviderClient(android.media.tv.TvContract.AUTHORITY)) {
                if (tv == null) delay = 20_000;   // no TV provider to wait for: give the boot time instead
            } catch (SecurityException e) {
                delay = 20_000;
            }
            handler.postDelayed(this::openTv, delay);
        }, "resume-input").start();
    }

    private void openTv() {
        if (!foreground.isEmpty() && !foreground.equals(getPackageName())) return;   // already somewhere else
        Intent tv = getPackageManager().getLeanbackLaunchIntentForPackage(tvPackage);
        if (tv == null) return;
        try {
            startActivity(tv.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    private final android.content.BroadcastReceiver screenReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            // Woke into Home although the TV app was on screen when it went off (a long standby).
            ignoreUntil = android.os.SystemClock.uptimeMillis() + 3000;
            handler.postDelayed(() -> {
                if (prefs().getBoolean(PREF_WATCHING, false) && getPackageName().equals(foreground)
                        && Config.resumeLastInput(InputKeyService.this)) openTv();
            }, 2000);
        }
    };

    /** Remembers whether the TV app or something else the owner chose (Home, another app) is on screen. */
    private void trackForeground(AccessibilityEvent event) {
        if (!resumeInput || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        boolean tv = p.equals(tvPackage);
        boolean choice = tv || p.equals(getPackageName()) || getPackageManager().getLeanbackLaunchIntentForPackage(p) != null;
        if (!choice) return;   // system UI, volume bar, keyboard, dialogs: not a change of what is being watched
        foreground = p;
        if (android.os.SystemClock.uptimeMillis() < ignoreUntil && !tv) return;
        if (prefs().getBoolean(PREF_WATCHING, false) != tv) prefs().edit().putBoolean(PREF_WATCHING, tv).apply();
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
