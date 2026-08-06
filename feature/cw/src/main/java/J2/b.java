package J2;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.SystemClock;
import android.util.Log;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/* loaded from: classes.dex */
public final class b implements GLSurfaceView.Renderer {

    /* renamed from: b, reason: collision with root package name */
    public FloatBuffer f729b;
    public int c;

    /* renamed from: d, reason: collision with root package name */
    public final Bitmap f730d;
    public final Context f;

    /* renamed from: g, reason: collision with root package name */
    public double f732g;

    /* renamed from: h, reason: collision with root package name */
    public final Bitmap f733h;

    /* renamed from: i, reason: collision with root package name */
    public final a f734i;

    /* renamed from: j, reason: collision with root package name */
    public long f735j;

    /* renamed from: k, reason: collision with root package name */
    public double f736k;

    /* renamed from: l, reason: collision with root package name */
    public int f737l;

    /* renamed from: a, reason: collision with root package name */
    public final int[] f728a = new int[1];

    /* renamed from: e, reason: collision with root package name */
    public final float[] f731e = {-1.0f, -1.0f, 0.0f, 1.0f, -1.0f, 1.0f, 0.0f, 0.0f, 1.0f, -1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f};

    public b(Context context, int i4) {
        Bitmap.Config config = Bitmap.Config.ARGB_8888;
        Bitmap createBitmap = Bitmap.createBitmap(2048, i4, config);
        this.f730d = createBitmap;
        createBitmap.eraseColor(16);
        this.f732g = 0.09375d;
        this.f734i = new a();
        this.f735j = 0L;
        this.f736k = 0.0d;
        this.f737l = -1;
        this.f = context;
        this.f733h = Bitmap.createBitmap(1, i4, config);
    }

    public static void a() {
        int glGetError = GLES20.glGetError();
        if (glGetError != 0) {
            StackTraceElement stackTraceElement = Thread.currentThread().getStackTrace()[3];
            Log.e(stackTraceElement.getFileName() + ":" + stackTraceElement.getLineNumber(), ", GLES20 error: " + glGetError);
        }
    }

    public final double b() {
        long uptimeMillis = SystemClock.uptimeMillis();
        double width = (((uptimeMillis - this.f735j) * this.f732g) + this.f736k) % this.f730d.getWidth();
        this.f735j = uptimeMillis;
        this.f736k = width;
        return width;
    }

    public final void c(GL10 gl10) {
        GLES20.glUseProgram(this.c);
        a();
        GLES20.glActiveTexture(33984);
        a();
        GLES20.glBindTexture(3553, this.f728a[0]);
        a();
        GLES20.glUniform1i(GLES20.glGetUniformLocation(this.c, "uTexture"), 0);
        a();
        int glGetAttribLocation = GLES20.glGetAttribLocation(this.c, "aPosition");
        a();
        int glGetAttribLocation2 = GLES20.glGetAttribLocation(this.c, "aTexPos");
        a();
        this.f729b.position(0);
        a();
        GLES20.glVertexAttribPointer(glGetAttribLocation, 2, 5126, false, 16, (Buffer) this.f729b);
        a();
        GLES20.glEnableVertexAttribArray(glGetAttribLocation);
        a();
        this.f729b.position(2);
        a();
        GLES20.glVertexAttribPointer(glGetAttribLocation2, 2, 5126, false, 16, (Buffer) this.f729b);
        a();
        GLES20.glEnableVertexAttribArray(glGetAttribLocation2);
        a();
        GLES20.glDrawArrays(5, 0, 4);
        a();
    }

    public final void d(GL10 gl10, int i4, int i5) {
        GLES20.glViewport(0, 0, i4, i5);
        a();
    }

    @Override // android.opengl.GLSurfaceView.Renderer
    public final void onDrawFrame(GL10 gl10) {
        float b4 = (float) (b() / this.f730d.getWidth());
        float f = 0.5f + b4;
        float[] fArr = this.f731e;
        fArr[6] = f;
        fArr[2] = f;
        float f4 = b4 + 1.0f;
        fArr[14] = f4;
        fArr[10] = f4;
        this.f729b.position(0);
        this.f729b.put(this.f731e);
        c(gl10);
    }

    @Override // android.opengl.GLSurfaceView.Renderer
    public final void onSurfaceChanged(GL10 gl10, int i4, int i5) {
        d(gl10, i4, i5);
        float width = 1.0f - (((this.f730d.getWidth() * 0.7f) / (i4 / this.f.getResources().getDisplayMetrics().density)) * 2.0f);
        float[] fArr = this.f731e;
        fArr[4] = width;
        fArr[0] = width;
        this.f729b.position(0);
        this.f729b.put(this.f731e);
    }

    @Override // android.opengl.GLSurfaceView.Renderer
    public final void onSurfaceCreated(GL10 gl10, EGLConfig eGLConfig) {
        int glCreateShader = GLES20.glCreateShader(35633);
        a();
        GLES20.glShaderSource(glCreateShader, "attribute vec2 aPosition;\nattribute vec2 aTexPos;\nvarying vec2 vTexPos;\nvoid main() {\n  vTexPos = aTexPos;\n  gl_Position = vec4(aPosition.xy, 0.0, 1.0);\n}");
        a();
        GLES20.glCompileShader(glCreateShader);
        a();
        int glCreateShader2 = GLES20.glCreateShader(35632);
        a();
        GLES20.glShaderSource(glCreateShader2, "precision mediump float;\nuniform sampler2D uTexture;\nvarying vec2 vTexPos;\nvoid main(void)\n{\n  gl_FragColor = texture2D(uTexture, vTexPos);\n}");
        a();
        GLES20.glCompileShader(glCreateShader2);
        a();
        this.c = GLES20.glCreateProgram();
        a();
        GLES20.glAttachShader(this.c, glCreateShader);
        a();
        GLES20.glAttachShader(this.c, glCreateShader2);
        a();
        GLES20.glLinkProgram(this.c);
        a();
        int[] iArr = this.f728a;
        GLES20.glGenTextures(1, iArr, 0);
        a();
        GLES20.glBindTexture(3553, iArr[0]);
        a();
        GLES20.glTexParameteri(3553, 10240, 9729);
        GLES20.glTexParameteri(3553, 10242, 10497);
        GLES20.glTexParameteri(3553, 10241, 9729);
        GLES20.glTexParameteri(3553, 10243, 10497);
        a();
        GLUtils.texImage2D(3553, 0, this.f730d, 0);
        a();
        float[] fArr = this.f731e;
        ByteBuffer allocateDirect = ByteBuffer.allocateDirect(fArr.length * 4);
        allocateDirect.order(ByteOrder.nativeOrder());
        FloatBuffer asFloatBuffer = allocateDirect.asFloatBuffer();
        this.f729b = asFloatBuffer;
        asFloatBuffer.put(fArr);
    }
}
