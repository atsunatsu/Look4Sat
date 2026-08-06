package I0;

/**
 * 照搬自 Morse Expert 1.15 I0.d, 类名保持混淆原名。
 * R8 合并类手术: 只保留 (i3.d, int, int, float[]) FFT 任务构造;
 * run() 按 smali 原样还原: R8 已将任务体优化为死代码(aget 后 throw null,
 * 永不执行), 保留原语义(行为与原 APK 完全一致)。
 * SystemForegroundService(work 库)case 已裁剪。
 */
public final class d implements Runnable {
    public final /* synthetic */ int f = 0;

    /* renamed from: g, reason: collision with root package name */
    public final /* synthetic */ int f653g;

    /* renamed from: h, reason: collision with root package name */
    public final /* synthetic */ int f654h;

    /* renamed from: i, reason: collision with root package name */
    public final /* synthetic */ Object f655i;

    /* renamed from: j, reason: collision with root package name */
    public final /* synthetic */ Object f656j;

    public d(i3.d dVar, int i4, int i5, float[] fArr) {
        this.f656j = dVar;
        this.f653g = i4;
        this.f654h = i5;
        this.f655i = fArr;
    }

    @Override // java.lang.Runnable
    public final void run() {
        int i6 = this.f654h;
        int i7 = this.f653g;
        if (i7 >= i6) {
            return;
        }
        float f = ((float[]) this.f655i)[i7 * 2];
        // R8 原代码: aget 后 throw null(死代码, 永不执行) —— 照搬语义, 不执行任何计算
    }
}
