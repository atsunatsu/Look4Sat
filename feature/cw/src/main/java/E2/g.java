package E2;

import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import com.ve3nea.morse_expert.MainActivity;
import com.ve3nea.morse_expert.ScaleView;

/**
 * 照搬自 Morse Expert 1.15 (com.ve3nea.morse_expert), 类名保持混淆原名。
 * R8 合并类手术: 只保留 case 1(瀑布图触摸选频率=应用逻辑),
 * 其余 case(广告 P4 / appcompat 焦点 / 弹窗)为库代码, 已裁剪。
 */
public final class g implements View.OnTouchListener {
    public final /* synthetic */ int f;

    /* renamed from: g, reason: collision with root package name */
    public final /* synthetic */ Object f411g;

    public /* synthetic */ g(Object obj, int i4) {
        this.f = i4;
        this.f411g = obj;
    }

    @Override // android.view.View.OnTouchListener
    public final boolean onTouch(View view, MotionEvent motionEvent) {
        int i4 = this.f;
        Object obj = this.f411g;
        if (i4 == 1) {
            MainActivity mainActivity = (MainActivity) obj;
            if (motionEvent.getActionMasked() == 0 && mainActivity.f11037F != null) {
                // smali 证实: p1 先被重赋值为 f11035D.h(ScaleView)再 getHeight;
                // 公式 = round((f606z + ((scaleH-1-y)*f603B/scaleH)) / 0.128f)
                ScaleView scaleView = (ScaleView) mainActivity.f11035D.f11889h;
                float y3 = motionEvent.getY();
                int round = Math.round((H2.b.f606z + ((((scaleView.getHeight() - 1) - y3) * H2.b.f603B) / scaleView.getHeight())) / 0.128f);
                mainActivity.f11037F.f622r.set(round);
                Log.i("Waterfall", String.format("Touch at %d Hz`", Integer.valueOf(round)));
            }
            return false;
        }
        return false;
    }
}
