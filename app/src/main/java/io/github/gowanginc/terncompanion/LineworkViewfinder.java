package io.github.gowanginc.terncompanion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;

import com.journeyapps.barcodescanner.ViewfinderView;

/**
 * Viewfinder that keeps the decode window completely clear and draws an
 * original restrained linework construction entirely outside it: an ink scrim
 * over the surroundings, a fine green ledger rail offset around the window,
 * concentric quarter-arc track clusters hugging each corner, and a single
 * coral accent on the outer top-left arc.
 *
 * All paints and scratch rectangles are preallocated; onDraw performs no
 * allocation. The frame tracks the real framing rect across rotation via
 * refreshSizes() on each draw.
 */
public class LineworkViewfinder extends ViewfinderView {
    private static final float RAIL_GAP_DP = 10f;
    private static final float RAIL_STROKE_DP = 1.5f;
    private static final float RAIL_RADIUS_DP = 18f;
    private static final float ARC_RADIUS_DP = 24f;
    private static final float ARC_GAP_DP = 4.5f;
    private static final float ARC_STROKE_DP = 1f;
    private static final float ACCENT_STROKE_DP = 2.5f;

    private final Paint scrim = new Paint();
    private final Paint rail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final RectF track = new RectF();
    private float density;

    public LineworkViewfinder(Context context) {
        super(context, null);
        init(context);
    }

    public LineworkViewfinder(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        scrim.setColor(context.getColor(R.color.tern_ink_scrim));
        rail.setStyle(Paint.Style.STROKE);
        rail.setStrokeWidth(RAIL_STROKE_DP * density);
        rail.setColor(context.getColor(R.color.tern_green_ink));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(ARC_STROKE_DP * density);
        arc.setColor(context.getColor(R.color.tern_green_ink));
        accent.setStyle(Paint.Style.STROKE);
        accent.setStrokeWidth(ACCENT_STROKE_DP * density);
        accent.setColor(context.getColor(R.color.tern_coral));
    }

    @Override
    public void onDraw(Canvas canvas) {
        refreshSizes();
        Rect window = framingRect;
        if (window == null) {
            return; // Camera not sized yet; the ink layout background shows instead.
        }

        scrimOutside(canvas, window);
        drawRail(canvas, window);
        drawCornerTracks(canvas, window);
    }

    /** Four rects, no clipping: the window itself is never touched. */
    private void scrimOutside(Canvas canvas, Rect w) {
        int width = getWidth();
        int height = getHeight();
        canvas.drawRect(0f, 0f, width, w.top, scrim);
        canvas.drawRect(0f, w.bottom, width, height, scrim);
        canvas.drawRect(0f, w.top, w.left, w.bottom, scrim);
        canvas.drawRect(w.right, w.top, width, w.bottom, scrim);
    }

    private void drawRail(Canvas canvas, Rect w) {
        float gap = RAIL_GAP_DP * density;
        bounds.set(w.left - gap, w.top - gap, w.right + gap, w.bottom + gap);
        canvas.drawRoundRect(bounds, RAIL_RADIUS_DP * density, RAIL_RADIUS_DP * density, rail);
    }

    /** Concentric quarter arcs centred on each window corner, outside the rail. */
    private void drawCornerTracks(Canvas canvas, Rect w) {
        float radius = ARC_RADIUS_DP * density;
        float gap = ARC_GAP_DP * density;
        cornerArc(canvas, w.left, w.top, radius, gap, 180f, true);
        cornerArc(canvas, w.right, w.top, radius, gap, 270f, false);
        cornerArc(canvas, w.right, w.bottom, radius, gap, 0f, false);
        cornerArc(canvas, w.left, w.bottom, radius, gap, 90f, false);
    }

    private void cornerArc(Canvas canvas, float cx, float cy, float radius,
                           float gap, float start, boolean accentOuter) {
        track.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(track, start, 90f, false, accentOuter ? accent : arc);
        float inner = radius - gap;
        track.set(cx - inner, cy - inner, cx + inner, cy + inner);
        canvas.drawArc(track, start, 90f, false, arc);
    }
}
