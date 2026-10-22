package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private TextView wallpaperChoice;
    private TextView wallpaperAutoChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    /** The On/Off rows below Apple TV look; refresh() relabels them, since Follow theme can change two of them. */
    private final List<Runnable> switchLabels = new java.util.ArrayList<>();
    private TextView powerOnChoice;
    private String powerOn;
    private TextView tvosChoice;
    private boolean tvosStyle;
    private int columns;
    private int rowsBelowDock;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
        }
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        wallpaperAutoChoice = findViewById(R.id.wallpaperAutoChoice);
        wallpaperAutoChoice.setOnClickListener(v -> {
            int next = AUTO_MINUTES[(autoStep() + 1) % AUTO_MINUTES.length];
            SharedPreferences.Editor edit = choices.edit();
            if (next == 0) edit.remove(Config.WALLPAPER_AUTO_KEY);
            else edit.putInt(Config.WALLPAPER_AUTO_KEY, next);
            edit.apply();
            setResult(RESULT_OK);
            refresh();
        });
        if (wallpaperCollection().size() < 2) wallpaperAutoChoice.setVisibility(View.GONE);
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        tvosChoice = findViewById(R.id.tvosChoice);
        tvosStyle = Config.tvosStyle(this);
        tvosChoice.setOnClickListener(v -> {
            tvosStyle = !tvosStyle;
            choices.edit().putBoolean(Config.TVOS_STYLE_KEY, tvosStyle).apply();
            setResult(RESULT_OK);   // Home rebuilds its top bar when this screen closes
            refresh();
        });
        bindSwitch(R.id.cinematicChoice, R.string.cinematic_state, Config.CINEMATIC_KEY,
                () -> Config.load(this).cinematicHome);
        bindSwitch(R.id.glowChoice, R.string.glow_state, Config.AMBIENT_GLOW_KEY,
                () -> Config.load(this).ambientGlow);
        bindSwitch(R.id.dockDotsChoice, R.string.dock_dots_state, Config.DOCK_DOTS_KEY,
                () -> Config.deviceSwitch(this, Config.DOCK_DOTS_KEY, R.bool.dock_dots));
        bindSwitch(R.id.dnsButtonChoice, R.string.dns_button_state, Config.DNS_BUTTON_KEY,
                () -> Config.deviceSwitch(this, Config.DNS_BUTTON_KEY, R.bool.dns_button));
        bindSwitch(R.id.statsBarChoice, R.string.stats_bar_state, Config.STATS_BAR_KEY,
                () -> Config.deviceSwitch(this, Config.STATS_BAR_KEY, R.bool.stats_on_top_bar));
        // Home, then Last input (TVs with a Live TV app), then Last app; InputKeyService reads it at each power on.
        powerOnChoice = findViewById(R.id.powerOnChoice);
        powerOn = Config.powerOnTo(this);
        boolean liveTv = !getString(R.string.live_tv_package).isEmpty();
        powerOnChoice.setOnClickListener(v -> {
            powerOn = Config.POWER_ON_HOME.equals(powerOn) ? (liveTv ? Config.POWER_ON_INPUT : Config.POWER_ON_APP)
                    : Config.POWER_ON_INPUT.equals(powerOn) ? Config.POWER_ON_APP : Config.POWER_ON_HOME;
            choices.edit().putString(Config.POWER_ON_KEY, powerOn).apply();
            refresh();
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.CINEMATIC_KEY).remove(Config.AMBIENT_GLOW_KEY).remove(Config.WALLPAPER_AUTO_KEY)
                    .commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            setResult(RESULT_OK);
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        refresh();
        columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    /** "Change wallpaper" steps through these on OK: Off, every 30 minutes, every hour, every day. */
    private static final int[] AUTO_MINUTES = {0, 30, 60, 1440};
    private static final int[] AUTO_LABELS = {R.string.off, R.string.wallpaper_auto_30, R.string.wallpaper_auto_60,
            R.string.wallpaper_auto_day};

    /** The wallpapers/ collection "Change wallpaper" rotates; it needs two or more pictures. */
    private List<File> wallpaperCollection() {
        File dir = Config.dir(this);
        return WallpaperRotation.images(dir == null ? null : new File(dir, "wallpapers"));
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(wallpaperCollection());
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 1];
        for (int i = 0; i < images.size(); i++) labels[i] = images.get(i).getName();
        labels[images.size()] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size()
                : indexOf(images, current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    // One picture, or None: automatic change stops, or it would cover the choice.
                    choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                    which == images.size() ? "none" : images.get(which).getName())
                            .remove(Config.WALLPAPER_AUTO_KEY).apply();
                    setResult(RESULT_OK);
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).remove(Config.WALLPAPER_AUTO_KEY).apply();
                    setResult(RESULT_OK);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private int autoStep() {
        int minutes = choices.getInt(Config.WALLPAPER_AUTO_KEY, 0);
        for (int i = 0; i < AUTO_MINUTES.length; i++) if (AUTO_MINUTES[i] == minutes) return i;
        return 0;
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    /** An On/Off row saved under key: OK flips what current reports, and Home rebuilds when this screen closes. */
    private void bindSwitch(int id, int labelRes, String key, java.util.function.BooleanSupplier current) {
        TextView row = findViewById(id);
        Runnable label = () -> row.setText(getString(labelRes,
                getString(current.getAsBoolean() ? R.string.on : R.string.off)));
        row.setOnClickListener(v -> {
            choices.edit().putBoolean(key, !current.getAsBoolean()).apply();
            setResult(RESULT_OK);
            refresh();
        });
        switchLabels.add(label);
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        tvosChoice.setText(getString(R.string.tvos_style_state, getString(tvosStyle ? R.string.on : R.string.off)));
        for (Runnable label : switchLabels) label.run();
        powerOnChoice.setText(getString(R.string.power_on_state, getString(
                Config.POWER_ON_INPUT.equals(powerOn) ? R.string.power_on_input
                        : Config.POWER_ON_APP.equals(powerOn) ? R.string.power_on_app : R.string.power_on_home)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        boolean auto = autoStep() > 0;
        wallpaperAutoChoice.setText(getString(R.string.wallpaper_auto_state, getString(AUTO_LABELS[autoStep()])));
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                auto ? getString(R.string.wallpaper_changing)
                        : "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : getString(R.string.wallpaper_theme_default)));
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.CINEMATIC_KEY) || choices.contains(Config.AMBIENT_GLOW_KEY)
                || choices.contains(Config.WALLPAPER_AUTO_KEY)
                || choices.contains(Config.WALLPAPER_CHOICE_KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
