package J2;

/* loaded from: classes.dex */
public final class a {

    /* renamed from: b, reason: collision with root package name */
    public static final int[] f726b = {16, 17749, 65535, 16776960, 16711680};

    /* renamed from: a, reason: collision with root package name */
    public final int[] f727a;

    public a() {
        int[] iArr = new int[256];
        this.f727a = iArr;
        int i4 = 4;
        float f = 255.0f / 4;
        int[] iArr2 = f726b;
        int i5 = 0;
        int i6 = -16777216;
        iArr[0] = iArr2[0] | (-16777216);
        int i7 = 1;
        while (i7 <= i4) {
            int round = Math.round(i7 * f);
            iArr[round] = iArr2[i7] | i6;
            int i8 = round - i5;
            int i9 = iArr[i5];
            int i10 = (i9 >> 16) & 255;
            int i11 = (i9 >> 8) & 255;
            int i12 = i9 & 255;
            float f4 = i8;
            // r10 = iArr2[i7] (下一个锚点色, smali: aget v10, v4, v8)
            float f5 = (((iArr2[i7] >> 16) & 255) - i10) / f4;
            float f6 = (((iArr2[i7] >> 8) & 255) - i11) / f4;
            float f7 = ((iArr2[i7] & 255) - i12) / f4;
            for (int i13 = 1; i13 < i8; i13++) {
                float f8 = i13;
                iArr[i5 + i13] = ((Math.round(f5 * f8) + i10) << 16) | (-16777216) | ((Math.round(f6 * f8) + i11) << 8) | (Math.round(f8 * f7) + i12);
            }
            i7++;
            i5 = round;
            i4 = 4;
            i6 = -16777216;
        }
    }
}
