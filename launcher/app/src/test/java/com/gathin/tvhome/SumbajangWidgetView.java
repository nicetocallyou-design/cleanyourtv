package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * SUMBAJANG — native Canvas widget renderer with premium weather-style prayer icons.
 * Visual target: the user's blue Salaam reference card (1200 x 520 design units).
 */
final class SumbajangWidgetView extends View {
    private static final float DESIGN_W = 1200f;
    private static final float DESIGN_H = 520f;

    static final int PRAYER_SUBUH = 0;
    static final int PRAYER_ZUHUR = 1;
    static final int PRAYER_ASHAR = 2;
    static final int PRAYER_MAGRIB = 3;
    static final int PRAYER_ISYA = 4;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF card = new RectF(0f, 0f, DESIGN_W, DESIGN_H);
    private final RectF rowRect = new RectF();
    private final Path path = new Path();
    private Bitmap mosqueBitmap;
    private Bitmap logoBitmap;
    private Runnable refreshAction;

    private String countdown = "--:--:--";
    private String nextPrayer = "Menunggu jadwal salat";
    private String locationDate = "Mendeteksi lokasi STB...";
    private String[] prayerTimes = {"--:--", "--:--", "--:--", "--:--", "--:--"};
    private int activePrayer = -1;
    private boolean refreshing;

