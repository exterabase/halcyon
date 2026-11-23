package app.halcyon.runner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

final class OrbitView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ring = new RectF();
    private static final float[][] STARS = {
        {12, 21, 1}, {22, 132, 1}, {35, 39, 1.8f}, {53, 147, 1.4f},
        {72, 15, 1}, {116, 31, 1.2f}, {127, 124, 1.7f}, {149, 46, 1}, {147, 152, 1.2f}
    };

    OrbitView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth(), getHeight()) / 160f;
        canvas.save();
        canvas.translate(getWidth() - scale * 160, (getHeight() - scale * 160) / 2);
        canvas.scale(scale, scale);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(90, 78, 74, 0xFF7352D4, 0x007352D4, Shader.TileMode.CLAMP));
        canvas.drawCircle(90, 78, 74, paint);
        paint.setShader(null);
        for (float[] star : STARS) {
            paint.setColor(0xFFBAA7F4);
            canvas.drawCircle(star[0], star[1], star[2], paint);
        }

        ring.set(22, 69, 152, 102);
        canvas.save();
        canvas.rotate(-33, 87, 84);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setColor(0xFF8C71D2);
        canvas.drawOval(ring, paint);
        canvas.restore();

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(53, 49, 118, 126, new int[]{0xFFF1DAFF, 0xFF9C7CF2, 0xFF502C9A},
            new float[]{0f, .50f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(87, 84, 35, paint);
        paint.setShader(null);
        canvas.save();
        canvas.rotate(-33, 87, 84);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.5f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xFFD4C3FF);
        canvas.drawArc(ring, 12, 156, false, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(145, 92, 2.6f, paint);
        canvas.restore();
        canvas.restore();
    }
}
