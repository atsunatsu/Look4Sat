package i3;

/* loaded from: classes.dex */
public final class b implements Runnable {
    public final /* synthetic */ long f;

    /* renamed from: g, reason: collision with root package name */
    public final /* synthetic */ long f11691g;

    /* renamed from: h, reason: collision with root package name */
    public final /* synthetic */ long f11692h;

    /* renamed from: i, reason: collision with root package name */
    public final /* synthetic */ k3.d f11693i;

    /* renamed from: j, reason: collision with root package name */
    public final /* synthetic */ k3.d f11694j;

    /* renamed from: k, reason: collision with root package name */
    public final /* synthetic */ d f11695k;

    public b(d dVar, long j4, long j5, long j6, k3.d dVar2, k3.d dVar3) {
        this.f11695k = dVar;
        this.f = j4;
        this.f11691g = j5;
        this.f11692h = j6;
        this.f11693i = dVar2;
        this.f11694j = dVar3;
    }

    @Override // java.lang.Runnable
    public final void run() {
        long j4 = this.f11691g;
        long j5 = this.f;
        if (j5 >= j4) {
            return;
        }
        this.f11694j.b(this.f11692h + j5);
        throw null;
    }
}