    SumbajangWidgetView(Context context) {
        super(context);
        // SUMBAJANG_LOGO_LOCK_V15: load the verified, immutable-by-hash logo asset.
        // SUMBAJANG_LOGO_LOCK_V16: load the verified, immutable-by-hash logo asset.
        // SUMBAJANG_LOGO_LOCK_V17: load the verified, immutable-by-hash logo asset (V17).
        // SUMBAJANG_LOGO_LOCK_V18: load the verified, immutable-by-hash logo asset (V18).
        try (InputStream logoInput = getContext().getAssets().open("sumbajang_logo.png")) {
            logoBitmap = BitmapFactory.decodeStream(logoInput);
        } catch (IOException e) {
            logoBitmap = null;
            android.util.Log.e("SUMBAJANG", "Logo master V15 gagal dimuat; tidak memakai logo pengganti.", e);
        }
        // SUMBAJANG_V12_NON_INTERACTIVE: informational panel only; remote focus passes through to launcher controls.
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(false);
        setLongClickable(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setPrayerState(String countdownText, String nextText, String locationAndDate,
                        String[] times, int activeIndex) {
        countdown = countdownText == null ? "--:--:--" : countdownText;
        nextPrayer = nextText == null ? "Menunggu jadwal salat" : nextText;
        locationDate = locationAndDate == null ? "Lokasi belum tersedia" : locationAndDate;
        if (times != null && times.length == 5) prayerTimes = times.clone();
        activePrayer = activeIndex;
        postInvalidate();
    }

    void setRefreshing(boolean value) {
        refreshing = value;
        postInvalidate();
    }

    void setMosqueBitmap(Bitmap bitmap) {
        mosqueBitmap = bitmap;
        postInvalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        canvas.save();
        canvas.scale(getWidth() / DESIGN_W, getHeight() / DESIGN_H);

        float radius = 88f;
        canvas.save();
        Path clip = new Path();
        clip.addRoundRect(card, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);

        // Photo layer: deliberately subtle, tinted blue to keep white text readable.
        if (mosqueBitmap != null && !mosqueBitmap.isRecycled()) {
            paint.reset();
            paint.setAntiAlias(true);
            paint.setFilterBitmap(true);
            paint.setAlpha(238);
            RectF src = centerCrop(mosqueBitmap, card);
            canvas.drawBitmap(mosqueBitmap, null, src, paint);
        }

        paint.reset();
        paint.setAntiAlias(true);
        // SUMBAJANG_PHOTO_GLASS_V2: translucent blue keeps the real mosque photo visible.
        paint.setShader(new LinearGradient(0f, 0f, DESIGN_W, DESIGN_H,
                new int[]{0xC90055B8, 0xBD0068D1, 0xAA0077DC},
                new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(card, radius, radius, paint);
        paint.setShader(null);

        // A soft dark wash only on the text side; the right side keeps more of the mosque photograph.
        paint.setShader(new LinearGradient(0f, 0f, 590f, 0f,
                new int[]{0x86004FB5, 0x56046FD0, 0x000082EA}, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(card, radius, radius, paint);
        paint.setShader(null);

        drawBottomEqualizer(canvas);
        drawLogo(canvas);
        drawBrand(canvas);
        // SUMBAJANG_V12_NO_UPDATE_BUTTON: manual refresh UI removed; data refresh remains automatic.
        drawCountdown(canvas);
        drawPrayerRows(canvas);
        drawCardEdge(canvas);
        canvas.restore();
        canvas.restore();
    }

    private RectF centerCrop(Bitmap bitmap, RectF target) {
        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();
        float scale = Math.max(target.width() / bw, target.height() / bh);
        float w = bw * scale;
        float h = bh * scale;
        float left = target.centerX() - w / 2f;
        float top = target.centerY() - h / 2f;
        return new RectF(left, top, left + w, top + h);
    }

    private void drawLogo(Canvas c) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setAntiAlias(true);
        paint.setFilterBitmap(true);
        paint.setAlpha(255);
        if (logoBitmap != null && !logoBitmap.isRecycled()) {
            // SUMBAJANG_LOGO_LOCK_V18: exact master-derived emblem; do not redraw or substitute.
            // V16 locked emblem size retained; V18 aligns wordmark to its optical vertical midpoint.
            // Center at the locked emblem center remains Y=105.5; V18 centers the wordmark using font metrics.
            float maxWidth = 54f;
            float maxHeight = 82f;
            float scale = Math.min(maxWidth / logoBitmap.getWidth(),
                    maxHeight / logoBitmap.getHeight());
            float width = logoBitmap.getWidth() * scale;
            float height = logoBitmap.getHeight() * scale;
            float left = 85f - width / 2f;
            float top = 105.5f - height / 2f;
            RectF logoBounds = new RectF(left, top, left + width, top + height);
            c.drawBitmap(logoBitmap, null, logoBounds, paint);
        } else {
            // Intentionally no text/shape fallback: a missing master logo must never morph into another logo.
            android.util.Log.e("SUMBAJANG", "Logo terkunci V18 tidak tersedia; tidak menggunakan logo pengganti.");
        }
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
    }
    private void drawBrand(Canvas c) {
        // SUMBAJANG_BRAND_VERTICAL_CENTER_V18:
        // Compute the text baseline from font metrics so the wordmark center
        // exactly follows the locked logo's vertical center (105.5 design units).
        Typeface brandTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        float brandSize = 29f;
        Paint metricsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        metricsPaint.setTextSize(brandSize);
        metricsPaint.setTypeface(brandTypeface);
        Paint.FontMetrics metrics = metricsPaint.getFontMetrics();
        float logoCenterY = 105.5f;
        float centeredBaseline = logoCenterY - (metrics.ascent + metrics.descent) / 2f;
        drawText(c, "SUMBAJANG", 142f, centeredBaseline, brandSize, Color.WHITE,
                brandTypeface, Paint.Align.LEFT);
    }
    private void drawCountdown(Canvas c) {
        drawText(c, countdown, 70f, 276f, 79f, Color.WHITE,
                Typeface.create("Arial", Typeface.NORMAL), Paint.Align.LEFT);
        drawText(c, "Jam", 86f, 322f, 22f, 0xF0FFFFFF, Typeface.create("Arial", Typeface.NORMAL), Paint.Align.LEFT);
        drawText(c, "Menit", 190f, 322f, 22f, 0xF0FFFFFF, Typeface.create("Arial", Typeface.NORMAL), Paint.Align.LEFT);
        drawText(c, "Detik", 303f, 322f, 22f, 0xF0FFFFFF, Typeface.create("Arial", Typeface.NORMAL), Paint.Align.LEFT);
        drawText(c, nextPrayer, 61f, 410f, 39f, Color.WHITE,
                Typeface.create("Arial", Typeface.BOLD), Paint.Align.LEFT);
        drawText(c, locationDate, 61f, 465f, 29f, 0xE3EFFFFF,
                Typeface.create("Arial", Typeface.NORMAL), Paint.Align.LEFT);
    }

    private void drawPrayerRows(Canvas c) {
        // SUMBAJANG_REFERENCE_LAYOUT_V10: evenly spaced rows, aligned right edge, balanced outer padding.
        String[] names = {"SUBUH", "ZUHUR", "ASHAR", "MAGHRIB", "ISYA"};
        int[] iconColors = {0xFFFFC247, 0xFFFFD12D, 0xFFFFC078, 0xFFB48CFF, 0xFFB18AFF};
        float[] ys = {82f, 173f, 264f, 355f, 446f};
        for (int i = 0; i < 5; i++) {
            float cy = ys[i];
            if (i == activePrayer) {
                rowRect.set(620f, cy - 39f, 1142f, cy + 39f);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x22FFFFFF);
                c.drawRoundRect(rowRect, 42f, 42f, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3f);
                paint.setColor(0xBFFFFFFF);
                c.drawRoundRect(rowRect, 42f, 42f, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            drawPrayerIcon(c, i, 688f, cy, iconColors[i]);
            drawText(c, names[i], 747f, cy + 11f, 34f,
                    Color.WHITE, Typeface.create("Arial", i == activePrayer ? Typeface.BOLD : Typeface.NORMAL), Paint.Align.LEFT);
            drawText(c, prayerTimes[i], 1105f, cy + 11f, 33f,
                    Color.WHITE, Typeface.create("Arial", i == activePrayer ? Typeface.BOLD : Typeface.NORMAL), Paint.Align.RIGHT);
        }
    }

    private void drawPrayerIcon(Canvas c, int index, float x, float y, int color) {
        c.save();
                c.translate(x, y);
        // SUMBAJANG_PRAYER_ICON_SCALE_V11: 74% size; remains taller than prayer text.
        c.scale(0.74f, 0.74f);
        if (index == PRAYER_SUBUH) {
            drawGlow(c, 0f, -4f, 39f, 0x55FFB13D, 0x00FFB13D);
            drawSunDisc(c, 0f, 2f, 22f, 0xFFFFE18A, 0xFFFF9C36, 0xFFEE702C);
            drawCloud(c, 0f, 17f, 0.92f, 0xFFF7FBFF, 0xFFB7DFFF);
            drawTinySpark(c, -30f, -21f, 0xFFFFE5A3, 5f);
            drawTinySpark(c, 30f, -26f, 0xFFFFD27A, 3.5f);
        } else if (index == PRAYER_ZUHUR) {
            drawGlow(c, 0f, 0f, 42f, 0x66FFD34D, 0x00FFD34D);
            drawSunRays(c, 0f, 0f, 29f, 39f, 0xFFFFD75C, 3.3f);
            drawSunDisc(c, 0f, 0f, 23f, 0xFFFFF29B, 0xFFFFC52D, 0xFFFF8A24);
            paint.setShader(new LinearGradient(-12f, -15f, 13f, 18f,
                    new int[]{0x88FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            c.drawOval(-13f, -16f, 3f, -2f, paint);
            paint.setShader(null);
        } else if (index == PRAYER_ASHAR) {
            drawGlow(c, -2f, -7f, 39f, 0x55FF9B43, 0x00FF9B43);
            drawSunDisc(c, -3f, 0f, 20f, 0xFFFFDB8A, 0xFFFFA443, 0xFFE96A31);
            drawCloud(c, 3f, 18f, 0.78f, 0xFFFFF8EE, 0xFFFFC18A);
            drawTinySpark(c, 30f, -24f, 0xFFFFD69B, 4f);
        } else if (index == PRAYER_MAGRIB) {
            drawGlow(c, 0f, 1f, 42f, 0x668D6CFF, 0x008D6CFF);
            RectF sunset = new RectF(-22f, -25f, 22f, 20f);
            paint.setShader(new LinearGradient(-22f, -24f, 21f, 18f,
                    new int[]{0xFFFFD5AC, 0xFFFFA4C8, 0xFF9B6CF4}, null, Shader.TileMode.CLAMP));
            c.drawArc(sunset, 180f, 180f, true, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4.5f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setShader(new LinearGradient(-25f, 0f, 25f, 0f,
                    new int[]{0xFF7D56DD, 0xFFD6A7FF, 0xFF7D56DD}, null, Shader.TileMode.CLAMP));
            c.drawLine(-28f, 1f, 28f, 1f, paint);
            c.drawLine(-19f, 13f, 19f, 13f, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            drawCloud(c, 18f, 18f, 0.47f, 0xFFEADFFF, 0xFFA98AEF);
            drawTinySpark(c, -28f, -25f, 0xFFDCC7FF, 4.2f);
        } else {
            drawGlow(c, -1f, 0f, 42f, 0x668F6BFF, 0x008F6BFF);
            Path crescent = new Path();
            crescent.moveTo(13f, -24f);
            crescent.cubicTo(-13f, -28f, -29f, -4f, -19f, 17f);
            crescent.cubicTo(-10f, 37f, 17f, 34f, 27f, 12f);
            crescent.cubicTo(13f, 22f, -4f, 14f, -7f, 0f);
            crescent.cubicTo(-10f, -12f, -1f, -22f, 13f, -24f);
            crescent.close();
            paint.setShader(new LinearGradient(-18f, -25f, 20f, 30f,
                    new int[]{0xFFFFF5C8, 0xFFE5C7FF, 0xFFAA88FF}, null, Shader.TileMode.CLAMP));
            c.drawPath(crescent, paint);
            paint.setShader(null);
            drawTinySpark(c, -29f, -20f, 0xFFE9D9FF, 5f);
            drawTinySpark(c, 26f, 15f, 0xFFD5B8FF, 4f);
            drawTinySpark(c, 15f, -31f, 0xFFFFFFFF, 3f);
        }
        c.restore();
    }

    private void drawGlow(Canvas c, float x, float y, float radius, int inner, int outer) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(x, y, radius, new int[]{inner, outer}, null, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, radius, paint);
        paint.setShader(null);
    }

    private void drawSunDisc(Canvas c, float x, float y, float radius, int highlight, int mid, int edge) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(x - radius * 0.34f, y - radius * 0.42f, radius * 1.65f,
                new int[]{highlight, mid, edge}, new float[]{0f, 0.64f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, radius, paint);
        paint.setShader(null);
        paint.setColor(0x66FFFFFF);
        c.drawOval(x - radius * 0.55f, y - radius * 0.67f, x - radius * 0.03f, y - radius * 0.19f, paint);
    }

    private void drawSunRays(Canvas c, float x, float y, float inner, float outer, int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(color);
        for (int a = 0; a < 12; a++) {
            double angle = Math.PI * 2d * a / 12d;
            float x1 = x + (float)Math.cos(angle) * inner;
            float y1 = y + (float)Math.sin(angle) * inner;
            float x2 = x + (float)Math.cos(angle) * outer;
            float y2 = y + (float)Math.sin(angle) * outer;
            c.drawLine(x1, y1, x2, y2, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawCloud(Canvas c, float x, float y, float scale, int top, int bottom) {
        c.save();
        c.translate(x, y);
        c.scale(scale, scale);
        Path cloud = new Path();
        cloud.moveTo(-34f, 12f);
        cloud.cubicTo(-44f, 10f, -43f, -6f, -31f, -10f);
        cloud.cubicTo(-28f, -25f, -9f, -31f, 2f, -20f);
        cloud.cubicTo(12f, -35f, 34f, -26f, 35f, -8f);
        cloud.cubicTo(51f, -6f, 52f, 15f, 35f, 18f);
        cloud.lineTo(-30f, 18f);
        cloud.cubicTo(-33f, 18f, -35f, 15f, -34f, 12f);
        cloud.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0f, -28f, 0f, 20f,
                new int[]{top, bottom}, null, Shader.TileMode.CLAMP));
        c.drawPath(cloud, paint);
        paint.setShader(null);
        paint.setColor(0x55FFFFFF);
        c.drawOval(-21f, -20f, 3f, -8f, paint);
        c.restore();
    }

    private void drawTinySpark(Canvas c, float x, float y, int color, float radius) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawCircle(x, y, radius, paint);
        paint.setColor(0x99FFFFFF);
        c.drawCircle(x - radius * 0.28f, y - radius * 0.32f, radius * 0.32f, paint);
    }

    private void drawBottomEqualizer(Canvas c) {
        paint.setColor(0x1554C9FF);
        for (int x = 10; x < 570; x += 19) {
            float height = 15f + ((x * 17) % 67);
            c.drawRect(x, DESIGN_H - height, x + 5f, DESIGN_H, paint);
        }
        paint.setColor(0x0DFFFFFF);
        for (int x = 600; x < 1190; x += 24) {
            c.drawRect(x, 495f, x + 3f, 520f, paint);
        }
    }

    private void drawCardEdge(Canvas c) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(isFocused() ? 3.5f : 2f);
        paint.setColor(isFocused() ? 0xCFFFFFFF : 0x25FFFFFF);
        c.drawRoundRect(card, 88f, 88f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawText(Canvas canvas, String text, float x, float y, float size,
                          int color, Typeface typeface, Paint.Align align) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(size);
        paint.setTypeface(typeface);
        paint.setTextAlign(align);
        paint.setSubpixelText(true);
        canvas.drawText(text == null ? "" : text.toUpperCase(new Locale("id", "ID")), x, y, paint); // SUMBAJANG_UPPERCASE_V1
    }
}
