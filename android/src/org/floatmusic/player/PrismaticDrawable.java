package org.floatmusic.player;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** The static pearl/aurora finish shared with qml/PrismaticSurface.qml. */
final class PrismaticDrawable extends Drawable {
    private static final float[] WASH_STOPS = {0f, .34f, .72f, 1f};
    private static final float[] REFLECTION_STOPS = {0f, .48f, 1f};
    private static final float[] SPECTRUM_STOPS = {0f, .30f, .58f, 1f};

    private final boolean dark;
    private final boolean elevated;
    private final float radiusPx;
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint filterPaint = new Paint();
    private final RectF surface = new RectF();
    private final RectF insetBounds = new RectF();
    private final Path clip = new Path();
    private final Path outerEdge = new Path();
    private final Path spectrumEdge = new Path();
    private final Path outerGlass = new Path();
    private final Path innerGlass = new Path();
    private final Shader[] washes = new Shader[4];
    private final Shader[] reflections = new Shader[4];
    private Shader spectrum;
    private int alpha = 255;
    private ColorFilter colorFilter;

    /** Radius is in the receiving Canvas's units; density converts the finish's dp. */
    PrismaticDrawable(boolean dark, boolean elevated, float radiusPx, float density) {
        this.dark = dark;
        this.elevated = elevated;
        this.radiusPx = Math.max(0f, radiusPx);
        this.density = density > 0f ? density : 1f;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        surface.set(bounds);
        roundedPath(clip, 0f);
        roundedPath(outerEdge, dp(.5f));
        roundedPath(spectrumEdge, dp(2.15f));
        roundedPath(outerGlass, dp(1f));
        roundedPath(innerGlass, dp(3.9f));
        if (surface.isEmpty()) return;

        float width = surface.width();
        float height = surface.height();
        float reach;
        if (dark) {
            reach = elevated
                    ? Math.min(Math.min(width * .36f, height * .78f), dp(142f))
                    : Math.min(Math.min(width * .48f, height * .44f), dp(202f));
        } else {
            reach = elevated
                    ? Math.min(Math.min(width * .34f, height * .75f), dp(132f))
                    : Math.min(Math.min(width * .46f, height * .42f), dp(184f));
        }
        reach = Math.max(1f, reach);
        float strength = dark ? .27f : .31f;
        float secondary = strength * (dark ? .40f : .68f);
        washes[0] = wash(surface.left, surface.top, reach,
                dark ? 0x40DDF3 : 0x4CDAEF, strength);
        washes[1] = wash(surface.right, surface.top, reach * .82f,
                dark ? 0xA592F2 : 0xAD9DEF, secondary);
        washes[2] = wash(surface.left, surface.bottom, reach * .82f,
                dark ? 0x6BA9EF : 0x7BC2EF, secondary);
        washes[3] = wash(surface.right, surface.bottom, reach,
                dark ? 0xED82DC : 0xEE8FE1, dark ? .24f : strength);

        float edgeStrength = dark ? .80f : .60f;
        spectrum = new LinearGradient(surface.left, surface.top,
                surface.right, surface.bottom, new int[] {
                    tint(dark ? 0x40DDF3 : 0x4CDAF0, edgeStrength),
                    tint(0x82C1F1, edgeStrength * (dark ? .50f : .42f)),
                    tint(0xBFB0F2, edgeStrength * (dark ? .4375f : .30f)),
                    tint(dark ? 0xED82DC : 0xF090E3, edgeStrength)
                }, SPECTRUM_STOPS, Shader.TileMode.CLAMP);

        float reflectionStrength = dark ? .70f : .88f;
        float secondaryReflection = reflectionStrength * (dark ? .40f : .62f);
        reflections[0] = reflection(surface.left, surface.top, reach,
                0x6AEBF4, reflectionStrength);
        reflections[1] = reflection(surface.right, surface.bottom, reach,
                0xF7A5EB, reflectionStrength);
        reflections[2] = reflection(surface.right, surface.top, reach * .82f,
                0xB6B5F7, secondaryReflection);
        reflections[3] = reflection(surface.left, surface.bottom, reach * .82f,
                0x7ADAF4, secondaryReflection);
    }

