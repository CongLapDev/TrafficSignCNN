package com.example.trafficsigncnn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Minimalist camera viewfinder overlay.
 *
 * Draws 4 corner "L-bracket" arcs (like a professional camera app):
 *  ┌──     ──┐
 *  │          │
 *
 *  │          │
 *  └──     ──┘
 *
 * No full border — only the corners are visible, giving a clean,
 * unobtrusive "aim here" affordance.
 */
public class ViewfinderView extends View {

    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint    = new Paint();

    /** Length of each corner arm in pixels (set at runtime) */
    private float cornerLen;
    /** Stroke width of corner arms */
    private float strokeW;
    /** Corner radius of the inner square */
    private float cornerRadius;

    public ViewfinderView(Context context) {
        super(context);
        init();
    }

    public ViewfinderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ViewfinderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        cornerLen    = 28 * density;   // 28 dp corner arm length
        strokeW      = 3  * density;   // 3 dp stroke

        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(strokeW);
        cornerPaint.setColor(Color.WHITE);
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);

        dimPaint.setStyle(Paint.Style.FILL);
        dimPaint.setColor(Color.parseColor("#55000000")); // subtle dim outside box
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        float density = getResources().getDisplayMetrics().density;

        // The square region that the viewfinder outlines
        float boxSize  = Math.min(w, h) * 0.72f;
        float left     = (w - boxSize) / 2f;
        float top      = (h - boxSize) / 2f;
        float right    = left + boxSize;
        float bottom   = top  + boxSize;

        cornerRadius = 16 * density;
        float cl = cornerLen;  // corner arm length

        // ── Draw corner brackets ──────────────────────────────

        // TOP-LEFT
        canvas.drawLine(left, top + cl, left, top + cornerRadius, cornerPaint);
        canvas.drawArc(new RectF(left, top, left + cornerRadius * 2, top + cornerRadius * 2),
                180, 90, false, cornerPaint);
        canvas.drawLine(left + cornerRadius, top, left + cl, top, cornerPaint);

        // TOP-RIGHT
        canvas.drawLine(right - cl, top, right - cornerRadius, top, cornerPaint);
        canvas.drawArc(new RectF(right - cornerRadius * 2, top, right, top + cornerRadius * 2),
                270, 90, false, cornerPaint);
        canvas.drawLine(right, top + cornerRadius, right, top + cl, cornerPaint);

        // BOTTOM-LEFT
        canvas.drawLine(left, bottom - cl, left, bottom - cornerRadius, cornerPaint);
        canvas.drawArc(new RectF(left, bottom - cornerRadius * 2, left + cornerRadius * 2, bottom),
                90, 90, false, cornerPaint);
        canvas.drawLine(left + cornerRadius, bottom, left + cl, bottom, cornerPaint);

        // BOTTOM-RIGHT
        canvas.drawLine(right - cl, bottom, right - cornerRadius, bottom, cornerPaint);
        canvas.drawArc(new RectF(right - cornerRadius * 2, bottom - cornerRadius * 2, right, bottom),
                0, 90, false, cornerPaint);
        canvas.drawLine(right, bottom - cornerRadius, right, bottom - cl, cornerPaint);
    }
}
