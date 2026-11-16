package com.geoguard.landslide;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.LruCache;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class LiveMapView extends View {
    private static final int TILE_SIZE = 256;
    private static final int MIN_ZOOM = 3;
    private static final int MAX_ZOOM = 19;
    private static final double MAX_LATITUDE = 85.05112878;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final LruCache<String, Bitmap> TILE_CACHE = new LruCache<>(48);
    private final Set<String> pendingTiles = new HashSet<>();
    private final Set<String> failedTiles = ConcurrentHashMap.newKeySet();
    private final Set<String> visibleTiles = ConcurrentHashMap.newKeySet();
    private final ExecutorService tileLoader = Executors.newFixedThreadPool(2);
    private final ScaleGestureDetector scaleDetector;
    private double centerLatitude;
    private double centerLongitude;
    private double locationLatitude;
    private double locationLongitude;
    private float locationAccuracy;
    private int zoom = 15;
    private boolean hasLocation;
    private boolean hasCenter;
    private boolean followLocation;
    private boolean hasVisibleMapTile;
    private float lastTouchX;
    private float lastTouchY;
    private boolean dragging;
    private final RectF attributionBounds = new RectF();

    LiveMapView(Context context) {
        super(context);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (detector.getScaleFactor() > 1.12f) zoomIn();
                else if (detector.getScaleFactor() < 0.89f) zoomOut();
                return true;
            }
        });
        setBackgroundColor(Color.rgb(235, 239, 230));
        setContentDescription("Live OpenStreetMap view showing the current device or ESP32 GPS location");
    }

    void setLocation(double latitude, double longitude, float accuracyMeters) {
        if (!validCoordinates(latitude, longitude)) return;
        locationLatitude = latitude;
        locationLongitude = longitude;
        locationAccuracy = Float.isFinite(accuracyMeters) && accuracyMeters > 0
                ? Math.min(accuracyMeters, 5000) : 0;
        hasLocation = true;
        if (!hasCenter || followLocation) {
            centerLatitude = latitude;
            centerLongitude = longitude;
            hasCenter = true;
            followLocation = true;
        }
        invalidate();
    }

    void clearLocation() {
        hasLocation = false;
        followLocation = false;
        invalidate();
    }

    void centerOnLocation() {
        if (!hasLocation) return;
        followLocation = true;
        centerLatitude = locationLatitude;
        centerLongitude = locationLongitude;
        invalidate();
    }

    void zoomIn() {
        if (zoom < MAX_ZOOM) {
            zoom++;
            invalidate();
        }
    }

    void zoomOut() {
        if (zoom > MIN_ZOOM) {
            zoom--;
            invalidate();
        }
    }

    void retryFailedTiles() {
        failedTiles.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(235, 239, 230));
        if (getWidth() == 0 || getHeight() == 0) return;
        visibleTiles.clear();
        hasVisibleMapTile = false;
        if (hasLocation) {
            drawTiles(canvas);
            drawLocation(canvas);
        } else {
            drawMessage(canvas, "Waiting for a phone or ESP32 GPS fix");
        }
        drawAttribution(canvas);
        if (hasLocation && !hasVisibleMapTile && !visibleTiles.isEmpty()) {
            drawMessage(canvas, hasFailedVisibleTile()
                    ? "Map tiles unavailable · check your internet and tap Retry"
                    : "Loading map tiles…");
        }
        for (String tile : visibleTiles) requestTile(tile);
    }

    private void drawTiles(Canvas canvas) {
        double worldSize = TILE_SIZE * Math.pow(2, zoom);
        double centerX = longitudeToWorldX(centerLongitude, worldSize);
        double centerY = latitudeToWorldY(centerLatitude, worldSize);
        double leftWorld = centerX - getWidth() / 2.0;
        double topWorld = centerY - getHeight() / 2.0;
        int firstTileX = (int) Math.floor(leftWorld / TILE_SIZE);
        int lastTileX = (int) Math.floor((leftWorld + getWidth()) / TILE_SIZE);
        int firstTileY = (int) Math.floor(topWorld / TILE_SIZE);
        int lastTileY = (int) Math.floor((topWorld + getHeight()) / TILE_SIZE);
        int tileCount = 1 << zoom;
        for (int tileY = Math.max(0, firstTileY); tileY <= Math.min(tileCount - 1, lastTileY); tileY++) {
            for (int unwrappedX = firstTileX; unwrappedX <= lastTileX; unwrappedX++) {
                int tileX = Math.floorMod(unwrappedX, tileCount);
                String key = zoom + "/" + tileX + "/" + tileY;
                visibleTiles.add(key);
                Bitmap bitmap = TILE_CACHE.get(key);
                if (bitmap == null) continue;
                hasVisibleMapTile = true;
                float x = (float) (unwrappedX * TILE_SIZE - leftWorld);
                float y = (float) (tileY * TILE_SIZE - topWorld);
                canvas.drawBitmap(bitmap, null, new RectF(x, y, x + TILE_SIZE, y + TILE_SIZE), paint);
            }
        }
    }

    private boolean hasFailedVisibleTile() {
        for (String tile : visibleTiles) {
            if (failedTiles.contains(tile)) return true;
        }
        return false;
    }

    private void drawLocation(Canvas canvas) {
        double worldSize = TILE_SIZE * Math.pow(2, zoom);
        double centerX = longitudeToWorldX(centerLongitude, worldSize);
        double centerY = latitudeToWorldY(centerLatitude, worldSize);
        double pointX = longitudeToWorldX(locationLongitude, worldSize);
        double pointY = latitudeToWorldY(locationLatitude, worldSize);
        double deltaX = pointX - centerX;
        if (deltaX > worldSize / 2) deltaX -= worldSize;
        else if (deltaX < -worldSize / 2) deltaX += worldSize;
        float screenX = (float) (getWidth() / 2.0 + deltaX);
        float screenY = (float) (getHeight() / 2.0 + pointY - centerY);
        if (screenX < -20 || screenX > getWidth() + 20
                || screenY < -20 || screenY > getHeight() + 20) return;

        if (locationAccuracy > 0) {
            double metersPerPixel = Math.cos(Math.toRadians(locationLatitude))
                    * 2 * Math.PI * 6378137.0 / worldSize;
            float radius = (float) Math.min(getWidth() / 2.0,
                    Math.max(dp(7), locationAccuracy / Math.max(0.01, metersPerPixel)));
            markerPaint.setColor(0x332196f3);
            canvas.drawCircle(screenX, screenY, radius, markerPaint);
        }
        markerPaint.setColor(Color.WHITE);
        canvas.drawCircle(screenX, screenY, dp(11), markerPaint);
        markerPaint.setColor(0xff1677e8);
        canvas.drawCircle(screenX, screenY, dp(8), markerPaint);
        markerPaint.setColor(Color.WHITE);
        canvas.drawCircle(screenX, screenY, dp(2.5f), markerPaint);
    }

    private void drawMessage(Canvas canvas, String message) {
        markerPaint.setColor(0xddffffff);
        float padding = dp(12);
        float textSize = dp(13);
        markerPaint.setTextSize(textSize);
        float boxWidth = Math.min(getWidth() - dp(24), markerPaint.measureText(message) + padding * 2);
        float left = (getWidth() - boxWidth) / 2;
        float top = getHeight() / 2f - dp(22);
        markerPaint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(left, top, left + boxWidth, top + dp(44), dp(10), dp(10), markerPaint);
        markerPaint.setColor(0xff243747);
        canvas.drawText(message, left + padding, top + dp(27), markerPaint);
    }

    private void drawAttribution(Canvas canvas) {
        markerPaint.setTextSize(dp(9));
        markerPaint.setColor(0xddffffff);
        String attribution = "© OpenStreetMap contributors";
        float width = markerPaint.measureText(attribution);
        float left = getWidth() - width - dp(12);
        float top = getHeight() - dp(10);
        attributionBounds.set(left - dp(5), top - dp(13), getWidth() - dp(4),
                getHeight() - dp(2));
        canvas.drawRoundRect(attributionBounds, dp(3), dp(3), markerPaint);
        markerPaint.setColor(0xff34495a);
        canvas.drawText(attribution, left, top, markerPaint);
    }

    private void requestTile(String key) {
        if (TILE_CACHE.get(key) != null || failedTiles.contains(key)) return;
        synchronized (pendingTiles) {
            if (!pendingTiles.add(key)) return;
        }
        tileLoader.execute(() -> {
            Bitmap bitmap = null;
            HttpURLConnection connection = null;
            try {
                if (!visibleTiles.contains(key)) return;
                connection = (HttpURLConnection) new URL("https://tile.openstreetmap.org/" + key + ".png")
                        .openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("User-Agent",
                        "DrishtiSetu/1.6 (+https://github.com/atharvshish13/LandSlide_AI)");
                connection.setRequestProperty("Accept", "image/png");
                connection.setUseCaches(true);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("Map tile request returned HTTP " + connection.getResponseCode());
                }
                try (InputStream input = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(input);
                }
                if (bitmap == null) throw new IOException("The map tile could not be decoded.");
                TILE_CACHE.put(key, bitmap);
            } catch (IOException | RuntimeException error) {
                failedTiles.add(key);
                android.util.Log.w("DrishtiSetuMap", "Could not load map tile " + key, error);
            } finally {
                if (connection != null) connection.disconnect();
                synchronized (pendingTiles) {
                    pendingTiles.remove(key);
                }
                postInvalidate();
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
                    float dx = event.getX() - lastTouchX;
                    float dy = event.getY() - lastTouchY;
                    if (Math.abs(dx) + Math.abs(dy) > dp(2)) dragging = true;
                    if (dragging) {
                        moveCenterBy(dx, dy);
                        followLocation = false;
                    }
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                return true;
            case MotionEvent.ACTION_UP:
                performClick();
                if (!dragging && attributionBounds.contains(event.getX(), event.getY())) {
                    try {
                        getContext().startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://www.openstreetmap.org/copyright")));
                    } catch (android.content.ActivityNotFoundException error) {
                        android.util.Log.w("DrishtiSetuMap", "No browser can open the map attribution link.", error);
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        tileLoader.shutdownNow();
        super.onDetachedFromWindow();
    }

    private void moveCenterBy(float dx, float dy) {
        double worldSize = TILE_SIZE * Math.pow(2, zoom);
        double centerX = longitudeToWorldX(centerLongitude, worldSize) - dx;
        double centerY = latitudeToWorldY(centerLatitude, worldSize) - dy;
        centerLatitude = worldYToLatitude(centerY, worldSize);
        centerLongitude = worldXToLongitude(centerX, worldSize);
        invalidate();
    }

    private static double longitudeToWorldX(double longitude, double worldSize) {
        return (longitude + 180.0) / 360.0 * worldSize;
    }

    private static double latitudeToWorldY(double latitude, double worldSize) {
        double limited = Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, latitude));
        double sin = Math.sin(Math.toRadians(limited));
        return (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * worldSize;
    }

    private static double worldYToLatitude(double y, double worldSize) {
        double mercator = Math.PI * (1 - 2 * y / worldSize);
        return Math.toDegrees(Math.atan(Math.sinh(mercator)));
    }

    private static double worldXToLongitude(double x, double worldSize) {
        double wrappedX = (x % worldSize + worldSize) % worldSize;
        return wrappedX / worldSize * 360.0 - 180.0;
    }

    private static boolean validCoordinates(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