    @Override
    public void draw(Canvas canvas) {
        if (surface.isEmpty() || alpha == 0) return;

        // Lower opacity composites the whole finish once; opaque surfaces can
        // draw directly without allocating an offscreen layer on each refresh.
        int saved = alpha == 255 ? canvas.save() : canvas.saveLayerAlpha(surface, alpha);
        canvas.clipPath(clip);
        if (colorFilter != null) canvas.saveLayer(surface, filterPaint);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(dark
                ? (elevated ? 0xFF252C43 : 0xFF191C2C)
                : (elevated ? 0xFFF9F9FC : 0xFFF5F5FA));
        canvas.drawRect(surface, paint);
        for (Shader wash : washes) {
            paint.setColor(Color.WHITE);
            paint.setShader(wash);
            canvas.drawRect(surface, paint);
        }

        stroke(canvas, outerEdge, dp(1f), null,
                tint(dark ? 0x8D95C1 : 0xA2AACC, dark ? .12f : .26f));
        stroke(canvas, spectrumEdge, dp(3f), spectrum, Color.WHITE);
        for (Shader reflection : reflections) {
            stroke(canvas, spectrumEdge, dp(1.7f), reflection, Color.WHITE);
        }
        stroke(canvas, outerGlass, dp(.9f), null,
                tint(dark ? 0xF2F4FF : 0xFFFFFF, dark ? .10f : .96f));
        stroke(canvas, innerGlass, dp(.7f), null,
                tint(dark ? 0xE8EDFF : 0xFFFFFF, dark ? .06f : .80f));
        canvas.restoreToCount(saved);
    }

    private void stroke(Canvas canvas, Path path, float width, Shader shader, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(color);
        paint.setShader(shader);
        canvas.drawPath(path, paint);
    }

    private void roundedPath(Path path, float inset) {
        path.reset();
        insetBounds.set(surface);
        insetBounds.inset(inset, inset);
        if (insetBounds.isEmpty()) return;
        float radius = Math.max(0f, Math.min(radiusPx - inset,
                Math.min(insetBounds.width(), insetBounds.height()) * .5f));
        path.addRoundRect(insetBounds, radius, radius, Path.Direction.CW);
    }

    private Shader wash(float x, float y, float reach, int color, float strength) {
        return new RadialGradient(x, y, reach, new int[] {
                tint(color, strength), tint(color, strength * .55f),
                tint(color, strength * .12f), tint(color, 0f)
        }, WASH_STOPS, Shader.TileMode.CLAMP);
    }

    private Shader reflection(float x, float y, float reach, int color, float strength) {
        return new RadialGradient(x, y, reach, new int[] {
                tint(color, strength), tint(color, strength * .55f), tint(color, 0f)
        }, REFLECTION_STOPS, Shader.TileMode.CLAMP);
    }

    private static int tint(int rgb, float opacity) {
        int alpha = Math.max(0, Math.min(255, Math.round(opacity * 255f)));
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }

    private float dp(float value) {
        return value * density;
    }

    @Override
    public void setAlpha(int alpha) {
        int next = Math.max(0, Math.min(255, alpha));
        if (this.alpha == next) return;
        this.alpha = next;
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        if (this.colorFilter == colorFilter) return;
        this.colorFilter = colorFilter;
        filterPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public ColorFilter getColorFilter() {
        return colorFilter;
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void getOutline(Outline outline) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            outline.setEmpty();
            return;
        }
        float radius = Math.min(radiusPx, Math.min(bounds.width(), bounds.height()) * .5f);
        outline.setRoundRect(bounds, radius);
        outline.setAlpha(alpha / 255f);
    }

    @Override
    public boolean getPadding(Rect padding) {
        padding.set(0, 0, 0, 0);
        return false;
    }
}
