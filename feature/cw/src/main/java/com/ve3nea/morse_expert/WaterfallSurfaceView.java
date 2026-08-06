package com.ve3nea.morse_expert;

import J2.b;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;

/* loaded from: classes.dex */
public class WaterfallSurfaceView extends GLSurfaceView {
    public final b f;

    public WaterfallSurfaceView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        setEGLContextClientVersion(2);
        b bVar = new b(context, H2.b.f603B);
        this.f = bVar;
        setRenderer(bVar);
    }
}
