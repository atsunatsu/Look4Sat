package H2;

/* loaded from: classes.dex */
public final class c {

    /* renamed from: a, reason: collision with root package name */
    public final int f630a;

    /* renamed from: b, reason: collision with root package name */
    public float[] f631b;
    public int c;

    /* renamed from: d, reason: collision with root package name */
    public float f632d;

    /* renamed from: e, reason: collision with root package name */
    public float f633e;
    public final /* synthetic */ int f;

    public c(int i4, int i5) {
        this.f = i5;
        this.f630a = i4 - 1;
    }

    public final void a(float[] fArr) {
        int i4 = this.f630a / 2;
        int length = fArr.length;
        if (length > i4) {
            this.f631b = null;
            for (int i5 = 0; i5 < i4; i5++) {
                c(fArr[i5]);
            }
            for (int i6 = i4; i6 < length; i6++) {
                fArr[i6 - i4] = c(fArr[i6]);
            }
            for (int i7 = length; i7 < length + i4; i7++) {
                fArr[i7 - i4] = c(fArr[length - 1]);
            }
            return;
        }
        throw new IllegalArgumentException("Sliding Filter error: input too short");
    }

    public final float b(float f, float f4) {
        switch (this.f) {
            case 0:
                return Math.max(f, f4);
            default:
                return Math.min(f, f4);
        }
    }

    public final float c(float f) {
        float[] fArr = this.f631b;
        int i4 = this.f630a;
        if (fArr == null) {
            this.f631b = new float[i4];
            for (int i5 = 0; i5 < i4; i5++) {
                this.f631b[i5] = f;
            }
            this.f632d = f;
            return f;
        }
        float b4 = b(this.f632d, f);
        this.f632d = b4;
        this.f633e = b(this.f631b[this.c], b4);
        float[] fArr2 = this.f631b;
        int i6 = this.c;
        int i7 = i6 + 1;
        this.c = i7;
        fArr2[i6] = f;
        if (i7 == i4) {
            int length = fArr2.length;
            while (true) {
                this.c = length - 1;
                int i8 = this.c;
                if (i8 <= 0) {
                    break;
                }
                float[] fArr3 = this.f631b;
                fArr3[i8 - 1] = b(fArr3[i8 - 1], fArr3[i8]);
                length = this.c;
            }
            this.c = 0;
            this.f632d = f;
        }
        return this.f633e;
    }
}
