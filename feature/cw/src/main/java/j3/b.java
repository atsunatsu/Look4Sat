package j3;

import k3.d;

/* loaded from: classes.dex */
public final class b implements Runnable {
    public final /* synthetic */ int f;

    /* renamed from: g, reason: collision with root package name */
    public final /* synthetic */ long f11925g;

    /* renamed from: h, reason: collision with root package name */
    public final /* synthetic */ long f11926h;

    /* renamed from: i, reason: collision with root package name */
    public final /* synthetic */ long f11927i;

    /* renamed from: j, reason: collision with root package name */
    public final /* synthetic */ d f11928j;

    /* renamed from: k, reason: collision with root package name */
    public final /* synthetic */ d f11929k;

    /* renamed from: l, reason: collision with root package name */
    public final /* synthetic */ long f11930l;

    public /* synthetic */ b(long j4, long j5, long j6, d dVar, d dVar2, long j7, int i4) {
        this.f = i4;
        this.f11925g = j4;
        this.f11926h = j5;
        this.f11927i = j6;
        this.f11928j = dVar;
        this.f11929k = dVar2;
        this.f11930l = j7;
    }

    @Override // java.lang.Runnable
    public final void run() {
        switch (this.f) {
            case 0:
                long j4 = this.f11925g;
                long j5 = this.f11926h;
                long j6 = j4 + j5;
                long j7 = this.f11927i;
                while (j7 > 512) {
                    long j8 = j7 >> 2;
                    c.x(j8, j6 - j8, this.f11930l - (j7 >> 3), this.f11928j, this.f11929k);
                    j7 = j8;
                }
                c.v(j7, 1L, j6 - j7, this.f11930l, this.f11928j, this.f11929k);
                long j9 = j4 - j7;
                long j10 = j5 - j7;
                long j11 = 0;
                for (long j12 = 0; j10 > j12; j12 = 0) {
                    long j13 = j11 + 1;
                    long j14 = j10;
                    c.v(j7, c.F(j7, j10, j13, this.f11925g, this.f11930l, this.f11928j, this.f11929k), j9 + j14, this.f11930l, this.f11928j, this.f11929k);
                    j10 = j14 - j7;
                    j11 = j13;
                    j9 = j9;
                }
                return;
            case 1:
                long j15 = this.f11925g;
                long j16 = this.f11926h;
                long j17 = j15 + j16;
                long j18 = this.f11927i;
                long j19 = 1;
                while (j18 > 512) {
                    long j20 = j18 >> 2;
                    j19 <<= 2;
                    c.z(j20, j17 - j20, this.f11930l - j20, this.f11928j, this.f11929k);
                    j18 = j20;
                }
                c.v(j18, 0L, j17 - j18, this.f11930l, this.f11928j, this.f11929k);
                long j21 = j19 >> 1;
                long j22 = j15 - j18;
                long j23 = j16 - j18;
                while (j23 > 0) {
                    long j24 = j21 + 1;
                    long j25 = j23;
                    j21 = j24;
                    c.v(j18, c.F(j18, j23, j24, this.f11925g, this.f11930l, this.f11928j, this.f11929k), j22 + j25, this.f11930l, this.f11928j, this.f11929k);
                    j23 = j25 - j18;
                }
                return;
            default:
                for (long j26 = this.f11925g; j26 < this.f11926h; j26++) {
                    this.f11928j.c(this.f11927i + j26, this.f11929k.b(this.f11930l + j26));
                }
                return;
        }
    }

    public b(long j4, long j5, long j6, long j7, d dVar, d dVar2) {
        this.f = 2;
        this.f11925g = j4;
        this.f11926h = j5;
        this.f11928j = dVar;
        this.f11927i = j6;
        this.f11929k = dVar2;
        this.f11930l = j7;
    }
}
