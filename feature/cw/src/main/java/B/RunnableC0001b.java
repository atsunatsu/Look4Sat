package B;

import android.text.SpannableStringBuilder;
import com.ve3nea.morse_expert.DecodedTextView;
import com.ve3nea.morse_expert.MainActivity;
import H2.b;

/**
 * 照搬自 Morse Expert 1.15 B.RunnableC0001b, 类名保持混淆原名。
 * R8 合并 Runnable 手术: 构造按 smali 真实签名 5 参 (Object,Object,int,int,boolean),
 * 字段 = i(Object)/h(Object)/g(int)/f(int); run() 仅保留 case 4(f==2 对应 pswitch_4:
 * 解码文本 UI 更新, H2.b 调用), 其余 case(权限/Intent/通知等库代码)已裁剪。
 * 注: jadx 4 参构造为错误还原, 已按 smali 修正。
 */
public final class RunnableC0001b implements Runnable {
    public final /* synthetic */ int f;

    /* renamed from: g, reason: collision with root package name */
    public final int g;

    /* renamed from: h, reason: collision with root package name */
    public final Object h;

    /* renamed from: i, reason: collision with root package name */
    public final Object i;

    public /* synthetic */ RunnableC0001b(Object obj, Object obj2, int i4, int i5, boolean z) {
        this.i = obj;
        this.h = obj2;
        this.g = i4;
        this.f = i5;
    }

    @Override // java.lang.Runnable
    public final void run() {
        if (this.f == 2) {
            MainActivity mainActivity = ((b) this.i).f620p;
            if (mainActivity != null) {
                SpannableStringBuilder spannableStringBuilder = (SpannableStringBuilder) this.h;
                int i5 = this.g;
                DecodedTextView decodedTextView = (DecodedTextView) mainActivity.f11035D.f11888g;
                if (!decodedTextView.f11032n && !decodedTextView.f11033o.get()) {
                    decodedTextView.beginBatchEdit();
                    try {
                        int length2 = decodedTextView.length();
                        int min = Math.min(length2, i5);
                        if (min > 0) {
                            decodedTextView.getEditableText().delete(length2 - min, length2);
                        }
                        decodedTextView.q(spannableStringBuilder);
                        decodedTextView.endBatchEdit();
                        return;
                    } catch (Throwable th) {
                        decodedTextView.endBatchEdit();
                        throw th;
                    }
                }
                return;
            }
            return;
        }
    }
}
