package i3;

/* loaded from: classes.dex */
public final class a implements Runnable {
    public final /* synthetic */ int f;

    /* renamed from: g, reason: collision with root package name */
    public final /* synthetic */ int f11686g;

    /* renamed from: h, reason: collision with root package name */
    public final /* synthetic */ int f11687h;

    /* renamed from: i, reason: collision with root package name */
    public final /* synthetic */ float[] f11688i;

    /* renamed from: j, reason: collision with root package name */
    public final /* synthetic */ float[] f11689j;

    /* renamed from: k, reason: collision with root package name */
    public final /* synthetic */ d f11690k;

    public a(d dVar, int i4, int i5, int i6, float[] fArr, float[] fArr2) {
        this.f11690k = dVar;
        this.f = i4;
        this.f11686g = i5;
        this.f11687h = i6;
        this.f11688i = fArr;
        this.f11689j = fArr2;
    }

    @Override // java.lang.Runnable
    public final void run() {
        int i4 = this.f11686g;
        int i5 = this.f;
        if (i5 >= i4) {
            return;
        }
        float f = this.f11689j[this.f11687h + i5];
        throw null;
    }
}
