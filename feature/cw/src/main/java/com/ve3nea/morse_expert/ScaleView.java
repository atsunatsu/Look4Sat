package com.ve3nea.morse_expert;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/* loaded from: classes.dex */
public class ScaleView extends View {
    public int f;

    /* renamed from: g, reason: collision with root package name */
    public int f11041g;

    /* renamed from: h, reason: collision with root package name */
    public final Paint f11042h;

    /* renamed from: i, reason: collision with root package name */
    public final Paint f11043i;

    /* renamed from: j, reason: collision with root package name */
    public final Paint f11044j;

    /* renamed from: k, reason: collision with root package name */
    public final Paint f11045k;

    public ScaleView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        Paint paint = new Paint();
        this.f11042h = paint;
        paint.setColor(-16711936);
        Paint paint2 = new Paint();
        this.f11043i = paint2;
        paint2.setColor(-16744448);
        Paint paint3 = new Paint();
        this.f11044j = paint3;
        paint3.setColor(-256);
        Paint paint4 = new Paint();
        this.f11045k = paint4;
        paint4.setColor(-8355840);
    }

    @Override // android.view.View
    public final void onDraw(Canvas canvas) {
        Paint paint;
        super.onDraw(canvas);
        int i4 = this.f11041g;
        if (i4 != 1) {
            if (i4 != 2) {
                if (i4 != 3) {
                    if (i4 != 4) {
                        return;
                    } else {
                        paint = this.f11044j;
                    }
                } else {
                    paint = this.f11045k;
                }
            } else {
                paint = this.f11042h;
            }
        } else {
            paint = this.f11043i;
        }
        int round = (int) Math.round(getWidth() * 0.7d);
        Path path = new Path();
        path.moveTo(0.0f, this.f);
        float f = round;
        path.lineTo(f, this.f - round);
        path.lineTo(f, this.f + round);
        path.close();
        canvas.drawPath(path, paint);
    }
}
