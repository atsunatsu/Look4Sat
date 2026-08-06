package j3;

/* loaded from: classes.dex */
public final class a implements Runnable {
    public final /* synthetic */ int f;

    /* renamed from: g, reason: collision with root package name */
    public final /* synthetic */ int f11919g;

    /* renamed from: h, reason: collision with root package name */
    public final /* synthetic */ int f11920h;

    /* renamed from: i, reason: collision with root package name */
    public final /* synthetic */ int f11921i;

    /* renamed from: j, reason: collision with root package name */
    public final /* synthetic */ float[] f11922j;

    /* renamed from: k, reason: collision with root package name */
    public final /* synthetic */ float[] f11923k;

    /* renamed from: l, reason: collision with root package name */
    public final /* synthetic */ int f11924l;

    public /* synthetic */ a(int i4, int i5, int i6, int i7, int i8, float[] fArr, float[] fArr2) {
        this.f = i8;
        this.f11919g = i4;
        this.f11920h = i5;
        this.f11921i = i6;
        this.f11922j = fArr;
        this.f11923k = fArr2;
        this.f11924l = i7;
    }

    @Override // java.lang.Runnable
    public final void run() {
        switch (this.f) {
            case 0:
                int i4 = this.f11919g;
                int i5 = this.f11920h;
                int i6 = i4 + i5;
                int i7 = this.f11921i;
                while (true) {
                    int i8 = i7;
                    if (i8 > 512) {
                        i7 = i8 >> 2;
                        c.w(i7, i6 - i7, this.f11924l - (i8 >> 3), this.f11922j, this.f11923k);
                    } else {
                        int i9 = this.f11924l;
                        float[] fArr = this.f11923k;
                        c.u(i8, 1, this.f11922j, i6 - i8, i9, fArr);
                        int i10 = i4 - i8;
                        int i11 = 0;
                        int i12 = i5 - i8;
                        while (i12 > 0) {
                            int i13 = i11 + 1;
                            int i14 = this.f11924l;
                            float[] fArr2 = this.f11923k;
                            int i15 = i12;
                            i11 = i13;
                            int i16 = this.f11924l;
                            float[] fArr3 = this.f11923k;
                            c.u(i8, c.E(i8, i12, i13, this.f11919g, i14, this.f11922j, fArr2), this.f11922j, i10 + i15, i16, fArr3);
                            i12 = i15 - i8;
                        }
                        return;
                    }
                }
            default:
                int i17 = this.f11919g;
                int i18 = this.f11920h;
                int i19 = i17 + i18;
                int i20 = this.f11921i;
                int i21 = 1;
                while (i20 > 512) {
                    i20 >>= 2;
                    i21 <<= 2;
                    c.y(i20, i19 - i20, this.f11924l - i20, this.f11922j, this.f11923k);
                }
                int i22 = this.f11924l;
                float[] fArr4 = this.f11923k;
                c.u(i20, 0, this.f11922j, i19 - i20, i22, fArr4);
                int i23 = i21 >> 1;
                int i24 = i17 - i20;
                int i25 = i18 - i20;
                while (i25 > 0) {
                    int i26 = i23 + 1;
                    int i27 = this.f11924l;
                    float[] fArr5 = this.f11923k;
                    int i28 = i25;
                    i23 = i26;
                    int i29 = this.f11924l;
                    float[] fArr6 = this.f11923k;
                    c.u(i20, c.E(i20, i25, i26, this.f11919g, i27, this.f11922j, fArr5), this.f11922j, i24 + i28, i29, fArr6);
                    i25 = i28 - i20;
                }
                return;
        }
    }
}
