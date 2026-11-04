package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** SUMBAJANG_DATA_VISUAL_LOCATION_V2: location, official Indonesian prayer timetable, and Wikimedia Commons photo. */
final class SumbajangWidgetController {
    private static final String PREFS = "sumbajang_widget_v1";
    private static final String REGIONS_URL = "https://sajadah.org/api/v1/regions.json";
    private static final String COMMONS_API = "https://commons.wikimedia.org/w/api.php";
    private static final long REFRESH_INTERVAL_MS = 60L * 60L * 1000L;
    private static final int LOCATION_PERMISSION_REQUEST = 8317;
    // Keep empty for automatic STB location. Set a Sajadah region id here only as a manual override.
    private static final String MANUAL_REGION_ID = "";

    private final Activity activity;
    private final SumbajangWidgetView view;
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DateTimeFormatter shortDate = DateTimeFormatter.ofPattern("d MMM", new Locale("id", "ID"));
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateCountdown();
            main.postDelayed(this, 1000L);
        }
    };

    private JSONObject region;
    private JSONObject annualSchedule;
    private Map<String, Integer> fieldIndexes = new HashMap<>();
    private String[] todayTimes = {"--:--", "--:--", "--:--", "--:--", "--:--"};
    private String regionName = "";
    private ZoneId zone = ZoneId.systemDefault();
    private long nextPrayerMillis = -1L;
    private int nextPrayerIndex = -1;
    private LocalDate loadedDate;
    private boolean started;
    private boolean refreshInProgress;
    private boolean permissionRequested;

    private SumbajangWidgetController(Activity activity, SumbajangWidgetView view) {
        this.activity = activity;
        this.view = view;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // SUMBAJANG_WIDGET_VISIBILITY_BRIDGE_V19: MainActivity controls this non-focusable overlay by dock focus.
    static void setWidgetVisible(FrameLayout root, boolean visible) {
        if (root == null) return;
        for (int i = 0; i < root.getChildCount(); i++) {
            android.view.View child = root.getChildAt(i);
            if (child instanceof SumbajangWidgetView) {
                child.animate().cancel();
                child.clearAnimation();
                if (visible) {
                    child.setAlpha(0f);
                    child.setVisibility(android.view.View.VISIBLE);
                    child.animate().alpha(1f).setDuration(160L).start();
                } else {
                    child.setAlpha(1f);
                    child.animate().alpha(0f).setDuration(140L).withEndAction(() -> {
                        if (child.getAlpha() <= 0.01f) child.setVisibility(android.view.View.GONE);
                    }).start();
                }
                return;
            }
        }
    }
    static void install(Activity activity, FrameLayout root) {
        if (root == null || root.getTag(R.id.sumbajang_widget_tag) != null) return;
        root.setTag(R.id.sumbajang_widget_tag, Boolean.TRUE);
        SumbajangWidgetView widget = new SumbajangWidgetView(activity);
        // SUMBAJANG_V12_NO_MANUAL_MENU: no click or long-press menu; all refreshes are automatic.
        root.post(() -> {
            // SUMBAJANG_BELOW_LAUNCHER_CLOCK_V1: size stays compact; position follows the real launcher clock block.
            int availableWidth = Math.max(dp(activity, 320), root.getWidth() - dp(activity, 64));
            int width = Math.min(availableWidth, Math.max(dp(activity, 220), Math.round(root.getWidth() * 0.40f))); // SUMBAJANG_LAYOUT_V5_WIDTH
            int height = Math.round(width * 520f / 1200f);
            FrameLayout.LayoutParams lp = layoutBelowLauncherClock(activity, root, width, height);
            widget.setElevation(dp(activity, 18));
            root.addView(widget, lp);

            // Reposition if the launcher rearranges its clock/date block after the first layout pass.
            android.view.View clockBlock = activity.findViewById(R.id.leftBlock);
            if (clockBlock != null) {
                clockBlock.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        root.post(() -> {
                            if (widget.getParent() == root) {
                                widget.setLayoutParams(layoutBelowLauncherClock(activity, root, width, height));
                            }
                        }));
            }

            // SUMBAJANG_LAYOUT_V5_SHELF_LISTENER: recalculate if CleanYourTV moves the BROWSER/title shelf.
            android.view.View heroAnchor = activity.findViewById(R.id.heroName);
            if (heroAnchor != null) {
                heroAnchor.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        root.post(() -> {
                            if (widget.getParent() == root) {
                                FrameLayout.LayoutParams updated = layoutBelowLauncherClock(activity, root, width, height);
                                widget.setLayoutParams(updated);
                            }
                        }));
            }            SumbajangWidgetController controller = new SumbajangWidgetController(activity, widget);
            widget.setTag(controller);
            controller.start();
        });
    }

    private static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }
    // Positions the card directly below CleanYourTV's left clock/date header (R.id.leftBlock).
    // Screen coordinates are converted into the root FrameLayout's coordinate space.
        // SUMBAJANG_LAYOUT_V5_SAFE_AREA: keep the entire prayer card between the clock and the app-name shelf.
    private static FrameLayout.LayoutParams layoutBelowLauncherClock(
            Activity activity, FrameLayout root, int width, int height) {
        int[] rootLocation = new int[2];
        root.getLocationOnScreen(rootLocation);

        int left = dp(activity, 24);
        int top = Math.max(dp(activity, 20), root.getHeight() / 12);
        android.view.View clock = activity.findViewById(R.id.leftBlock);
        if (clock == null) clock = activity.findViewById(R.id.date);
        if (clock != null && clock.getWidth() > 0 && clock.getHeight() > 0
                && clock.getVisibility() == android.view.View.VISIBLE) {
            int[] clockLocation = new int[2];
            clock.getLocationOnScreen(clockLocation);
            left = clockLocation[0] - rootLocation[0];
            top = clockLocation[1] - rootLocation[1] + clock.getHeight() + dp(activity, 6);
        }

        // heroName is the large active-app title (for example BROWSER). Keep a small clear gap above it.
        android.view.View shelf = activity.findViewById(R.id.heroName);
        if (shelf == null || shelf.getVisibility() != android.view.View.VISIBLE || shelf.getHeight() <= 0) {
            shelf = activity.findViewById(R.id.hero);
        }
        int safeBottom = root.getHeight() - dp(activity, 116);
        if (shelf != null && shelf.getVisibility() == android.view.View.VISIBLE && shelf.getHeight() > 0) {
            int[] shelfLocation = new int[2];
            shelf.getLocationOnScreen(shelfLocation);
            int shelfTop = shelfLocation[1] - rootLocation[1];
            if (shelfTop > top) safeBottom = Math.min(safeBottom, shelfTop - dp(activity, 10));
        }

        int availableHeight = Math.max(dp(activity, 76), safeBottom - top);
        int targetHeight = Math.min(height, availableHeight);
        int targetWidth = Math.round(targetHeight * 1200f / 520f);
        int maxWidth = Math.max(dp(activity, 220), root.getWidth() - dp(activity, 48));
        if (targetWidth > maxWidth) {
            targetWidth = maxWidth;
            targetHeight = Math.round(targetWidth * 520f / 1200f);
        }

        int maxLeft = Math.max(dp(activity, 24), root.getWidth() - targetWidth - dp(activity, 24));
        left = Math.max(dp(activity, 24), Math.min(left, maxLeft));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                targetWidth, targetHeight, Gravity.TOP | Gravity.START);
        params.leftMargin = left;
        params.topMargin = top;
        return params;
    }

    private void start() {
        if (started) return;
        started = true;
        // SUMBAJANG_V12_BUNDLED_PHOTO: show a local mosque image immediately, even without STB internet.
        try {
            Bitmap bundledMosque = BitmapFactory.decodeResource(activity.getResources(), R.drawable.sumbajang_mosque);
            if (bundledMosque != null) {
                view.setMosqueBitmap(bundledMosque);
                android.util.Log.i("SUMBAJANG", "Foto masjid lokal siap ditampilkan.");
            } else {
                android.util.Log.e("SUMBAJANG", "Aset foto masjid lokal gagal didekode.");
            }
        } catch (Exception photoError) {
            android.util.Log.e("SUMBAJANG", "Gagal memuat aset foto masjid lokal.", photoError);
        }
        ticker.run();
        requestLocationPermissionIfNeeded();
        refresh(false);
        // Retry once after the Android location permission prompt has had time to be answered.
        main.postDelayed(() -> refresh(false), 15000L);
        main.postDelayed(new Runnable() {
            @Override public void run() {
                refresh(false);
                main.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        }, REFRESH_INTERVAL_MS);
    }

    private void requestLocationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 23 || permissionRequested) return;
        boolean fine = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse && !prefs.getBoolean("location_prompted", false)) {
            permissionRequested = true;
            prefs.edit().putBoolean("location_prompted", true).apply();
            activity.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST);
        }
    }

    void refresh(boolean userInitiated) {
        if (refreshInProgress) return;
        refreshInProgress = true;
        view.setRefreshing(true);
        executor.execute(() -> {
            try {
                LocationPoint point = resolveLocation();
                JSONObject index = getJson(REGIONS_URL, "regions_json");
                JSONObject chosenRegion = chooseRegion(index, point);
                if (chosenRegion == null) {
                    throw new IllegalStateException("Lokasi belum cukup akurat. Tahan tombol OK pada widget untuk memilih kota.");
                }
                String id = chosenRegion.optString("id", "");
                // Keep bundled fallback visible while automatic city-photo lookup runs.
                int year = LocalDate.now(ZoneId.of(chosenRegion.optString("tz", "Asia/Jakarta"))).getYear();
                String scheduleUrl = "https://sajadah.org/api/v1/times/id/" + id + "/" + year + ".json";
                JSONObject schedule = getJson(scheduleUrl, "schedule_" + id + "_" + year);
                JSONObject finalRegion = chosenRegion;
                JSONObject finalSchedule = schedule;
                boolean isManual = !MANUAL_REGION_ID.isEmpty() || !prefs.getString("manual_region_id", "").isEmpty();
                String sourceLabel = isManual ? "Pilihan manual" : (point == null ? "Tidak diketahui" : point.source);
                prefs.edit().putString("last_location_method", sourceLabel)
                        .putString("last_region_id", id)
                        .putString("last_location_city", finalRegion.optString("shortName", finalRegion.optString("name", "Indonesia")))
                        .apply();
                main.post(() -> {
                    region = finalRegion;
                    annualSchedule = finalSchedule;
                    regionName = finalRegion.optString("shortName", finalRegion.optString("name", "Indonesia"));
                    try { zone = ZoneId.of(finalRegion.optString("tz", "Asia/Jakarta")); }
                    catch (Exception ignored) { zone = ZoneId.systemDefault(); }
                    readScheduleForToday();
                    updateCountdown();
                    view.setRefreshing(false);
                    refreshInProgress = false;
                });
                // Search the actual selected city and province, not only a generic city label.
                Bitmap mosque = loadMosquePhoto(finalRegion);
                if (mosque != null) main.post(() -> view.setMosqueBitmap(mosque));
            } catch (Exception ex) {
                main.post(() -> {
                    if (region == null) {
                        view.setPrayerState("--:--:--", "Lokasi belum akurat", "Tahan OK untuk memilih kota",
                                new String[]{"--:--", "--:--", "--:--", "--:--", "--:--"}, -1);
                    }
                    view.setRefreshing(false);
                    refreshInProgress = false;
                });
            }
        });
    }

    private LocationPoint resolveLocation() {
        // A user-selected region is more reliable than device/IP geolocation on GPS-less TV boxes.
        if (!MANUAL_REGION_ID.isEmpty() || !prefs.getString("manual_region_id", "").isEmpty()) return null;
        LocationPoint fromAndroid = getAndroidLocation();
        if (fromAndroid != null) return fromAndroid;
        try {
            // Never reuse a cached IP location: the STB may have moved or its ISP route may have changed.
            JSONObject ip = getJsonFresh("https://ipapi.co/json/");
            double lat = ip.optDouble("latitude", Double.NaN);
            double lon = ip.optDouble("longitude", Double.NaN);
            String city = ip.optString("city", "");
            String province = ip.optString("region", "");
            if (isValidCoordinate(lat, lon)) {
                return new LocationPoint(lat, lon, city, province, "IP (perkiraan)", 30000f);
            }
        } catch (Exception ignored) { }
        return null;
    }

    private LocationPoint getAndroidLocation() {
        if (Build.VERSION.SDK_INT < 23) return null;
        boolean fine = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) return null;
        try {
            LocationManager manager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
            if (manager == null) return null;
            String[] providers = {LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER};

            // Ask for a fresh provider fix first; old last-known TV-box coordinates are commonly stale.
            if (Build.VERSION.SDK_INT >= 30) {
                for (String provider : providers) {
                    try {
                        if (!manager.isProviderEnabled(provider)) continue;
                        CountDownLatch latch = new CountDownLatch(1);
                        AtomicReference<Location> result = new AtomicReference<>();
                        manager.getCurrentLocation(provider, null, activity.getMainExecutor(), location -> {
                            result.set(location);
                            latch.countDown();
                        });
                        latch.await(5500L, TimeUnit.MILLISECONDS);
                        Location current = result.get();
                        if (isAcceptableLocation(current, 15L * 60L * 1000L, 20000f)) {
                            return new LocationPoint(current.getLatitude(), current.getLongitude(), "", "", "Android Location", current.getAccuracy());
                        }
                    } catch (Exception ignored) { }
                }
            }

            // A last-known point is accepted only when recent and reasonably accurate.
            Location best = null;
            for (String provider : providers) {
                try {
                    if (!manager.isProviderEnabled(provider)) continue;
                    Location candidate = manager.getLastKnownLocation(provider);
                    if (!isAcceptableLocation(candidate, 20L * 60L * 1000L, 15000f)) continue;
                    if (best == null || candidate.getAccuracy() < best.getAccuracy()) best = candidate;
                } catch (Exception ignored) { }
            }
            if (best != null) {
                return new LocationPoint(best.getLatitude(), best.getLongitude(), "", "", "Android Location (terakhir, baru)", best.getAccuracy());
            }
        } catch (Exception ignored) { }
        return null;
    }

    private boolean isAcceptableLocation(Location location, long maxAgeMs, float maxAccuracyM) {
        if (location == null || !isValidCoordinate(location.getLatitude(), location.getLongitude()) || !location.hasAccuracy()) return false;
        long age = System.currentTimeMillis() - location.getTime();
        return age >= -60000L && age <= maxAgeMs && location.getAccuracy() > 0f && location.getAccuracy() <= maxAccuracyM;
    }

    private boolean isValidCoordinate(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon) && lat >= -11.5 && lat <= 6.5 && lon >= 94.5 && lon <= 141.5;
    }

    private JSONObject chooseRegion(JSONObject index, LocationPoint point) {
        if (index == null) return null;
        JSONArray regions = index.optJSONArray("regions");
        if (regions == null || regions.length() == 0) return null;

        // Explicit source-code override, then the region chosen from the TV's on-screen picker.
        String manualId = !MANUAL_REGION_ID.isEmpty() ? MANUAL_REGION_ID : prefs.getString("manual_region_id", "");
        if (!manualId.isEmpty()) {
            for (int i = 0; i < regions.length(); i++) {
                JSONObject candidate = regions.optJSONObject(i);
                if (candidate != null && manualId.equals(candidate.optString("id"))) return candidate;
            }
        }
        if (point == null) return null;

        // IP geolocation often supplies a useful city name even when its coordinates are ISP-level.
        // Prefer an exact city + province match over a nearest-centroid guess.
        String pointCity = normalizePlaceName(point.city);
        String pointProvince = normalizePlaceName(point.province);
        if (!pointCity.isEmpty()) {
            JSONObject cityMatch = null;
            for (int i = 0; i < regions.length(); i++) {
                JSONObject candidate = regions.optJSONObject(i);
                if (candidate == null || !"ID".equalsIgnoreCase(candidate.optString("country"))) continue;
                String shortName = normalizePlaceName(candidate.optString("shortName", ""));
                String fullName = normalizePlaceName(candidate.optString("name", ""));
                if (!pointCity.equals(shortName) && !pointCity.equals(fullName)) continue;
                String candidateProvince = normalizePlaceName(candidate.optString("province", ""));
                if (!pointProvince.isEmpty() && !candidateProvince.isEmpty()
                        && !canonicalProvince(pointProvince).equals(canonicalProvince(candidateProvince))) continue;
                cityMatch = candidate;
                break;
            }
            if (cityMatch != null) return cityMatch;
        }

        JSONObject nearest = null;
        double nearestKm = Double.MAX_VALUE;
        for (int i = 0; i < regions.length(); i++) {
            JSONObject candidate = regions.optJSONObject(i);
            if (candidate == null || !"ID".equalsIgnoreCase(candidate.optString("country"))) continue;
            double lat = candidate.optDouble("lat", Double.NaN);
            double lon = candidate.optDouble("lng", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
            double distance = haversineKm(point.lat, point.lon, lat, lon);
            if (distance < nearestKm) { nearestKm = distance; nearest = candidate; }
        }
        // Do not silently assign a distant region based on a rough IP centroid.
        double allowedKm = point.source.startsWith("Android") ? Math.max(35d, point.accuracyM / 1000d * 2.0d) : 40d;
        return nearest != null && nearestKm <= allowedKm ? nearest : null;
    }

    private String normalizePlaceName(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replace("kabupaten", "").replace("kab.", "")
                .replace("kota administrasi", "").replace("kota", "")
                .replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    private String canonicalProvince(String normalized) {
        if (normalized == null) return "";
        switch (normalized) {
            case "north sumatra": return "sumatera utara";
            case "west sumatra": return "sumatera barat";
            case "south sumatra": return "sumatera selatan";
            case "riau islands": return "kepulauan riau";
            case "bangka belitung islands": return "kepulauan bangka belitung";
            case "west java": return "jawa barat";
            case "central java": return "jawa tengah";
            case "east java": return "jawa timur";
            case "special region of yogyakarta": return "daerah istimewa yogyakarta";
            case "west kalimantan": return "kalimantan barat";
            case "central kalimantan": return "kalimantan tengah";
            case "south kalimantan": return "kalimantan selatan";
            case "east kalimantan": return "kalimantan timur";
            case "north kalimantan": return "kalimantan utara";
            case "north sulawesi": return "sulawesi utara";
            case "central sulawesi": return "sulawesi tengah";
            case "south sulawesi": return "sulawesi selatan";
            case "southeast sulawesi": return "sulawesi tenggara";
            case "west sulawesi": return "sulawesi barat";
            case "gorontalo province": return "gorontalo";
            case "west nusa tenggara": return "nusa tenggara barat";
            case "east nusa tenggara": return "nusa tenggara timur";
            case "papua province": return "papua";
            case "west papua": return "papua barat";
            case "southwest papua": return "papua barat daya";
            case "central papua": return "papua tengah";
            case "highland papua": return "papua pegunungan";
            case "south papua": return "papua selatan";
            default: return normalized;
        }
    }

    private void readScheduleForToday() {
        if (annualSchedule == null) return;
        JSONArray fields = annualSchedule.optJSONArray("fields");
        JSONArray days = annualSchedule.optJSONArray("days");
        if (fields == null || days == null) return;
        fieldIndexes.clear();
        for (int i = 0; i < fields.length(); i++) fieldIndexes.put(fields.optString(i), i);
        LocalDate today = LocalDate.now(zone);
        JSONArray row = findDay(days, today.toString());
        if (row == null) return;
        loadedDate = today;
        todayTimes = new String[]{fieldValue(row, "fajr"), fieldValue(row, "dhuhr"),
                fieldValue(row, "asr"), fieldValue(row, "maghrib"), fieldValue(row, "isha")};
        String source = annualSchedule.optJSONObject("authority") == null ? "Kemenag RI" :
                annualSchedule.optJSONObject("authority").optString("shortName", "Kemenag RI");
        prefs.edit().putString("last_source", source).apply();
    }

    private JSONArray findDay(JSONArray days, String date) {
        for (int i = 0; i < days.length(); i++) {
            JSONArray row = days.optJSONArray(i);
            if (row != null && row.length() > 0 && date.equals(row.optString(0))) return row;
        }
        return null;
    }

    private String fieldValue(JSONArray row, String field) {
        Integer idx = fieldIndexes.get(field);
        if (idx == null || idx < 0 || idx >= row.length()) return "--:--";
        return row.optString(idx, "--:--");
    }

    private void updateCountdown() {
        if (region == null || annualSchedule == null) return;
        LocalDate today = LocalDate.now(zone);
        if (loadedDate == null || !loadedDate.equals(today)) readScheduleForToday();
        JSONArray days = annualSchedule.optJSONArray("days");
        if (days == null) return;
        JSONArray row = findDay(days, today.toString());
        if (row == null) return;
        String[] keys = {"fajr", "dhuhr", "asr", "maghrib", "isha"};
        String[] labels = {"Subuh", "Zuhur", "Ashar", "Maghrib", "Isya"};
        long now = System.currentTimeMillis();
        nextPrayerMillis = -1L;
        nextPrayerIndex = -1;
        for (int i = 0; i < keys.length; i++) {
            String value = fieldValue(row, keys[i]);
            if (!isValidTime(value)) continue;
            long target = LocalDateTime.of(today, LocalTime.parse(value)).atZone(zone).toInstant().toEpochMilli();
            if (target > now) { nextPrayerMillis = target; nextPrayerIndex = i; break; }
        }
        if (nextPrayerMillis < 0) {
            JSONArray tomorrow = findDay(days, today.plusDays(1).toString());
            if (tomorrow != null) {
                String fajr = fieldValue(tomorrow, "fajr");
                if (isValidTime(fajr)) {
                    nextPrayerMillis = LocalDateTime.of(today.plusDays(1), LocalTime.parse(fajr)).atZone(zone).toInstant().toEpochMilli();
                    nextPrayerIndex = SumbajangWidgetView.PRAYER_SUBUH;
                }
            }
        }
        long seconds = nextPrayerMillis > now ? (nextPrayerMillis - now) / 1000L : 0L;
        String countdownText = String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600L,
                (seconds % 3600L) / 60L, seconds % 60L);
        String nextText = nextPrayerIndex >= 0 ? "Menuju waktu " + labels[nextPrayerIndex] : "Jadwal salat berikutnya";
        String dateText = regionName + "  •  " + today.format(shortDate);
        view.setPrayerState(countdownText, nextText, dateText, todayTimes, nextPrayerIndex);
    }

    // SUMBAJANG_MOSQUE_PHOTO_V3: city-aware Commons search + visible photo fallback.
    private Bitmap loadMosquePhoto(JSONObject selectedRegion) {
        // SUMBAJANG_PALU_PHOTO_V5: use a verified, city-specific Commons file first for Palu.
        if (selectedRegion != null) {
            String selectedCityV5 = selectedRegion.optString("shortName", selectedRegion.optString("name", ""));
            if (selectedCityV5.toLowerCase(Locale.ROOT).contains("palu")) {
                Bitmap paluPhotoV5 = loadPaluMosquePhotoV5();
                if (paluPhotoV5 != null) return paluPhotoV5;
            }
        }
        if (selectedRegion == null) return null;
        String city = selectedRegion.optString("shortName", selectedRegion.optString("name", "Indonesia"));
        String province = selectedRegion.optString("province", "");
        String cacheKey = safeKey(city + "_" + province);
        File imageFile = new File(activity.getFilesDir(), "sumbajang-mosque-" + cacheKey + ".jpg");
        Bitmap cached = imageFile.exists() ? BitmapFactory.decodeFile(imageFile.getAbsolutePath()) : null;
        boolean cacheFresh = imageFile.exists() && System.currentTimeMillis() - imageFile.lastModified()
                < 30L * 24L * 60L * 60L * 1000L;
        if (cached != null && cacheFresh) return cached;

        String[] queries = {
                "Masjid Raya " + city + " " + province + " Indonesia",
                "Masjid Agung " + city + " " + province + " Indonesia",
                "Masjid " + city + " " + province + " Indonesia",
                "Mosque " + city + " " + province + " Indonesia",
                "Masjid " + city,
                "Mosque " + city
        };
        try {
            for (String query : queries) {
                String api = COMMONS_API + "?action=query&generator=search&gsrsearch=" + enc(query)
                        + "&gsrnamespace=6&gsrlimit=40&gsrwhat=text&prop=imageinfo"
                        + "&iiprop=url%7Cextmetadata&iilimit=1&iiurlwidth=1280&format=json";
                JSONObject response = getJsonFresh(api);
                JSONObject queryObject = response.optJSONObject("query");
                JSONObject pages = queryObject == null ? null : queryObject.optJSONObject("pages");
                if (pages == null) continue;

                JSONObject bestInfo = null;
                String bestTitle = "";
                int bestScore = Integer.MIN_VALUE;
                String cityKey = normalizePlaceName(city);
                String provinceKey = normalizePlaceName(province);
                for (java.util.Iterator<String> it = pages.keys(); it.hasNext();) {
                    JSONObject page = pages.optJSONObject(it.next());
                    if (page == null) continue;
                    String title = page.optString("title", "");
                    JSONArray info = page.optJSONArray("imageinfo");
                    JSONObject first = info == null ? null : info.optJSONObject(0);
                    if (first == null) continue;
                    JSONObject metadata = first.optJSONObject("extmetadata");
                    String description = metadataValue(metadata, "ImageDescription");
                    String searchable = normalizePlaceName(title.replace("File:", "") + " " + description);
                    String lower = (title + " " + description).toLowerCase(Locale.ROOT);
                    boolean mosqueHit = lower.matches(".*(masjid|mosque|mosque|masjid raya|masjid agung|mosquee).*" );
                    boolean cityHit = !cityKey.isEmpty() && searchable.contains(cityKey);
                    boolean provinceHit = !provinceKey.isEmpty() && searchable.contains(provinceKey);
                    // The search query already contains the selected city; accept a mosque result even
                    // when Commons has no locality text in its metadata, but rank explicit locality higher.
                    if (!mosqueHit && !cityHit && !provinceHit) continue;
                    int score = (cityHit ? 120 : 0) + (provinceHit ? 25 : 0) + (mosqueHit ? 45 : 0);
                    String titleLower = title.toLowerCase(Locale.ROOT);
                    if (titleLower.contains("raya") || titleLower.contains("agung")) score += 12;
                    if (score > bestScore) { bestScore = score; bestTitle = title; bestInfo = first; }
                }
                if (bestInfo == null) continue;

                String imageUrl = bestInfo.optString("thumburl", bestInfo.optString("url", ""));
                if (imageUrl.isEmpty()) continue;
                byte[] bytes = getBytes(imageUrl);
                Bitmap downloaded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (downloaded == null) continue;
                try (FileOutputStream out = new FileOutputStream(imageFile)) { out.write(bytes); out.flush(); }

                JSONObject metadata = bestInfo.optJSONObject("extmetadata");
                String artist = metadataValue(metadata, "Artist");
                String license = metadataValue(metadata, "LicenseShortName");
                String pageUrl = bestInfo.optString("descriptionurl", "https://commons.wikimedia.org/");
                StringBuilder credit = new StringBuilder("Foto: ");
                credit.append(artist.isEmpty() ? bestTitle.replace("File:", "") : artist);
                if (!license.isEmpty()) credit.append(" Â· ").append(license);
                credit.append("\n").append(pageUrl);
                prefs.edit().putString("photo_credit_" + cacheKey, credit.toString()).apply();
                android.util.Log.i("SUMBAJANG", "Foto masjid dimuat: " + bestTitle);
                return downloaded;
            }
        } catch (Exception error) {
            android.util.Log.w("SUMBAJANG", "Pencarian foto Commons gagal untuk " + city, error);
        }

        // Built-in fallbacks are city-specific and used only when the city-matched Commons search fails.
        // Both photographs are on Wikimedia Commons under CC BY-SA 4.0; source credit is saved below.
        String cityKeyForFallback = normalizePlaceName(city);
        String fallbackUrl = null;
        String fallbackCredit = null;
        if (cityKeyForFallback.contains("makassar")) {
            fallbackUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/6/64/"
                    + "MASJID_99_KUBAH%2C_MAKASSAR_2026.jpg/1280px-MASJID_99_KUBAH%2C_MAKASSAR_2026.jpg";
            fallbackCredit = "Foto: Volstand Â· CC BY-SA 4.0\n"
                    + "https://commons.wikimedia.org/wiki/File:MASJID_99_KUBAH,_MAKASSAR_2026.jpg";
        } else if (cityKeyForFallback.contains("palu")) {
            fallbackUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/6/66/"
                    + "%28MASJID_BAITUL_KHAIRAAT%29.Masjid_Raya_Kota_Palu_yang_baru_diresmikan_Desember_lalu._Bentuk_bangu.webp/"
                    + "960px-%28MASJID_BAITUL_KHAIRAAT%29.Masjid_Raya_Kota_Palu_yang_baru_diresmikan_Desember_lalu._Bentuk_bangu.webp.png";
            fallbackCredit = "Foto: Nurdiansyah46 Â· CC BY-SA 4.0\n"
                    + "https://commons.wikimedia.org/wiki/File:(MASJID_BAITUL_KHAIRAAT).Masjid_Raya_Kota_Palu_yang_baru_diresmikan_Desember_lalu._Bentuk_bangu.webp";
        }
        if (fallbackUrl != null) {
            try {
                byte[] bytes = getBytes(fallbackUrl);
                Bitmap fallback = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (fallback != null) {
                    try (FileOutputStream out = new FileOutputStream(imageFile)) { out.write(bytes); out.flush(); }
                    prefs.edit().putString("photo_credit_" + cacheKey, fallbackCredit).apply();
                    android.util.Log.i("SUMBAJANG", "Foto fallback kota dimuat: " + city);
                    return fallback;
                }
            } catch (Exception error) {
                android.util.Log.w("SUMBAJANG", "Foto fallback kota gagal diunduh: " + city, error);
            }
        }
        return cached;
    }
    // SUMBAJANG_PALU_PHOTO_V5: verified Wikimedia Commons photograph, cached locally on the STB.
    // Author: Nurdiansyah46. License: CC BY-SA 4.0. Source page is stored for attribution.
    private Bitmap loadPaluMosquePhotoV5() {
        File imageFile = new File(activity.getFilesDir(), "sumbajang-palu-baitul-khairaat-v5.webp");
        Bitmap cached = imageFile.exists() ? BitmapFactory.decodeFile(imageFile.getAbsolutePath()) : null;
        if (cached != null) return cached;

        String imageUrl = "https://upload.wikimedia.org/wikipedia/commons/6/66/"
                + "%28MASJID_BAITUL_KHAIRAAT%29.Masjid_Raya_Kota_Palu_yang_baru_diresmikan_Desember_lalu._Bentuk_bangu.webp";
        try {
            byte[] bytes = getBytes(imageUrl);
            Bitmap downloaded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (downloaded == null) return null;
            try (FileOutputStream out = new FileOutputStream(imageFile)) {
                out.write(bytes);
                out.flush();
            }
            prefs.edit().putString("photo_credit_palu_v5",
                    "Foto: Nurdiansyah46 Â· CC BY-SA 4.0\n"
                    + "https://commons.wikimedia.org/wiki/File:(MASJID_BAITUL_KHAIRAAT).Masjid_Raya_Kota_Palu_yang_baru_diresmikan_Desember_lalu._Bentuk_bangu.webp")
                    .apply();
            android.util.Log.i("SUMBAJANG", "Foto Masjid Raya Baitul Khairaat Palu berhasil dimuat dan di-cache.");
            return downloaded;
        } catch (Exception error) {
            android.util.Log.e("SUMBAJANG", "Foto Palu gagal diunduh; periksa koneksi STB.", error);
            return cached;
        }
    }
    private String metadataValue(JSONObject metadata, String field) {
        if (metadata == null) return "";
        JSONObject item = metadata.optJSONObject(field);
        if (item == null) return "";
        String value = item.optString("value", "");
        return value.replaceAll("<[^>]*>", " ").replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&").replaceAll("\\s+", " ").trim();
    }

    private void showWidgetMenu() {
        String[] actions = {"Perbarui jadwal & foto", "Pilih lokasi kabupaten/kota", "Sumber data & foto"};
        new AlertDialog.Builder(activity).setTitle("Sumbajang")
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) refresh(true);
                    else if (which == 1) loadRegionIndexAndShowPicker();
                    else showSources();
                }).show();
    }

    private void showSources() {
        String source = prefs.getString("last_source", "Kemenag RI");
        String photoKey = region == null ? safeKey(regionName) : safeKey(regionName + "_" + region.optString("province", ""));
        String photo = prefs.getString("photo_credit_" + photoKey, "Foto masjid belum berhasil dimuat");
        String locationSource = prefs.getString("last_location_method", "Belum terdeteksi");
        String selectedCity = prefs.getString("last_location_city", regionName.isEmpty() ? "Belum dipilih" : regionName);
        new AlertDialog.Builder(activity)
                .setTitle("Sumbajang · Lokasi & sumber")
                .setMessage("Wilayah: " + selectedCity + "\nMetode lokasi: " + locationSource +
                        "\n\nJadwal salat: " + source + " (disalurkan melalui Sajadah.org).\n\n" + photo)
                .setPositiveButton("Pilih lokasi", (dialog, which) -> loadRegionIndexAndShowPicker())
                .setNegativeButton("Tutup", null)
                .show();
    }

    private void loadRegionIndexAndShowPicker() {
        view.setRefreshing(true);
        executor.execute(() -> {
            try {
                JSONObject index = getJson(REGIONS_URL, "regions_json");
                main.post(() -> {
                    view.setRefreshing(false);
                    showProvincePicker(index);
                });
            } catch (Exception ex) {
                main.post(() -> {
                    view.setRefreshing(false);
                    new AlertDialog.Builder(activity).setTitle("Lokasi tidak tersedia")
                            .setMessage("Daftar kabupaten/kota tidak dapat diunduh. Periksa koneksi internet STB.")
                            .setPositiveButton("Tutup", null).show();
                });
            }
        });
    }

    private void showProvincePicker(JSONObject index) {
        JSONArray regions = index == null ? null : index.optJSONArray("regions");
        if (regions == null) return;
        TreeSet<String> provinceSet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < regions.length(); i++) {
            JSONObject item = regions.optJSONObject(i);
            if (item != null && "ID".equalsIgnoreCase(item.optString("country"))) {
                String province = item.optString("province", "").trim();
                if (!province.isEmpty()) provinceSet.add(province);
            }
        }
        List<String> provinces = new ArrayList<>(provinceSet);
        String[] labels = new String[provinces.size() + 1];
        labels[0] = "Deteksi otomatis";
        for (int i = 0; i < provinces.size(); i++) labels[i + 1] = provinces.get(i);
        new AlertDialog.Builder(activity).setTitle("Pilih provinsi")
                .setItems(labels, (dialog, which) -> {
                    if (which == 0) {
                        prefs.edit().remove("manual_region_id").apply();
                        refresh(true);
                    } else {
                        showCityPicker(index, provinces.get(which - 1));
                    }
                }).setNegativeButton("Batal", null).show();
    }

    private void showCityPicker(JSONObject index, String province) {
        JSONArray regions = index == null ? null : index.optJSONArray("regions");
        if (regions == null) return;
        List<JSONObject> cities = new ArrayList<>();
        for (int i = 0; i < regions.length(); i++) {
            JSONObject item = regions.optJSONObject(i);
            if (item != null && "ID".equalsIgnoreCase(item.optString("country"))
                    && province.equalsIgnoreCase(item.optString("province", ""))) cities.add(item);
        }
        Collections.sort(cities, Comparator.comparing((JSONObject o) -> o.optString("shortName", o.optString("name", "")), String.CASE_INSENSITIVE_ORDER));
        String[] labels = new String[cities.size()];
        for (int i = 0; i < cities.size(); i++) {
            JSONObject item = cities.get(i);
            String shortName = item.optString("shortName", item.optString("name", "Wilayah"));
            String fullName = item.optString("name", shortName);
            labels[i] = shortName + (shortName.equalsIgnoreCase(fullName) ? "" : "  ·  " + fullName);
        }
        new AlertDialog.Builder(activity).setTitle("Pilih kabupaten/kota · " + province)
                .setItems(labels, (dialog, which) -> {
                    JSONObject chosen = cities.get(which);
                    prefs.edit().putString("manual_region_id", chosen.optString("id", "")).apply();
                    refresh(true);
                }).setNeutralButton("Kembali", (dialog, which) -> showProvincePicker(index))
                .setNegativeButton("Batal", null).show();
    }

    private JSONObject getJsonFresh(String url) throws Exception {
        return new JSONObject(new String(getBytes(url), StandardCharsets.UTF_8));
    }

    private JSONObject getJson(String url, String cacheKey) throws Exception {
        try {
            byte[] bytes = getBytes(url);
            String body = new String(bytes, StandardCharsets.UTF_8);
            prefs.edit().putString(cacheKey, body).apply();
            return new JSONObject(body);
        } catch (Exception networkError) {
            String cached = prefs.getString(cacheKey, null);
            if (cached != null) return new JSONObject(cached);
            throw networkError;
        }
    }

    private byte[] getBytes(String address) throws Exception {
        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(9000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("User-Agent", "SumbajangWidget/1.0 (Android TV; prayer-times display)");
            connection.setRequestProperty("Accept", "application/json,image/*,*/*;q=0.8");
            connection.connect();
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + " for " + address);
            input = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        } finally {
            try { if (input != null) input.close(); } catch (Exception ignored) { }
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean isValidTime(String value) {
        if (value == null || !value.matches("\\d{2}:\\d{2}")) return false;
        try { LocalTime.parse(value); return true; } catch (Exception ex) { return false; }
    }

    private static String enc(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }
    private static String safeKey(String value) { return value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "_"); }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2d) * Math.sin(dLat / 2d) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2d) * Math.sin(dLon / 2d);
        return 6371d * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a));
    }

    private static final class LocationPoint {
        final double lat, lon;
        final String city, province, source;
        final float accuracyM;
        LocationPoint(double lat, double lon, String city, String province, String source, float accuracyM) {
            this.lat = lat; this.lon = lon; this.city = city == null ? "" : city;
            this.province = province == null ? "" : province;
            this.source = source == null ? "" : source;
            this.accuracyM = accuracyM;
        }
    }
}
