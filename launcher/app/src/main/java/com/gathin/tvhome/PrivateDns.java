package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.util.regex.Pattern;

/**
 * Android's Private DNS setting (Android 9+), switched from the DNS pill. Writing it needs
 * WRITE_SECURE_SETTINGS, which no app can ask for: the install grants it once with
 * {@code pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS}.
 * Off means "opportunistic", Android's own default (encrypted DNS when the network's resolver offers it).
 */
final class PrivateDns {
    static final String ADGUARD = "dns.adguard-dns.com";
    static final String ADGUARD_FAMILY = "family.adguard-dns.com";
    static final String CLOUDFLARE = "one.one.one.one";

    private static final String MODE = "private_dns_mode";
    private static final String SPECIFIER = "private_dns_specifier";
    private static final String MODE_HOSTNAME = "hostname";
    private static final String MODE_OFF = "opportunistic";
    private static final String PREFS = "private_dns";
    private static final String CUSTOM = "custom_host";
    private static final Pattern HOST = Pattern.compile(
            "^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");

    private PrivateDns() {}

    /** The resolver in use, or null when Private DNS is off or automatic. */
    static String current(Context c) {
        ContentResolver cr = c.getContentResolver();
        if (!MODE_HOSTNAME.equals(Settings.Global.getString(cr, MODE))) return null;
        String host = Settings.Global.getString(cr, SPECIFIER);
        return host == null || host.isEmpty() ? null : host;
    }

    /** Turns Private DNS on with this resolver, or off with null. False when the permission is missing. */
    static boolean set(Context c, String host) {
        ContentResolver cr = c.getContentResolver();
        try {
            if (host == null) return Settings.Global.putString(cr, MODE, MODE_OFF);
            return Settings.Global.putString(cr, SPECIFIER, host)
                    && Settings.Global.putString(cr, MODE, MODE_HOSTNAME);
        } catch (SecurityException e) {
            return false;
        }
    }

    /** The cleaned hostname, or null when it is not one ("https://" and paths are not accepted by Android). */
    static String normalise(String input) {
        if (input == null) return null;
        String h = input.trim().toLowerCase(java.util.Locale.ROOT);
        return HOST.matcher(h).matches() ? h : null;
    }

    /** Public, family or a personal AdGuard DNS server (<id>.d.adguard-dns.com). */
    static boolean isAdGuard(String host) {
        return host != null && (host.equals("adguard-dns.com") || host.endsWith(".adguard-dns.com"));
    }

    static boolean isPreset(String host) {
        return ADGUARD.equals(host) || ADGUARD_FAMILY.equals(host) || CLOUDFLARE.equals(host);
    }

    /** The last custom resolver the owner typed, so it stays one press away. */
    static String savedCustom(Context c) {
        return prefs(c).getString(CUSTOM, null);
    }

    static void saveCustom(Context c, String host) {
        prefs(c).edit().putString(CUSTOM, host).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
