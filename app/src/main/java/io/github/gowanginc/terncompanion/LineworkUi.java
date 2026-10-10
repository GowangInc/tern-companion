package io.github.gowanginc.terncompanion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Linework drawing helpers shared by the app's restrained geometric surfaces.
 *
 * Original corner "registration" detail in the style of fine printed plate
 * rules: each rounded corner carries a short double quarter-arc track and a
 * small diagonal spoke, confined to the corner squares so the card body stays
 * quiet. All paints and scratch rectangles are preallocated; draw() performs
 * no allocation.
 */
public final class LineworkUi {
    private static final float CORNER_RADIUS_DP = 14f;
    private static final float STROKE_WIDTH_DP = 1f;
    private static final float DETAIL_OFFSET_DP = 3.5f;
    private static final float DETAIL_GAP_DP = 3.5f;
    private static final float DETAIL_ALPHA = 0.45f;
    private static final float DIAGONAL = 0.70710678f;

    private LineworkUi() {}

    /** Rounded card background with subtle original registration detail in the corners only. */
    public static Drawable cardBackground(Context context, int fill, int stroke) {
        float density = context.getResources().getDisplayMetrics().density;
        return new CardDrawable(fill, stroke, density);
    }

    private static final class CardDrawable extends Drawable {
        private final int fill;
        private final int stroke;
        private final float density;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private final RectF arc = new RectF();
        private int alpha = 255;
        private ColorFilter filter;

        CardDrawable(int fill, int stroke, float density) {
            this.fill = fill;
            this.stroke = stroke;
            this.density = density;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            bounds.set(b);
            float corner = CORNER_RADIUS_DP * density;
            float strokeW = Math.max(density, STROKE_WIDTH_DP * density);
            float offset = DETAIL_OFFSET_DP * density;
            float gap = DETAIL_GAP_DP * density;

            paint.setStyle(Paint.Style.FILL);
            paint.setColorFilter(filter);
            paint.setColor(fill);
            paint.setAlpha(Color.alpha(fill) * alpha / 255);
            canvas.drawRoundRect(bounds, corner, corner, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(strokeW);
            paint.setColor(stroke);
            paint.setAlpha(Color.alpha(stroke) * alpha / 255);
            float inset = strokeW / 2f;
            canvas.drawRoundRect(bounds.left + inset, bounds.top + inset,
                    bounds.right - inset, bounds.bottom - inset, corner, corner, paint);

            drawCornerDetail(canvas, b, corner, strokeW, offset, gap);
        }

        /** Double quarter-arc track plus one diagonal registration spoke per corner. */
        private void drawCornerDetail(Canvas canvas, Rect b, float corner,
                                      float strokeW, float offset, float gap) {
            paint.setStrokeWidth(strokeW);
            paint.setColor(stroke);
            paint.setAlpha((int) (Color.alpha(stroke) * DETAIL_ALPHA) * alpha / 255);

            float track = corner - strokeW / 2f - offset;
            float inner = track - gap;
            if (inner <= 0f) return;
            float l = b.left + strokeW / 2f + offset;
            float t = b.top + strokeW / 2f + offset;
            float r = b.right - strokeW / 2f - offset;
            float bottom = b.bottom - strokeW / 2f - offset;

            cornerArcs(canvas, l + track, t + track, track, inner, 180f);
            cornerArcs(canvas, r - track, t + track, track, inner, 270f);
            cornerArcs(canvas, r - track, bottom - track, track, inner, 0f);
            cornerArcs(canvas, l + track, bottom - track, track, inner, 90f);
        }

        private void cornerArcs(Canvas canvas, float cx, float cy, float track,
                                float inner, float start) {
            arc.set(cx - track, cy - track, cx + track, cy + track);
            canvas.drawArc(arc, start, 90f, false, paint);
            arc.set(cx - inner, cy - inner, cx + inner, cy + inner);
            canvas.drawArc(arc, start, 90f, false, paint);
            canvas.drawLine(cx + inner * DIAGONAL, cy + inner * DIAGONAL,
                    cx + track * DIAGONAL, cy + track * DIAGONAL, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            if (this.alpha != alpha) {
                this.alpha = alpha;
                invalidateSelf();
            }
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            if (this.filter != colorFilter) {
                this.filter = colorFilter;
                invalidateSelf();
            }
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
