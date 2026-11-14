package jp.kohei.norisugoshi;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.GestureDetector;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class StationMapView extends View {
    public interface OnRadiiChangedListener {
        void onRadiiChanged(RadiusSettings.Values values);
    }
    public interface OnLocationPickedListener { void onPicked(double latitude, double longitude); }
    private OnLocationPickedListener locationPickedListener;
    public interface OnCircleTappedListener { void onCircleTapped(String id); }
    private OnCircleTappedListener circleTappedListener;
    private String focusedCircleId = "notify";
    private boolean circleVisible = true;
    private java.util.List<ActivationArea> referenceAreas = java.util.Collections.emptyList();
    private final GestureDetector pointGesture;

    private static final int TILE_SIZE = 256;
    private static final int MIN_ZOOM = 10;
    private static final int MAX_ZOOM = 18;
    private static final long RETRY_DELAY_MILLIS = 60_000L;
    private static final double EARTH_METERS_PER_PIXEL = 156_543.03392;

    private static final ExecutorService TILE_EXECUTOR = Executors.newFixedThreadPool(3);
    private static final Set<String> REQUESTED_TILES = Collections.newSetFromMap(
            new ConcurrentHashMap<>()
    );
    private static final ConcurrentHashMap<String, Long> FAILED_TILES =
            new ConcurrentHashMap<>();
    private static final LruCache<String, Bitmap> TILE_CACHE =
            new LruCache<String, Bitmap>(12 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return Math.max(1, value.getByteCount() / 1024);
                }
            };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final File tileCacheRoot;
    private final Context appContext;

    private double latitude;
    private double longitude;
    private String stationName = "";
    private boolean hasStationLocation;
    private boolean editable;
    private boolean singleCircle;
    private int singleCircleColor=Ui.BLUE;
    private String singleCircleLabel="通知する円";
    public void setSingleCircleColor(int color){singleCircleColor=color;invalidate();}
    public void setSingleCircleLabel(String label){singleCircleLabel=label;updateAccessibilityText();}
    private boolean areaSelection;
    public void setAreaSelectionMode(boolean enabled) { areaSelection=enabled; updateAccessibilityText(); }
    private int minimumZoom() { return singleCircle ? 3 : MIN_ZOOM; }

    public void setSingleCircleRadius(double meters) {
        singleCircle = true;
        setFocusedRadius(meters);
    }
    private boolean dragging;
    private int lastZoom = 13;
    private double lastMetersPerPixel = 10.0;
    private float focusPulse = 1.0f;
    private RadiusSettings.Ring focusedRing;
    private RadiusSettings.Values radii = RadiusSettings.normalized(
            500.0,
            1_500.0,
            5_000.0
    );
    private OnRadiiChangedListener radiiChangedListener;
    private ValueAnimator focusAnimator;
    private final ScaleGestureDetector scaleDetector;
    private double cameraLatitude;
    private double cameraLongitude;
    private double cameraZoom = 14.0;
    private float lastTouchX;
    private float lastTouchY;
    private boolean panning;
    private boolean cameraReady;
    private float stationScreenX;
    private float stationScreenY;
    private int tileFailures;
    private boolean networkTilesEnabled = true;

    public StationMapView(Context context) {
        super(context);
        networkTilesEnabled = !Boolean.getBoolean("norisugoshi.map.offlineTest");
        appContext = context.getApplicationContext();
        pointGesture = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent event) {
                String id = circleAtScreen(event.getX(), event.getY());
                if (id == null || circleTappedListener == null) return false;
                circleTappedListener.onCircleTapped(id);
                return true;
            }
            @Override public void onLongPress(MotionEvent event) {
                if (areaSelection || locationPickedListener == null) return;
                double size = TILE_SIZE * Math.pow(2.0, lastZoom);
                double scale = tileScale(lastZoom);
                double x = worldX(cameraLongitude, size) + (event.getX() - getWidth() / 2.0) / scale;
                double y = worldY(cameraLatitude, size) + (event.getY() - getHeight() / 2.0) / scale;
                locationPickedListener.onPicked(Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / size)))),
                        normalizeLongitude(x / size * 360 - 180));
                dragging = false;
                panning = false;
            }
        });
        tileCacheRoot = new File(appContext.getCacheDir(), "station_map_tiles");
        setClickable(true);
        setFocusable(true);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if(areaSelection){
                            setFocusedRadius(getFocusedRadius()/detector.getScaleFactor());fitCircle();
                            return true;
                        }
                        cameraZoom = Math.max(minimumZoom(), Math.min(MAX_ZOOM,
                                cameraZoom + Math.log(detector.getScaleFactor()) / Math.log(2.0)));
                        invalidate();
                        return true;
                    }
                });
    }

    public void setStation(
            double latitude,
            double longitude,
            String stationName,
            boolean hasStationLocation
    ) {
        this.latitude = Math.max(-85.0, Math.min(85.0, latitude));
        this.longitude = normalizeLongitude(longitude);
        this.stationName = stationName == null ? "" : stationName;
        this.hasStationLocation = hasStationLocation;
        cameraReady = false;
        fitCircle();
        updateAccessibilityText();
        invalidate();
    }

    public void setRadii(double alertMeters, double rearmMeters, double monitorMeters) {
        radii = RadiusSettings.normalized(alertMeters, rearmMeters, monitorMeters);
        updateAccessibilityText();
        invalidate();
    }

    public RadiusSettings.Values getRadii() {
        return radii;
    }

    public void setEditable(boolean editable) {
        this.editable = editable;
        invalidate();
    }

    void disableNetworkTilesForTesting() {
        networkTilesEnabled = false;
    }

    public void setFocusedRing(RadiusSettings.Ring ring) {
        focusedRing = ring;
        fitCircle();
        startFocusAnimation();
        updateAccessibilityText();
        invalidate();
    }

    public RadiusSettings.Ring getFocusedRing() {
        return focusedRing;
    }

    public void setOnRadiiChangedListener(OnRadiiChangedListener listener) {
        radiiChangedListener = listener;
    }

    public void setOnLocationPickedListener(OnLocationPickedListener listener) { locationPickedListener = listener; }
    public void setOnCircleTappedListener(OnCircleTappedListener listener) { circleTappedListener = listener; }
    public void setFocusedCircleId(String id) { focusedCircleId = id; }
    public void setCircleVisible(boolean visible) { circleVisible = visible; invalidate(); }

    String circleAtScreen(float x, float y) {
        double size = TILE_SIZE * Math.pow(2.0, lastZoom);
        double scale = tileScale(lastZoom);
        double wx = worldX(cameraLongitude, size) + (x - getWidth() / 2.0) / scale;
        double wy = worldY(cameraLatitude, size) + (y - getHeight() / 2.0) / scale;
        double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * wy / size))));
        double lon = normalizeLongitude(wx / size * 360 - 180);
        return circleAtPoint(lat, lon);
    }

    String circleAtPoint(double lat, double lon) {
        String id = null;
        double score = Double.MAX_VALUE;
        if (circleVisible && hasStationLocation) {
            double distance = Geo.distanceMeters(lat, lon, latitude, longitude);
            if (distance <= getFocusedRadius() + lastMetersPerPixel * dp(12)) {
                id = focusedCircleId; score = distance / Math.max(1, getFocusedRadius());
            }
        }
        for (ActivationArea area : referenceAreas) {
            double distance = Geo.distanceMeters(lat, lon, area.latitude, area.longitude);
            if (distance <= area.radiusMeters + lastMetersPerPixel * dp(12)
                    && distance / area.radiusMeters < score) {
                id = area.id; score = distance / area.radiusMeters;
            }
        }
        return id;
    }
    public double getCenterLatitude() { return cameraReady ? cameraLatitude : latitude; }
    public double getCenterLongitude() { return cameraReady ? cameraLongitude : longitude; }
    public void setReferenceAreas(java.util.List<ActivationArea> areas) {
        referenceAreas = new java.util.ArrayList<>(areas);
        invalidate();
    }

    public double getFocusedMinimum() {
        if (focusedRing == null) {
            return 0.0;
        }
        return singleCircle ? ReturnArea.MIN_RADIUS : RadiusSettings.minimumFor(radii, focusedRing);
    }

    public double getFocusedMaximum() {
        if (focusedRing == null) {
            return 0.0;
        }
        return singleCircle ? ReturnArea.MAX_RADIUS : RadiusSettings.maximumFor(radii, focusedRing);
    }

    public double getFocusedRadius() {
        return focusedRing == null ? 0.0 : radii.forRing(focusedRing);
    }

    public void setFocusedRadius(double meters) {
        if (focusedRing == null) {
            return;
        }
        RadiusSettings.Values next = singleCircle
                ? new RadiusSettings.Values(Math.max(ReturnArea.MIN_RADIUS, Math.min(ReturnArea.MAX_RADIUS, meters)), ReturnArea.MAX_RADIUS+1, ReturnArea.MAX_RADIUS+2)
                : RadiusSettings.withChangedRadius(
                radii,
                focusedRing,
                meters
        );
        if (sameValues(radii, next)) {
            return;
        }
        radii = next;
        updateAccessibilityText();
        invalidate();
        if (radiiChangedListener != null) {
            radiiChangedListener.onRadiiChanged(radii);
        }
    }

    public void beginRadiusGesture() {
        if (focusAnimator != null) {
            focusAnimator.cancel();
        }
        focusPulse = 1.0f;
    }

    public void endRadiusGesture() {
        startFocusAnimation();
        invalidate();
    }

    public void fitCircle() {
        cameraLatitude = latitude;
        cameraLongitude = longitude;
        if (getWidth() > 0 && getHeight() > 0) {
            double meters = getFocusedRadius() > 0.0 ? getFocusedRadius() : radii.alertMeters;
            if(singleCircle){
                // A geographic circle stretches on Mercator, especially at regional scales.
                double extent=0;
                for(int bearing=0;bearing<360;bearing+=5){
                    double[] point=Geo.destination(latitude,longitude,meters,bearing);
                    double x=wrappedWorldDelta(point[1],longitude,256);
                    double y=worldY(Math.max(-85,Math.min(85,point[0])),256)-worldY(latitude,256);
                    extent=Math.max(extent,Math.max(Math.abs(x),Math.abs(y)));
                }
                double density=getResources().getDisplayMetrics().density;
                cameraZoom=Math.max(minimumZoom(),Math.min(MAX_ZOOM,
                        Math.log(Math.min(getWidth(),getHeight())*.34/(Math.max(.000001,extent)*density))/Math.log(2)));
                cameraReady=true;invalidate();return;
            }
            double pixels = Math.min(getWidth(), getHeight()) * 0.34;
            double density = getResources().getDisplayMetrics().density;
            cameraZoom = Math.max(minimumZoom(), Math.min(MAX_ZOOM,
                    Math.log(EARTH_METERS_PER_PIXEL * Math.cos(Math.toRadians(latitude))
                            * pixels / (meters * density)) / Math.log(2.0)));
            cameraReady = true;
        }
        invalidate();
    }

    public void zoomBy(int delta) {
        if(areaSelection){setFocusedRadius(getFocusedRadius()/Math.pow(2,delta));fitCircle();return;}
        cameraZoom = Math.max(minimumZoom(), Math.min(MAX_ZOOM, cameraZoom + delta));
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        fitCircle();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(DesignTokens.Color.MAP_BASE);

        if (!hasStationLocation || getWidth() <= 0 || getHeight() <= 0) {
            drawEmptyStation(canvas);
            return;
        }

        if (!cameraReady) {
            fitCircle();
        }
        int zoom = Math.max(minimumZoom(), Math.min(MAX_ZOOM, (int) Math.floor(cameraZoom)));
        lastZoom = zoom;
        double scale = tileScale(zoom);
        lastMetersPerPixel = metersPerPixel(zoom) / scale;
        double worldSize = TILE_SIZE * Math.pow(2.0, zoom);
        stationScreenX = (float) (getWidth() / 2.0
                + (worldX(longitude, worldSize) - worldX(cameraLongitude, worldSize)) * scale);
        stationScreenY = (float) (getHeight() / 2.0
                + (worldY(latitude, worldSize) - worldY(cameraLatitude, worldSize)) * scale);
        int drawnTiles = drawTiles(canvas, zoom);
        drawRings(canvas, lastMetersPerPixel);
        if(!areaSelection)drawStationMarker(canvas);
        if (drawnTiles > 0) {
            drawAttribution(canvas);
        } else {
            textPaint.setTypeface(android.graphics.Typeface.DEFAULT);
            textPaint.setColor(Ui.MUTED);
            textPaint.setTextSize(sp(13));
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(tileFailures == 0 ? "地図を読み込み中" : "地図を読み込めません",
                    getWidth() / 2.0f, dp(100), textPaint);
        }
    }

    private int drawTiles(Canvas canvas, int zoom) {
        double worldSize = TILE_SIZE * Math.pow(2.0, zoom);
        double scale = tileScale(zoom);
        double centerWorldX = worldX(cameraLongitude, worldSize);
        double centerWorldY = worldY(cameraLatitude, worldSize);
        double left = centerWorldX - getWidth() / (2.0 * scale);
        double top = centerWorldY - getHeight() / (2.0 * scale);
        int firstTileX = (int) Math.floor(left / TILE_SIZE);
        int lastTileX = (int) Math.floor((left + getWidth() / scale) / TILE_SIZE);
        int firstTileY = (int) Math.floor(top / TILE_SIZE);
        int lastTileY = (int) Math.floor((top + getHeight() / scale) / TILE_SIZE);
        int tileCount = 1 << zoom;
        int drawn = 0;

        for (int rawY = firstTileY; rawY <= lastTileY; rawY++) {
            if (rawY < 0 || rawY >= tileCount) {
                continue;
            }
            for (int rawX = firstTileX; rawX <= lastTileX; rawX++) {
                int tileX = ((rawX % tileCount) + tileCount) % tileCount;
                String key = zoom + "/" + tileX + "/" + rawY;
                Bitmap bitmap;
                synchronized (TILE_CACHE) {
                    bitmap = TILE_CACHE.get(key);
                }
                if (bitmap == null) {
                    requestTile(key, zoom, tileX, rawY);
                    continue;
                }
                float drawX = (float) ((rawX * TILE_SIZE - left) * scale);
                float drawY = (float) ((rawY * TILE_SIZE - top) * scale);
                float tilePixels = (float) (TILE_SIZE * scale);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.WHITE);
                paint.setFilterBitmap(true);
                canvas.drawBitmap(
                        bitmap,
                        null,
                        new RectF(drawX, drawY, drawX + tilePixels, drawY + tilePixels),
                        paint
                );
                drawn++;
            }
        }
        return drawn;
    }

    private void requestTile(String key, int zoom, int x, int y) {
        if (!networkTilesEnabled) {
            return;
        }
        Long failedAt = FAILED_TILES.get(key);
        if (failedAt != null
                && System.currentTimeMillis() - failedAt < RETRY_DELAY_MILLIS) {
            return;
        }
        if (!REQUESTED_TILES.add(key)) {
            return;
        }

        TILE_EXECUTOR.execute(() -> {
            try {
                Bitmap bitmap = loadTile(zoom, x, y);
                if (bitmap != null) {
                    synchronized (TILE_CACHE) {
                        TILE_CACHE.put(key, bitmap);
                    }
                    FAILED_TILES.remove(key);
                    postInvalidate();
                } else {
                    FAILED_TILES.put(key, System.currentTimeMillis());
                    post(() -> {
                        tileFailures++;
                        invalidate();
                    });
                }
            } finally {
                REQUESTED_TILES.remove(key);
            }
        });
    }

    private Bitmap loadTile(int zoom, int x, int y) {
        File tileFile = new File(tileCacheRoot, zoom + "/" + x + "/" + y + ".png");
        Bitmap cached = BitmapFactory.decodeFile(tileFile.getAbsolutePath());
        if (cached != null) {
            return cached;
        }

        File parent = tileFile.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            return null;
        }

        File temporary = new File(tileFile.getAbsolutePath() + ".part");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                    "https://tile.openstreetmap.org/" + zoom + "/" + x + "/" + y + ".png"
            ).openConnection();
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(8_000);
            connection.setRequestProperty(
                    "User-Agent",
                    "Noriori/"+BuildConfig.VERSION_NAME+" (Android; station and region map editor)"
            );
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }

            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8_192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            }
            if (!temporary.renameTo(tileFile)) {
                return BitmapFactory.decodeFile(temporary.getAbsolutePath());
            }
            return BitmapFactory.decodeFile(tileFile.getAbsolutePath());
        } catch (IOException ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (temporary.exists() && tileFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
        }
    }


    private void drawEmptyStation(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(getWidth() / 2.0f, getHeight() / 2.0f, dp(26), paint);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(sp(22));
        textPaint.setColor(getContext().getColor(R.color.brand_primary));
        textPaint.setTextSize(sp(15));
        canvas.drawText("駅を検索", getWidth() / 2.0f, getHeight() / 2.0f + dp(5), textPaint);
    }

    private void drawRings(Canvas canvas, double metersPerPixel) {
        double size = TILE_SIZE * Math.pow(2.0, lastZoom);
        double scale = tileScale(lastZoom);
        for (ActivationArea area : referenceAreas) {
            float x = (float) (getWidth() / 2.0 + (worldX(area.longitude, size) - worldX(cameraLongitude, size)) * scale);
            float y = (float) (getHeight() / 2.0 + (worldY(area.latitude, size) - worldY(cameraLatitude, size)) * scale);
            float radius = (float) (area.radiusMeters / metersPerPixel);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(Ui.MUTED, 18));
            canvas.drawCircle(x, y, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(withAlpha(Ui.MUTED, 160));
            canvas.drawCircle(x, y, radius, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, y, dp(5), paint);
        }
        RadiusSettings.Ring ring = focusedRing == null ? RadiusSettings.Ring.ALERT : focusedRing;
        if (circleVisible) {
            if(singleCircle)drawGeographicCircle(canvas);else drawRing(canvas, ring, radii.forRing(ring), metersPerPixel);
        }
    }
    private static double wrappedWorldDelta(double lon,double center,double size){
        double delta=worldX(lon,size)-worldX(center,size);
        if(delta>size/2)delta-=size;if(delta < -size/2)delta+=size;return delta;
    }
    private void drawGeographicCircle(Canvas canvas){
        double size=TILE_SIZE*Math.pow(2,lastZoom),scale=tileScale(lastZoom);
        Path path=new Path();
        for(int bearing=0;bearing<360;bearing+=3){
            double[] point=Geo.destination(latitude,longitude,getFocusedRadius(),bearing);
            float x=(float)(getWidth()/2.0+wrappedWorldDelta(point[1],cameraLongitude,size)*scale);
            float y=(float)(getHeight()/2.0+(worldY(Math.max(-85,Math.min(85,point[0])),size)-worldY(cameraLatitude,size))*scale);
            if(bearing==0)path.moveTo(x,y);else path.lineTo(x,y);
        }
        path.close();
        paint.setStyle(Paint.Style.FILL);paint.setColor(withAlpha(singleCircleColor,40));canvas.drawPath(path,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setColor(singleCircleColor);paint.setStrokeWidth(dp(3));canvas.drawPath(path,paint);
    }

    private void drawRing(
            Canvas canvas,
            RadiusSettings.Ring ring,
            double meters,
            double metersPerPixel
    ) {
        boolean focused = ring == focusedRing;
        float radius = (float) (meters / metersPerPixel);
        if (radius > Math.max(getWidth(), getHeight()) * 3.0f) {
            return;
        }

        int color = Ui.BLUE;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(color, focused ? Math.round(40 * focusPulse) : 24));
        canvas.drawCircle(stationScreenX, stationScreenY, radius, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(focused ? 3 : 2));
        paint.setColor(withAlpha(color, focused ? 255 : 190));
        canvas.drawCircle(stationScreenX, stationScreenY, radius, paint);

        if (editable && focused) {
            float handleX = stationScreenX + radius;
            float handleY = stationScreenY;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setShadowLayer(dp(3), 0f, dp(1), 0x55000000);
            canvas.drawCircle(handleX, handleY, dp(11), paint);
            paint.clearShadowLayer();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            paint.setColor(color);
            canvas.drawCircle(handleX, handleY, dp(11), paint);
        }
    }

    private void drawStationMarker(Canvas canvas) {
        textPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        float centerX = stationScreenX;
        float centerY = stationScreenY;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setShadowLayer(dp(4), 0f, dp(2), 0x66000000);
        canvas.drawCircle(centerX, centerY, dp(16), paint);
        paint.clearShadowLayer();
        paint.setColor(getContext().getColor(R.color.text_primary));
        canvas.drawCircle(centerX, centerY, dp(6), paint);
        if (!stationName.isEmpty()) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(sp(14));
            float width = textPaint.measureText(stationName);
            paint.setColor(Color.WHITE);
            canvas.drawRoundRect(centerX - width / 2.0f - dp(12), centerY + dp(22),
                    centerX + width / 2.0f + dp(12), centerY + dp(52), dp(15), dp(15), paint);
            textPaint.setColor(Ui.INK);
            canvas.drawText(stationName, centerX, centerY + dp(42), textPaint);
        }
    }

    private void drawAttribution(Canvas canvas) {
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setTextSize(sp(10));
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT);
        textPaint.setColor(Ui.INK);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xEEFFFFFF);
        canvas.drawRect(getWidth() - dp(170), getHeight() - dp(19), getWidth(), getHeight(), paint);
        canvas.drawText(
                "© OpenStreetMap contributors",
                getWidth() - dp(5),
                getHeight() - dp(5),
                textPaint
        );
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!hasStationLocation) {
            return super.onTouchEvent(event);
        }
        pointGesture.onTouchEvent(event);
        scaleDetector.onTouchEvent(event);
        if (event.getPointerCount() > 1) {
            dragging = false;
            panning = false;
            return true;
        }
        float dx = event.getX() - stationScreenX;
        float dy = event.getY() - stationScreenY;
        float touchRadius = (float) Math.hypot(dx, dy);
        float ringRadius = (float) (getFocusedRadius() / lastMetersPerPixel);
        float handleX = stationScreenX + ringRadius;
        float handleDistance = (float) Math.hypot(
                event.getX() - handleX,
                event.getY() - stationScreenY
        );

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = !areaSelection && editable && circleVisible && focusedRing != null
                        && (handleDistance <= dp(28)
                        || Math.abs(touchRadius - ringRadius) <= dp(16));
                panning = !dragging;
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                beginRadiusGesture();
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    setFocusedRadius(touchRadius * lastMetersPerPixel);
                } else if (panning && !scaleDetector.isInProgress()) {
                    double worldSize = TILE_SIZE * Math.pow(2.0, lastZoom);
                    double scale = tileScale(lastZoom);
                    double x = worldX(cameraLongitude, worldSize)
                            - (event.getX() - lastTouchX) / scale;
                    double y = worldY(cameraLatitude, worldSize)
                            - (event.getY() - lastTouchY) / scale;
                    cameraLongitude = normalizeLongitude(x / worldSize * 360.0 - 180.0);
                    cameraLatitude = Math.max(-85.0, Math.min(85.0,
                            Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / worldSize))))));
                    if(areaSelection){latitude=cameraLatitude;longitude=cameraLongitude;
                        if(locationPickedListener!=null)locationPickedListener.onPicked(latitude,longitude);}
                    invalidate();
                }
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                panning = false;
                endRadiusGesture();
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    performClick();
                }
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (focusAnimator != null) {
            focusAnimator.cancel();
        }
        super.onDetachedFromWindow();
    }


    private double metersPerPixel(int zoom) {
        return EARTH_METERS_PER_PIXEL
                * Math.max(0.2, Math.cos(Math.toRadians(latitude)))
                / Math.pow(2.0, zoom);
    }

    private double tileScale(int zoom) {
        return getResources().getDisplayMetrics().density * Math.pow(2.0, cameraZoom - zoom);
    }

    private static double worldX(double longitude, double worldSize) {
        return (longitude + 180.0) / 360.0 * worldSize;
    }

    private static double worldY(double latitude, double worldSize) {
        double sinLatitude = Math.sin(Math.toRadians(latitude));
        return (0.5 - Math.log((1.0 + sinLatitude) / (1.0 - sinLatitude))
                / (4.0 * Math.PI)) * worldSize;
    }

    private void startFocusAnimation() {
        if (focusAnimator != null) {
            focusAnimator.cancel();
        }
        focusPulse = 1f;
        if (!DesignSystem.animationsEnabled()) return;
        focusAnimator = ValueAnimator.ofFloat(0.65f, 1.0f);
        focusAnimator.setDuration(DesignTokens.Motion.MEDIUM);
        focusAnimator.setInterpolator(new DecelerateInterpolator());
        focusAnimator.addUpdateListener(animation -> {
            focusPulse = (float) animation.getAnimatedValue();
            invalidate();
        });
        focusAnimator.start();
    }

    private void updateAccessibilityText() {
        if(areaSelection){setContentDescription(singleCircleLabel+"。地図を動かして移動、ピンチで広さを変更");return;}
        if (!hasStationLocation) {
            setContentDescription("駅を検索");
            return;
        }
        String target = stationName.isEmpty() ? "駅" : stationName;
        if (focusedRing == null) {
            setContentDescription(target + "の通知範囲");
        } else {
            setContentDescription(target + "、" + (singleCircle?singleCircleLabel:accessibilityName(focusedRing)) + "を調整");
        }
    }


    private String accessibilityName(RadiusSettings.Ring ring) {
        switch (ring) {
            case ALERT:
                return "通知する円";
            case REARM:
                return "次の通知を準備する円";
            case MONITOR:
                return "見守りを始める円";
            default:
                throw new IllegalArgumentException("Unknown ring: " + ring);
        }
    }

    private static boolean sameValues(
            RadiusSettings.Values left,
            RadiusSettings.Values right
    ) {
        return left.alertMeters == right.alertMeters
                && left.rearmMeters == right.rearmMeters
                && left.monitorMeters == right.monitorMeters;
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static double normalizeLongitude(double value) {
        double normalized = value;
        while (normalized < -180.0) {
            normalized += 360.0;
        }
        while (normalized > 180.0) {
            normalized -= 360.0;
        }
        return normalized;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float sp(int value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                value,
                getResources().getDisplayMetrics()
        );
    }
}
