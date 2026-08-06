package B0;

import android.widget.TextView;
import com.ve3nea.morse_expert.MainActivity;
import com.ve3nea.morse_expert.ScaleView;
import java.util.Locale;
import d1.AbstractC1518b;

/**
 * 照搬自 Morse Expert 1.15 B0.b, 类名保持混淆原名。
 * R8 合并 Runnable 手术: 构造按 smali 真实签名 (H2/b, String, [I) -> f=4;
 * 只保留 case 4(状态栏更新: dBFS/Hz/dB/WPM + ScaleView 频率标记, H2.b 调用),
 * 其余 case(WorkManager/动画等库代码)已裁剪。
 * r3 = scaleView.getHeight()(smali 证实, jadx 寄存器未展开)。
 */
public final class b implements Runnable {
    public final /* synthetic */ int f;

    /* renamed from: g, reason: collision with root package name */
    public final Object g;

    /* renamed from: h, reason: collision with root package name */
    public final Object h;

    /* renamed from: i, reason: collision with root package name */
    public final Object i;

    public b(H2.b bVar, String str, int[] iArr) {
        this.f = 4;
        this.g = bVar;
        this.h = iArr;
        this.i = str;
    }

    @Override // java.lang.Runnable
    public final void run() {
        if (this.f == 4) {
            MainActivity mainActivity = ((H2.b) this.g).f621q;
            if (mainActivity != null) {
                String str2 = (String) this.i;
                int[] iArr = (int[]) this.h;
                String concat;
                String format = String.format(Locale.US, " %3d dBFS", Integer.valueOf(iArr[0]));
                if (iArr[5] == 0) {
                    concat = format.concat("  Idle    ");
                } else {
                    concat = format.concat(String.format(Locale.US, "    %4d Hz    %2d dB    %2d WPM    ", Integer.valueOf(iArr[1]), Integer.valueOf(iArr[2]), Integer.valueOf(iArr[3])));
                }
                ((TextView) mainActivity.f11035D.f11890i).setText(AbstractC1518b.e(concat, str2));
                ScaleView scaleView = (ScaleView) mainActivity.f11035D.f11889h;
                int i4 = iArr[1];
                scaleView.f11041g = iArr[5];
                // r3 = scaleView.getHeight()(smali 证实)
                scaleView.f = (scaleView.getHeight() - 1) - Math.round(((Math.round(i4 * 0.128f) - H2.b.f606z) * scaleView.getHeight()) / H2.b.f603B);
                scaleView.invalidate();
                return;
            }
            return;
        }
    }
}
