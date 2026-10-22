package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * All apps (library_opens_apps), Apple TV style: every launchable app as a card, six across, A to Z. OK opens
 * the app; hold OK for Add to Home or Remove from Home, and App info. Opened by the All apps pill and the
 * remote's Apps button. Home reloads its tiles on return when a shortcut changed (see {@link #homeChanged}).
 */
public class AllAppsActivity extends Activity {

    static final String EXTRA_HOME_PACKAGES = "home_packages";
    /** Set when a shortcut was added or removed here; MainActivity reloads its tiles and clears it. */
    static volatile boolean homeChanged;

    private static final int COLUMNS = 6;

    private final List<ResolveInfo> apps = new ArrayList<>();
    private final Set<String> onHome = new HashSet<>();
    /** Home passed its tiles in; opened by the remote's Apps button it did not, so loadApps works them out. */
    private boolean homeKnown;
    private float density;
    private int cardW, cardH;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_all_apps);
        density = getResources().getDisplayMetrics().density;
        ArrayList<String> home = getIntent().getStringArrayListExtra(EXTRA_HOME_PACKAGES);
        if (home != null) onHome.addAll(home);
        homeKnown = home != null;

        // Six cards across the width between the 96dp side margins, each with 12dp either side for the lift.
        int inner = getResources().getDisplayMetrics().widthPixels - Math.round(192 * density);
        cardW = inner / COLUMNS - Math.round(24 * density);
        cardH = Math.round(cardW * AppAdapter.DESIGN_H_DP / AppAdapter.DESIGN_W_DP);

        showWallpaper();
        loadApps();
        ((TextView) findViewById(R.id.count)).setText(
                getResources().getQuantityString(R.plurals.library_app_count, apps.size(), apps.size()));
        RecyclerView grid = findViewById(R.id.apps);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setItemAnimator(null);
        grid.setAdapter(new AppCards());
        grid.post(() -> {
            RecyclerView.ViewHolder first = grid.findViewHolderForAdapterPosition(0);
            if (first != null) first.itemView.findViewById(R.id.card).requestFocus();
        });
    }

    /** The picture Home shows (the theme's wallpaper, the current one of a rotation), at screen size in RGB_565. */
    private void showWallpaper() {
        Config config = Config.load(this);
        if (!config.wallpaperEnabled || config.wallpapers.isEmpty()) return;
        String current = getSharedPreferences("wallpaper_rotation", MODE_PRIVATE).getString("image", "");
        java.io.File file = config.wallpapers.get(0);
        for (java.io.File f : config.wallpapers) if (f.getName().equals(current)) file = f;
        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(file.getPath(), options);
        int sample = 1;
        int w = getResources().getDisplayMetrics().widthPixels;
        while (options.outWidth / (sample * 2) >= w) sample *= 2;
        options.inJustDecodeBounds = false;
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(file.getPath(), options);
        if (bitmap == null) return;
        ImageView wallpaper = findViewById(R.id.wallpaper);
        wallpaper.setImageBitmap(bitmap);
        wallpaper.setVisibility(View.VISIBLE);
        findViewById(R.id.scrim).setVisibility(View.VISIBLE);
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> unique = new LinkedHashMap<>();
        Set<String> tvApps = new HashSet<>();
        // Prefer the TV entry when an app declares both.
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                if (info.activityInfo == null || !info.activityInfo.exported) continue;
                String pkg = info.activityInfo.packageName;
                if (category.equals(Intent.CATEGORY_LEANBACK_LAUNCHER)) tvApps.add(pkg);
                if (!pkg.equals(getPackageName()) && !unique.containsKey(pkg)) unique.put(pkg, info);
            }
        }
        if (!homeKnown) {
            // The same rule Home's tiles follow (AppAdapter): TV apps unless hidden, plus added, minus removed.
            Set<String> hidden = new HashSet<>(Config.load(this).hiddenApps);
            Set<String> added = LibraryApps.load(this), removed = LibraryApps.loadRemoved(this);
            for (String pkg : unique.keySet()) {
                if (LibraryApps.visible(tvApps.contains(pkg), hidden.contains(pkg), added.contains(pkg),
                        removed.contains(pkg))) onHome.add(pkg);
            }
        }
        apps.addAll(unique.values());
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> collator.compare(label(a), label(b)));
    }

    private final Map<String, String> labels = new java.util.HashMap<>();

    private String label(ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        String cached = labels.get(pkg);
        if (cached != null) return cached;
        String value;
        try { value = Cards.cleanLabel(String.valueOf(app.loadLabel(getPackageManager())), pkg); }
        catch (RuntimeException unavailable) { value = pkg; }
        labels.put(pkg, value);
        return value;
    }

    private void open(ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(pkg);
        try {
            if (launch == null) throw new ActivityNotFoundException(pkg);
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, getString(R.string.cant_open, label(app)), Toast.LENGTH_SHORT).show();
        }
    }

    /** Hold OK: Open, Add to / Remove from Home, App info. */
    private void showOptions(ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        boolean home = onHome.contains(pkg);
        String[] items = {getString(R.string.library_open),
                getString(home ? R.string.library_remove_shortcut : R.string.library_add_home),
                getString(R.string.library_app_info)};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(label(app))
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        open(app);
                    } else if (which == 1) {
                        if (!LibraryApps.save(this, pkg, !home)) {
                            Toast.makeText(this, R.string.library_save_failed, Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (home) onHome.remove(pkg);
                        else onHome.add(pkg);
                        homeChanged = true;
                    } else {
                        try {
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (ActivityNotFoundException e) {
                            Toast.makeText(this, getString(R.string.cant_open, label(app)), Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    /** A dark tint from the icon's own colours, for apps that have no TV banner (Apple's icon backgrounds). */
    private static int tintOf(Drawable icon) {
        try {
            Bitmap one = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(one);
            icon.setBounds(0, 0, 1, 1);
            icon.draw(c);
            int px = one.getPixel(0, 0);
            one.recycle();
            float[] hsv = new float[3];
            Color.colorToHSV(px | 0xFF000000, hsv);
            hsv[1] = Math.min(hsv[1], 0.55f);
            hsv[2] = 0.30f;
            return Color.HSVToColor(hsv);
        } catch (RuntimeException e) {
            return 0xFF1C1C1E;
        }
    }

    private final class AppCards extends RecyclerView.Adapter<AppCards.Holder> {

        final class Holder extends RecyclerView.ViewHolder {
            final View card, sheen;
            final ImageView banner, icon;
            final TextView name;

            Holder(View v) {
                super(v);
                card = v.findViewById(R.id.card);
                sheen = v.findViewById(R.id.sheen);
                banner = v.findViewById(R.id.banner);
                icon = v.findViewById(R.id.icon);
                name = v.findViewById(R.id.name);
                ViewGroup.LayoutParams lp = card.getLayoutParams();
                lp.width = cardW;
                lp.height = cardH;
                card.setLayoutParams(lp);
                ((ViewGroup.MarginLayoutParams) name.getLayoutParams()).topMargin = cardH + Math.round(14 * density);
                card.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
                card.setClipToOutline(true);
                card.setOnFocusChangeListener((view, focused) -> {
                    float s = focused ? 1.10f : 1f;
                    view.animate().scaleX(s).scaleY(s)
                            .translationY(focused ? -4f * density : 0f)
                            .translationZ(focused ? 12f * density : 0f)
                            .setDuration(focused ? 160 : 110)
                            .setInterpolator(new DecelerateInterpolator()).withLayer().start();
                    sheen.animate().alpha(focused ? 1f : 0f).setDuration(160).start();
                    name.animate().alpha(focused ? 1f : 0f).setDuration(140).start();
                });
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_all_app, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            ResolveInfo app = apps.get(position);
            PackageManager pm = getPackageManager();
            Drawable banner = null;
            try {
                banner = app.activityInfo.loadBanner(pm);
                if (banner == null) banner = app.activityInfo.applicationInfo.loadBanner(pm);
            } catch (RuntimeException ignored) {
            }
            if (banner != null) {
                h.banner.setImageDrawable(banner);
                h.banner.setVisibility(View.VISIBLE);
                h.icon.setVisibility(View.GONE);
                h.card.setBackgroundResource(R.drawable.tile_bg);
            } else {
                Drawable icon;
                try { icon = app.loadIcon(pm); }
                catch (RuntimeException unavailable) { icon = getDrawable(R.drawable.ic_apps); }
                h.banner.setVisibility(View.GONE);
                h.icon.setVisibility(View.VISIBLE);
                h.icon.setImageDrawable(icon);
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(16 * density);
                bg.setColor(tintOf(icon.getConstantState() != null ? icon.getConstantState().newDrawable().mutate() : icon));
                h.card.setBackground(bg);
            }
            h.name.setText(label(app));
            h.card.setOnClickListener(v -> open(app));
            h.card.setOnLongClickListener(v -> {
                showOptions(app);
                return true;
            });
            h.card.setOnKeyListener((v, keyCode, event) -> {
                // Up from the first row stays put instead of leaving the grid.
                return event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP
                        && h.getBindingAdapterPosition() < COLUMNS;
            });
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }
    }
}
