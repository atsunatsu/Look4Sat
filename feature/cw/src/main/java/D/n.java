package D;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import com.ve3nea.morse_expert.WaterfallSurfaceView;

/**
 * 照搬自 Morse Expert 1.15, 类名保持混淆原名。
 * R8 合并 Runnable 手术: 只保留 case 1(瀑布图频谱->OpenGL 纹理, H2.b 调用),
 * 其余 case(库/广告)已裁剪。
 */
public final /* synthetic */ class n implements Runnable {
    public final /* synthetic */ int f;

    /* renamed from: g, reason: collision with root package name */
    public final /* synthetic */ Object f224g;

    /* renamed from: h, reason: collision with root package name */
    public final /* synthetic */ Object f225h;

    public /* synthetic */ n(Object obj, int i4, Object obj2) {
        this.f = i4;
        this.f224g = obj;
        this.f225h = obj2;
    }

    @Override // java.lang.Runnable
    public final void run() {
        int i4 = this.f;
        Object obj = this.f225h;
        Object obj2 = this.f224g;
        if (i4 == 1) {
            float[] fArr = (float[]) obj;
            J2.b bVar = ((WaterfallSurfaceView) obj2).f;
            Bitmap bitmap = bVar.f733h;
            int width = bVar.f730d.getWidth();
            double d4 = width;
            double b4 = (bVar.b() + 4.0d) % d4;
            double d5 = bVar.f737l - b4;
            if (d5 > d4 / 2.0d) {
                d5 -= d4;
            } else if (d5 < (-width) / 2.0d) {
                d5 += d4;
            }
            if (Math.abs(d5) > 8.0d) {
                bVar.f737l = (int) Math.floor(b4);
            } else {
                bVar.f732g = (d5 * 1.0E-5d) + bVar.f732g;
            }
            int height = bitmap.getHeight() - 1;
            for (int i5 = 0; i5 <= height; i5++) {
                J2.a aVar = bVar.f734i;
                int round = Math.round((fArr[i5] * 4.0f) + 30.0f);
                aVar.getClass();
                bitmap.setPixel(0, height - i5, aVar.f727a[Math.max(0, Math.min(255, round))]);
            }
            int i6 = bVar.f737l;
            GLES20.glBindTexture(3553, bVar.f728a[0]);
            J2.b.a();
            GLUtils.texSubImage2D(3553, 0, i6, 0, bitmap);
            J2.b.a();
            int i7 = bVar.f737l + 1;
            bVar.f737l = i7;
            bVar.f737l = i7 % width;
            return;
        }
    }
}
