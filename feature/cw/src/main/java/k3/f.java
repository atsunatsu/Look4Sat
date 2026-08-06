package k3;

/* loaded from: classes.dex */
public final class f implements Runnable {
    public long f;

    /* renamed from: g, reason: collision with root package name */
    public final long f12100g;

    /* renamed from: h, reason: collision with root package name */
    public final long f12101h;

    public f(long j4, long j5, long j6) {
        this.f = j4;
        this.f12100g = j5;
        this.f12101h = j6;
    }

    @Override // java.lang.Runnable
    public final void run() {
        long j4 = this.f;
        if (j4 != 0) {
            r.f12109a.freeMemory(j4);
            this.f = 0L;
            long j5 = K1.a.c - (this.f12100g * this.f12101h);
            K1.a.c = j5;
            if (j5 < 0) {
                K1.a.c = 0L;
            }
        }
    }
}
