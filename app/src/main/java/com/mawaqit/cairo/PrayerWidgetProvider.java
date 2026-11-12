package com.mawaqit.cairo;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class PrayerWidgetProvider extends AppWidgetProvider {
    private static final String ACTION_TICK = "com.mawaqit.cairo.WIDGET_TICK";
    private static final String PREFS = "prayer_widget";
    private static final String[] KEYS = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};
    private static final String[] AR = {"الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء"};
    private static final TimeZone CAIRO = TimeZone.getTimeZone("Africa/Cairo");

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        scheduleTicks(context);
        renderAll(context);
        refreshPrayerTimes(context, false);
    }

    @Override public void onEnabled(Context context) {
        scheduleTicks(context);
        refreshPrayerTimes(context, true);
    }

    @Override public void onDisabled(Context context) {
        Intent intent = new Intent(context, PrayerWidgetProvider.class).setAction(ACTION_TICK);
        PendingIntent pi = PendingIntent.getBroadcast(context, 701, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pi);
    }

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_TICK.equals(intent.getAction())) {
            renderAll(context);
            refreshPrayerTimes(context, false);
        }
    }

    private static void scheduleTicks(Context context) {
        Intent intent = new Intent(context, PrayerWidgetProvider.class).setAction(ACTION_TICK);
        PendingIntent pi = PendingIntent.getBroadcast(context, 701, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + 15000, 60000, pi);
    }

    private static String today() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(CAIRO);
        return f.format(new Date());
    }

    private static void refreshPrayerTimes(Context context, boolean force) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!force && today().equals(p.getString("date", "")) && p.getString("Fajr", "").length() > 0) return;
        Thread thread = new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("https://api.aladhan.com/v1/timingsByCity/" + today() + "?city=Cairo&country=Egypt&method=5");
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(12000);
                connection.setRequestMethod("GET");
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
                JSONObject root = new JSONObject(body.toString());
                JSONObject timings = root.getJSONObject("data").getJSONObject("timings");
                SharedPreferences.Editor edit = p.edit().putString("date", today());
                for (String key : KEYS) edit.putString(key, clean(timings.optString(key, "")));
                edit.apply();
            } catch (Exception ignored) {
                // Keep the last cached prayer times; the widget remains usable offline.
            } finally {
                if (connection != null) connection.disconnect();
                renderAll(context);
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private static String clean(String value) {
        if (value == null) return "--:--";
        int i = value.indexOf(' ');
        return (i > 0 ? value.substring(0, i) : value).trim();
    }

    private static Calendar prayerCalendar(String time, Calendar base) {
        try {
            String[] parts = time.split(":");
            Calendar c = (Calendar) base.clone();
            c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(parts[0]));
            c.set(Calendar.MINUTE, Integer.parseInt(parts[1]));
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c;
        } catch (Exception e) { return null; }
    }

    private static void renderAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, PrayerWidgetProvider.class));
        for (int id : ids) renderOne(context, manager, id);
    }

    private static void renderOne(Context context, AppWidgetManager manager, int widgetId) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.prayer_widget);
        Calendar now = Calendar.getInstance(CAIRO, new Locale("ar", "EG"));
        SimpleDateFormat clock = new SimpleDateFormat("hh:mm a", new Locale("ar", "EG")); clock.setTimeZone(CAIRO);
        SimpleDateFormat date = new SimpleDateFormat("EEEE، d MMMM", new Locale("ar", "EG")); date.setTimeZone(CAIRO);
        SimpleDateFormat hijri = new SimpleDateFormat("d MMMM yyyy G", new Locale("ar", "SA")); hijri.setTimeZone(CAIRO);
        v.setTextViewText(R.id.widget_clock, clock.format(now.getTime()));
        v.setTextViewText(R.id.widget_date, date.format(now.getTime()) + "  •  " + hijri.format(now.getTime()));

        String[] values = new String[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            values[i] = p.getString(KEYS[i], "--:--");
            v.setTextViewText(new int[]{R.id.time_fajr, R.id.time_sunrise, R.id.time_dhuhr, R.id.time_asr, R.id.time_maghrib, R.id.time_isha}[i], values[i]);
        }

        int[] prayerIndexes = {0, 2, 3, 4, 5};
        Calendar next = null; int nextIndex = 0;
        for (int idx : prayerIndexes) {
            Calendar candidate = prayerCalendar(values[idx], now);
            if (candidate != null && candidate.after(now)) { next = candidate; nextIndex = idx; break; }
        }
        if (next == null) {
            Calendar tomorrow = (Calendar) now.clone(); tomorrow.add(Calendar.DAY_OF_YEAR, 1);
            next = prayerCalendar(values[0], tomorrow);
            nextIndex = 0;
        }
        if (next != null) {
            long remaining = Math.max(0, next.getTimeInMillis() - System.currentTimeMillis());
            v.setTextViewText(R.id.widget_next_name, "الصلاة القادمة: " + AR[nextIndex]);
            v.setChronometer(R.id.widget_countdown, SystemClock.elapsedRealtime() + remaining, "%s", true);
            if (Build.VERSION.SDK_INT >= 24) v.setChronometerCountDown(R.id.widget_countdown, true);
        } else {
            v.setTextViewText(R.id.widget_next_name, "مواقيت الصلاة - القاهرة");
            v.setTextViewText(R.id.widget_countdown, "--:--:--");
        }
        Intent launch = new Intent(context, MainActivity.class);
        PendingIntent click = PendingIntent.getActivity(context, 702, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.widget_root, click);
        manager.updateAppWidget(widgetId, v);
    }
}
