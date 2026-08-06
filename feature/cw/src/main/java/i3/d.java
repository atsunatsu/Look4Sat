package i3;

import d1.AbstractC1518b;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import k3.g;
import k3.q;
import k3.r;
import k3.s;
import s.AbstractC1880e;

/* loaded from: classes.dex */
public final class d {

    /* renamed from: a, reason: collision with root package name */
    public int f11700a;

    /* renamed from: b, reason: collision with root package name */
    public long f11701b;
    public int[] c;

    /* renamed from: d, reason: collision with root package name */
    public s f11702d;

    /* renamed from: e, reason: collision with root package name */
    public float[] f11703e;
    public k3.d f;

    /* renamed from: g, reason: collision with root package name */
    public int f11704g;

    /* renamed from: h, reason: collision with root package name */
    public long f11705h;

    /* renamed from: i, reason: collision with root package name */
    public int f11706i;

    /* renamed from: j, reason: collision with root package name */
    public long f11707j;

    /* renamed from: k, reason: collision with root package name */
    public int f11708k;

    /* renamed from: l, reason: collision with root package name */
    public boolean f11709l;

    public final void a(int i4, int i5, int i6, int i7, int i8, float[] fArr, float[] fArr2) {
        int i9 = i5 * i4;
        int i10 = i4 * 2;
        for (int i11 = 0; i11 < i5; i11++) {
            int i12 = (i11 * i10) + i7;
            int i13 = (i11 * i4) + i6;
            int i14 = i13 + i9;
            float f = fArr[i13];
            float f4 = fArr[i14];
            fArr2[i12] = f + f4;
            fArr2[(i12 + i10) - 1] = f - f4;
        }
        if (i4 >= 2) {
            if (i4 != 2) {
                for (int i15 = 0; i15 < i5; i15++) {
                    i10 = i15 * i4;
                    int i16 = i10 + i9;
                    if (2 < i4) {
                        int i17 = i6 + 2;
                        int i18 = i10 + i17;
                        int i19 = i17 + i16;
                        float f5 = fArr[i18 - 1];
                        float f6 = fArr[i18];
                        float f7 = fArr[i19 - 1];
                        float f8 = fArr[i19];
                        throw null;
                    }
                }
                if (i4 % 2 == 1) {
                    return;
                }
            }
            int i20 = i10 * 2;
            for (int i21 = 0; i21 < i5; i21++) {
                int i22 = i7 + i20 + i4;
                int i23 = ((i6 + i4) - 1) + (i21 * i4);
                fArr2[i22] = -fArr[i23 + i9];
                fArr2[i22 - 1] = fArr[i23];
            }
        }
    }

    public final void b(long j4, long j5, long j6, long j7, long j8, k3.d dVar, k3.d dVar2) {
        long j9 = j5 * j4;
        long j10 = 2;
        long j11 = j4 * 2;
        long j12 = 0;
        while (j12 < j5) {
            long j13 = (j12 * j11) + j7;
            long j14 = (j12 * j4) + j6;
            float b4 = dVar.b(j14);
            float b5 = dVar.b(j14 + j9);
            dVar2.c((j13 + j11) - 1, AbstractC1518b.m(b4, b5, dVar2, j13, b4, b5));
            j12++;
            j10 = j10;
        }
        long j15 = j10;
        if (j4 >= j15) {
            if (j4 != j15) {
                for (long j16 = 0; j16 < j5; j16++) {
                    j11 = j16 * j4;
                    long j17 = j11 + j9;
                    if (j15 < j4) {
                        long j18 = j6 + j15;
                        long j19 = j11 + j18;
                        long j20 = j18 + j17;
                        dVar.b(j19 - 1);
                        dVar.b(j19);
                        dVar.b(j20 - 1);
                        dVar.b(j20);
                        throw null;
                    }
                }
                if (j4 % j15 == 1) {
                    return;
                }
            }
            long j21 = j11 * j15;
            for (long j22 = 0; j22 < j5; j22++) {
                long j23 = j7 + j21 + j4;
                long j24 = ((j6 + j4) - 1) + (j22 * j4);
                dVar2.c(j23, -dVar.b(j24 + j9));
                dVar2.c(j23 - 1, dVar.b(j24));
            }
        }
    }

    public final void c(int i4, int i5, int i6, int i7, int i8, float[] fArr, float[] fArr2) {
        int i9 = i5 * i4;
        for (int i10 = 0; i10 < i5; i10++) {
            int i11 = i10 * i4;
            int i12 = ((i10 * 3) + 1) * i4;
            int i13 = i6 + i11;
            int i14 = i13 + i9;
            int i15 = (i9 * 2) + i13;
            float f = fArr[i13];
            float f4 = fArr[i14];
            float f5 = fArr[i15];
            float f6 = f4 + f5;
            fArr2[(i11 * 3) + i7] = f + f6;
            fArr2[i7 + i12 + i4] = (f5 - f4) * 0.8660254f;
            fArr2[((i7 + i4) - 1) + i12] = (f6 * (-0.5f)) + f;
        }
        if (i4 != 1) {
            for (int i16 = 0; i16 < i5; i16++) {
                if (2 < i4) {
                    throw null;
                }
            }
        }
    }

    public final void d(long j4, long j5, long j6, long j7, long j8, k3.d dVar, k3.d dVar2) {
        long j9 = j5 * j4;
        for (long j10 = 0; j10 < j5; j10++) {
            long j11 = j10 * j4;
            long j12 = ((j10 * 3) + 1) * j4;
            long j13 = j6 + j11;
            long j14 = j13 + j9;
            long j15 = (2 * j9) + j13;
            float b4 = dVar.b(j13);
            float b5 = dVar.b(j14);
            float b6 = dVar.b(j15);
            float f = b5 + b6;
            dVar2.c((j11 * 3) + j7, b4 + f);
            dVar2.c(j7 + j12 + j4, (b6 - b5) * 0.8660254f);
            dVar2.c(((j7 + j4) - 1) + j12, (f * (-0.5f)) + b4);
        }
        if (j4 != 1) {
            for (long j16 = 0; j16 < j5; j16++) {
                if (2 < j4) {
                    throw null;
                }
            }
        }
    }

    public final void e(int i4, int i5, int i6, int i7, int i8, float[] fArr, float[] fArr2) {
        int i9 = i5 * i4;
        for (int i10 = 0; i10 < i5; i10++) {
            int i11 = i10 * i4;
            int i12 = i11 * 4;
            int i13 = i11 + i9;
            int i14 = i13 + i9;
            int i15 = i14 + i9;
            float f = fArr[i6 + i11];
            float f4 = fArr[i6 + i13];
            float f5 = fArr[i6 + i14];
            float f6 = fArr[i6 + i15];
            float f7 = f4 + f6;
            float f8 = f + f5;
            int i16 = i7 + i12 + i4 + i4;
            fArr2[i7 + i12] = f7 + f8;
            int i17 = i16 - 1;
            fArr2[i17 + i4 + i4] = f8 - f7;
            fArr2[i17] = f - f5;
            fArr2[i16] = f6 - f4;
        }
        if (i4 >= 2) {
            if (i4 != 2) {
                for (int i18 = 0; i18 < i5; i18++) {
                    if (2 < i4) {
                        throw null;
                    }
                }
                if (i4 % 2 == 1) {
                    return;
                }
            }
            for (int i19 = 0; i19 < i5; i19++) {
                int i20 = i19 * i4;
                int i21 = i20 * 4;
                int i22 = i20 + i9;
                int i23 = i22 + i9;
                int i24 = i23 + i9;
                int i25 = i21 + i4;
                int i26 = i25 + i4;
                int i27 = (i6 + i4) - 1;
                float f9 = fArr[i20 + i27];
                float f10 = fArr[i22 + i27];
                float f11 = fArr[i23 + i27];
                float f12 = fArr[i27 + i24];
                float f13 = (f10 + f12) * (-0.70710677f);
                float f14 = (f10 - f12) * 0.70710677f;
                int i28 = (i7 + i4) - 1;
                fArr2[i21 + i28] = f14 + f9;
                fArr2[i28 + i26] = f9 - f14;
                fArr2[i7 + i25] = f13 - f11;
                fArr2[i7 + i26 + i4] = f13 + f11;
            }
        }
    }

    public final void f(long j4, long j5, long j6, long j7, long j8, k3.d dVar, k3.d dVar2) {
        long j9 = j5 * j4;
        for (long j10 = 0; j10 < j5; j10++) {
            long j11 = j10 * j4;
            long j12 = 4 * j11;
            long j13 = j11 + j9;
            long j14 = j13 + j9;
            long j15 = j12 + j4;
            float b4 = dVar.b(j6 + j11);
            float b5 = dVar.b(j6 + j13);
            float b6 = dVar.b(j6 + j14);
            float b7 = dVar.b(j6 + j14 + j9);
            float f = b5 + b7;
            float f4 = b4 + b6;
            long j16 = j7 + j15 + j4;
            dVar2.c(j7 + j12, f + f4);
            long j17 = j16 - 1;
            dVar2.c(j17, AbstractC1518b.l(f4, f, dVar2, j17 + j4 + j4, b4, b6));
            dVar2.c(j16, b7 - b5);
        }
        if (j4 >= 2) {
            if (j4 != 2) {
                for (long j18 = 0; j18 < j5; j18++) {
                    if (2 < j4) {
                        throw null;
                    }
                }
                if (j4 % 2 == 1) {
                    return;
                }
            }
            for (long j19 = 0; j19 < j5; j19++) {
                long j20 = j19 * j4;
                long j21 = j20 * 4;
                long j22 = j20 + j9;
                long j23 = j22 + j9;
                long j24 = j21 + j4;
                long j25 = j24 + j4;
                long j26 = (j6 + j4) - 1;
                float b8 = dVar.b(j26 + j20);
                float b9 = dVar.b(j26 + j22);
                float b10 = dVar.b(j26 + j23);
                float b11 = dVar.b(j26 + j23 + j9);
                float f5 = (b9 + b11) * (-0.70710677f);
                float f6 = (b9 - b11) * 0.70710677f;
                long j27 = (j7 + j4) - 1;
                dVar2.c(j27 + j21, f6 + b8);
                dVar2.c(j27 + j25, b8 - f6);
                dVar2.c(j7 + j24, f5 - b10);
                dVar2.c(j7 + j25 + j4, f5 + b10);
            }
        }
    }

    public final void g(int i4, int i5, int i6, int i7, int i8, float[] fArr, float[] fArr2) {
        int i9 = i5 * i4;
        for (int i10 = 0; i10 < i5; i10++) {
            int i11 = i10 * i4;
            int i12 = i11 * 5;
            int i13 = i12 + i4;
            int i14 = i13 + i4;
            int i15 = i14 + i4;
            int i16 = i11 + i9;
            int i17 = i16 + i9;
            int i18 = i17 + i9;
            int i19 = (i7 + i4) - 1;
            float f = fArr[i6 + i11];
            float f4 = fArr[i6 + i16];
            float f5 = fArr[i6 + i17];
            float f6 = fArr[i6 + i18];
            float f7 = fArr[i6 + i18 + i9];
            float f8 = f7 + f4;
            float f9 = f7 - f4;
            float f10 = f6 + f5;
            float f11 = f6 - f5;
            fArr2[i7 + i12] = f + f8 + f10;
            fArr2[i19 + i13] = (f10 * (-0.809017f)) + (f8 * 0.309017f) + f;
            fArr2[i7 + i14] = (f11 * 0.58778524f) + (f9 * 0.95105654f);
            fArr2[i19 + i15] = (f10 * 0.309017f) + (f8 * (-0.809017f)) + f;
            fArr2[i7 + i15 + i4] = (f9 * 0.58778524f) - (f11 * 0.95105654f);
        }
        if (i4 != 1) {
            for (int i20 = 0; i20 < i5; i20++) {
                if (2 < i4) {
                    throw null;
                }
            }
        }
    }

    public final void h(long j4, long j5, long j6, long j7, long j8, k3.d dVar, k3.d dVar2) {
        long j9 = j5 * j4;
        long j10 = 0;
        while (j10 < j5) {
            long j11 = j10 * j4;
            long j12 = 5 * j11;
            long j13 = j12 + j4;
            long j14 = j13 + j4;
            long j15 = j14 + j4;
            long j16 = j11 + j9;
            long j17 = j16 + j9;
            long j18 = j17 + j9;
            long j19 = (j7 + j4) - 1;
            float b4 = dVar.b(j6 + j11);
            float b5 = dVar.b(j6 + j16);
            float b6 = dVar.b(j6 + j17);
            float b7 = dVar.b(j6 + j18);
            long j20 = j9;
            float b8 = dVar.b(j6 + j18 + j9);
            float f = b8 + b5;
            float f4 = b8 - b5;
            float f5 = b7 + b6;
            float f6 = b7 - b6;
            dVar2.c(j7 + j12, b4 + f + f5);
            dVar2.c(j19 + j13, (f5 * (-0.809017f)) + (f * 0.309017f) + b4);
            dVar2.c(j7 + j14, (f6 * 0.58778524f) + (f4 * 0.95105654f));
            dVar2.c(j19 + j15, (f5 * 0.309017f) + (f * (-0.809017f)) + b4);
            dVar2.c(j7 + j15 + j4, (f4 * 0.58778524f) - (f6 * 0.95105654f));
            j10++;
            j9 = j20;
        }
        if (j4 != 1) {
            for (long j21 = 0; j21 < j5; j21++) {
                if (2 < j4) {
                    throw null;
                }
            }
        }
    }

    public final void i(int i4, int i5, int i6, int i7, float[] fArr, int i8, float[] fArr2, int i9, int i10) {
        int i11 = i4;
        int i12 = i5;
        double d4 = 6.2831855f / i12;
        float b4 = (float) g3.c.b(d4);
        float f = (float) g3.c.f(d4);
        int i13 = (i12 + 1) / 2;
        int i14 = (i11 - 1) / 2;
        if (i11 != 1) {
            for (int i15 = 0; i15 < i7; i15++) {
                fArr2[i9 + i15] = fArr[i8 + i15];
            }
            for (int i16 = 1; i16 < i12; i16++) {
                int i17 = i16 * i6 * i11;
                for (int i18 = 0; i18 < i6; i18++) {
                    int i19 = (i18 * i11) + i17;
                    fArr2[i9 + i19] = fArr[i8 + i19];
                }
            }
            if (i14 <= i6) {
                for (int i20 = 1; i20 < i12; i20++) {
                    if (2 < i11) {
                        throw null;
                    }
                }
            } else {
                for (int i21 = 1; i21 < i12; i21++) {
                    for (int i22 = 0; i22 < i6; i22++) {
                        if (2 < i11) {
                            throw null;
                        }
                    }
                }
            }
            if (i14 >= i6) {
                for (int i23 = 1; i23 < i13; i23++) {
                    int i24 = i23 * i6 * i11;
                    int i25 = (i12 - i23) * i6 * i11;
                    for (int i26 = 0; i26 < i6; i26++) {
                        int i27 = i26 * i11;
                        int i28 = i27 + i24;
                        int i29 = i27 + i25;
                        for (int i30 = 2; i30 < i11; i30 += 2) {
                            int i31 = i8 + i30;
                            int i32 = i9 + i30;
                            int i33 = i31 + i28;
                            int i34 = i31 + i29;
                            int i35 = i32 + i28;
                            int i36 = i32 + i29;
                            float f4 = fArr2[i35 - 1];
                            float f5 = fArr2[i35];
                            float f6 = fArr2[i36 - 1];
                            float f7 = fArr2[i36];
                            fArr[i33 - 1] = f4 + f6;
                            fArr[i33] = f5 + f7;
                            fArr[i34 - 1] = f5 - f7;
                            fArr[i34] = f6 - f4;
                        }
                    }
                }
            } else {
                int i37 = 1;
                while (i37 < i13) {
                    int i38 = i37 * i6 * i11;
                    int i39 = (i12 - i37) * i6 * i11;
                    for (int i40 = 2; i40 < i11; i40 += 2) {
                        int i41 = i8 + i40;
                        int i42 = i9 + i40;
                        for (int i43 = 0; i43 < i6; i43++) {
                            int i44 = i43 * i11;
                            int i45 = i44 + i38;
                            int i46 = i44 + i39;
                            int i47 = i41 + i45;
                            int i48 = i41 + i46;
                            int i49 = i42 + i45;
                            int i50 = i42 + i46;
                            float f8 = fArr2[i49 - 1];
                            float f9 = fArr2[i49];
                            float f10 = fArr2[i50 - 1];
                            float f11 = fArr2[i50];
                            fArr[i47 - 1] = f8 + f10;
                            fArr[i47] = f9 + f11;
                            fArr[i48 - 1] = f9 - f11;
                            fArr[i48] = f10 - f8;
                        }
                    }
                    i37++;
                    i12 = i5;
                }
            }
        } else {
            System.arraycopy(fArr2, i9, fArr, i8, i7);
        }
        for (int i51 = 1; i51 < i13; i51++) {
            int i52 = i51 * i6 * i11;
            int i53 = (i5 - i51) * i6 * i11;
            for (int i54 = 0; i54 < i6; i54++) {
                int i55 = i54 * i11;
                int i56 = i55 + i52;
                int i57 = i55 + i53;
                float f12 = fArr2[i9 + i56];
                float f13 = fArr2[i9 + i57];
                fArr[i8 + i56] = f12 + f13;
                fArr[i57 + i8] = f13 - f12;
            }
        }
        int i58 = (i5 - 1) * i7;
        float f14 = 1.0f;
        float f15 = 0.0f;
        int i59 = 1;
        while (i59 < i13) {
            float f16 = (b4 * f14) - (f * f15);
            f15 = (f15 * b4) + (f14 * f);
            int i60 = i59 * i7;
            int i61 = (i5 - i59) * i7;
            int i62 = i58;
            for (int i63 = 0; i63 < i7; i63++) {
                int i64 = i9 + i63;
                int i65 = i8 + i63;
                fArr2[i64 + i60] = (fArr[i65 + i7] * f16) + fArr[i65];
                fArr2[i64 + i61] = fArr[i65 + i62] * f15;
            }
            float f17 = f15;
            float f18 = f16;
            int i66 = 2;
            while (i66 < i13) {
                float f19 = (f16 * f18) - (f15 * f17);
                f17 = (f18 * f15) + (f17 * f16);
                int i67 = i66 * i7;
                int i68 = (i5 - i66) * i7;
                int i69 = i66;
                for (int i70 = 0; i70 < i7; i70++) {
                    int i71 = i9 + i70;
                    int i72 = i8 + i70;
                    int i73 = i71 + i60;
                    fArr2[i73] = (fArr[i72 + i67] * f19) + fArr2[i73];
                    int i74 = i71 + i61;
                    fArr2[i74] = (fArr[i72 + i68] * f17) + fArr2[i74];
                }
                i66 = i69 + 1;
                f18 = f19;
            }
            i59++;
            f14 = f16;
            i58 = i62;
        }
        for (int i75 = 1; i75 < i13; i75++) {
            int i76 = i75 * i7;
            for (int i77 = 0; i77 < i7; i77++) {
                int i78 = i9 + i77;
                fArr2[i78] = fArr2[i78] + fArr[i8 + i77 + i76];
            }
        }
        if (i11 >= i6) {
            for (int i79 = 0; i79 < i6; i79++) {
                int i80 = i79 * i11;
                int i81 = i80 * i5;
                for (int i82 = 0; i82 < i11; i82++) {
                    fArr[i8 + i82 + i81] = fArr2[i9 + i82 + i80];
                }
            }
        } else {
            for (int i83 = 0; i83 < i11; i83++) {
                for (int i84 = 0; i84 < i6; i84++) {
                    int i85 = i84 * i11;
                    fArr[(i85 * i5) + i8 + i83] = fArr2[i9 + i83 + i85];
                }
            }
        }
        int i86 = i5 * i11;
        for (int i87 = 1; i87 < i13; i87++) {
            int i88 = i87 * i6 * i11;
            int i89 = (i5 - i87) * i6 * i11;
            int i90 = i87 * 2 * i11;
            for (int i91 = 0; i91 < i6; i91++) {
                int i92 = i91 * i11;
                int i93 = i91 * i86;
                fArr[((((i8 + i11) - 1) + i90) - i11) + i93] = fArr2[i92 + i88 + i9];
                fArr[i8 + i90 + i93] = fArr2[i92 + i89 + i9];
            }
        }
        if (i11 == 1) {
            return;
        }
        if (i14 >= i6) {
            for (int i94 = 1; i94 < i13; i94++) {
                int i95 = i94 * i6 * i11;
                int i96 = (i5 - i94) * i6 * i11;
                int i97 = i94 * 2 * i11;
                for (int i98 = 0; i98 < i6; i98++) {
                    int i99 = i98 * i86;
                    int i100 = i98 * i11;
                    for (int i101 = 2; i101 < i11; i101 += 2) {
                        int i102 = i8 + i101 + i97 + i99;
                        int i103 = (((i8 + (i11 - i101)) + i97) - i11) + i99;
                        int i104 = i9 + i101 + i100;
                        int i105 = i104 + i95;
                        int i106 = i104 + i96;
                        float f20 = fArr2[i105 - 1];
                        float f21 = fArr2[i105];
                        float f22 = fArr2[i106 - 1];
                        float f23 = fArr2[i106];
                        fArr[i102 - 1] = f20 + f22;
                        fArr[i103 - 1] = f20 - f22;
                        fArr[i102] = f21 + f23;
                        fArr[i103] = f23 - f21;
                    }
                }
            }
            return;
        }
        int i107 = 1;
        while (i107 < i13) {
            int i108 = i107 * i6 * i11;
            int i109 = (i5 - i107) * i6 * i11;
            int i110 = i107 * 2 * i11;
            int i111 = 2;
            while (i111 < i11) {
                int i112 = i8 + i111;
                int i113 = (i11 - i111) + i8;
                int i114 = i9 + i111;
                for (int i115 = 0; i115 < i6; i115++) {
                    int i116 = i115 * i86;
                    int i117 = i112 + i110 + i116;
                    int i118 = ((i113 + i110) - i4) + i116;
                    int i119 = i114 + (i115 * i4);
                    int i120 = i119 + i108;
                    int i121 = i119 + i109;
                    float f24 = fArr2[i120 - 1];
                    float f25 = fArr2[i120];
                    float f26 = fArr2[i121 - 1];
                    float f27 = fArr2[i121];
                    fArr[i117 - 1] = f24 + f26;
                    fArr[i118 - 1] = f24 - f26;
                    fArr[i117] = f25 + f27;
                    fArr[i118] = f27 - f25;
                }
                i111 += 2;
                i11 = i4;
            }
            i107++;
            i11 = i4;
        }
    }

    public final void j(long j4, long j5, long j6, long j7, k3.d dVar, long j8, k3.d dVar2, long j9, long j10) {
        float f;
        float f4;
        long j11;
        long j12;
        long j13 = j5;
        k3.d dVar3 = dVar;
        k3.d dVar4 = dVar2;
        double d4 = 6.2831855f / ((float) j13);
        float b4 = (float) g3.c.b(d4);
        float f5 = (float) g3.c.f(d4);
        long j14 = 1;
        long j15 = 2;
        long j16 = (j13 + 1) / 2;
        long j17 = (j4 - 1) / 2;
        if (j4 != 1) {
            for (long j18 = 0; j18 < j7; j18++) {
                dVar4.c(j9 + j18, dVar3.b(j8 + j18));
            }
            for (long j19 = 1; j19 < j13; j19 += j14) {
                long j20 = j19 * j6 * j4;
                long j21 = 0;
                while (j21 < j6) {
                    long j22 = (j21 * j4) + j20;
                    long j23 = j14;
                    dVar4.c(j9 + j22, dVar3.b(j8 + j22));
                    j21 += j23;
                    j14 = j23;
                    j15 = j15;
                }
            }
            j11 = j14;
            j12 = j15;
            if (j17 <= j6) {
                for (long j24 = j11; j24 < j13; j24 += j11) {
                    if (j12 < j4) {
                        throw null;
                    }
                }
            } else {
                for (long j25 = j11; j25 < j13; j25 += j11) {
                    for (long j26 = 0; j26 < j6; j26 += j11) {
                        if (j12 < j4) {
                            throw null;
                        }
                    }
                }
            }
            if (j17 >= j6) {
                long j27 = j11;
                while (j27 < j16) {
                    long j28 = j27 * j6 * j4;
                    long j29 = (j13 - j27) * j6 * j4;
                    for (long j30 = 0; j30 < j6; j30 += j11) {
                        long j31 = j30 * j4;
                        long j32 = j31 + j28;
                        long j33 = j31 + j29;
                        long j34 = j12;
                        while (j34 < j4) {
                            long j35 = j8 + j34;
                            long j36 = j9 + j34;
                            long j37 = j35 + j32;
                            long j38 = j35 + j33;
                            long j39 = j36 + j32;
                            long j40 = j36 + j33;
                            float b5 = dVar4.b(j39 - j11);
                            float b6 = dVar4.b(j39);
                            float b7 = dVar4.b(j40 - j11);
                            float b8 = dVar4.b(j40);
                            dVar3.c(j37, AbstractC1518b.j(b5, b7, dVar3, j37 - j11, b6, b8));
                            dVar3.c(j38, AbstractC1518b.l(b6, b8, dVar3, j38 - j11, b7, b5));
                            j34 += j12;
                            b4 = b4;
                            f5 = f5;
                        }
                    }
                    j27 += j11;
                    j13 = j5;
                }
                f = b4;
                f4 = f5;
            } else {
                f = b4;
                f4 = f5;
                long j41 = j11;
                while (j41 < j16) {
                    long j42 = j41 * j6 * j4;
                    long j43 = (j5 - j41) * j6 * j4;
                    long j44 = j12;
                    while (j44 < j4) {
                        long j45 = j8 + j44;
                        long j46 = j9 + j44;
                        long j47 = 0;
                        while (j47 < j6) {
                            long j48 = j47 * j4;
                            long j49 = j48 + j42;
                            long j50 = j48 + j43;
                            long j51 = j45 + j49;
                            long j52 = j41;
                            long j53 = j45 + j50;
                            long j54 = j46 + j49;
                            long j55 = j46 + j50;
                            float b9 = dVar4.b(j54 - j11);
                            float b10 = dVar4.b(j54);
                            float b11 = dVar4.b(j55 - j11);
                            float b12 = dVar4.b(j55);
                            dVar3.c(j51, AbstractC1518b.j(b9, b11, dVar3, j51 - j11, b10, b12));
                            dVar3.c(j53, AbstractC1518b.l(b10, b12, dVar3, j53 - j11, b11, b9));
                            j47 += j11;
                            dVar4 = dVar2;
                            j41 = j52;
                            j42 = j42;
                        }
                        j44 += j12;
                        dVar4 = dVar2;
                    }
                    j41 += j11;
                    dVar4 = dVar2;
                }
                dVar4 = dVar2;
            }
        } else {
            f = b4;
            f4 = f5;
            j11 = 1;
            j12 = 2;
            r.a(j9, j8, j7, dVar4, dVar3);
            dVar3 = dVar3;
            dVar4 = dVar4;
        }
        long j56 = j11;
        while (j56 < j16) {
            long j57 = j56 * j6 * j4;
            long j58 = (j5 - j56) * j6 * j4;
            long j59 = 0;
            while (j59 < j6) {
                long j60 = j59 * j4;
                long j61 = j60 + j57;
                long j62 = j60 + j58;
                long j63 = j56;
                float b13 = dVar4.b(j9 + j61);
                float b14 = dVar4.b(j9 + j62);
                dVar3.c(j8 + j61, b13 + b14);
                dVar3.c(j8 + j62, b14 - b13);
                j59 += j11;
                j56 = j63;
            }
            j56 += j11;
        }
        long j64 = (j5 - j11) * j7;
        float f6 = 1.0f;
        float f7 = 0.0f;
        long j65 = j11;
        while (j65 < j16) {
            float f8 = (f * f6) - (f4 * f7);
            float f9 = (f7 * f) + (f4 * f6);
            long j66 = j65 * j7;
            long j67 = (j5 - j65) * j7;
            long j68 = 0;
            while (j68 < j7) {
                long j69 = j9 + j68;
                long j70 = j64;
                long j71 = j8 + j68;
                float f10 = f9;
                dVar4.c(j69 + j66, (dVar3.b(j71 + j7) * f8) + dVar3.b(j71));
                dVar4.c(j69 + j67, dVar3.b(j71 + j70) * f10);
                j68 += j11;
                f9 = f10;
                j64 = j70;
            }
            long j72 = j64;
            float f11 = f9;
            float f12 = f8;
            long j73 = j12;
            while (j73 < j16) {
                float f13 = (f8 * f12) - (f11 * f9);
                f9 = (f9 * f8) + (f12 * f11);
                long j74 = j73 * j7;
                long j75 = (j5 - j73) * j7;
                long j76 = 0;
                while (j76 < j7) {
                    long j77 = j9 + j76;
                    long j78 = j8 + j76;
                    long j79 = j73;
                    long j80 = j77 + j66;
                    float f14 = f9;
                    dVar4.c(j80, (dVar3.b(j78 + j74) * f13) + dVar4.b(j80));
                    long j81 = j77 + j67;
                    dVar4.c(j81, (dVar3.b(j78 + j75) * f14) + dVar4.b(j81));
                    j76 += j11;
                    f9 = f14;
                    j73 = j79;
                }
                j73 += j11;
                f12 = f13;
            }
            j65 += j11;
            f6 = f8;
            f7 = f11;
            j64 = j72;
        }
        for (long j82 = j11; j82 < j16; j82 += j11) {
            long j83 = j82 * j7;
            for (long j84 = 0; j84 < j7; j84 += j11) {
                long j85 = j9 + j84;
                dVar4.c(j85, dVar3.b(j8 + j84 + j83) + dVar4.b(j85));
            }
        }
        if (j4 >= j6) {
            for (long j86 = 0; j86 < j6; j86 += j11) {
                long j87 = j86 * j4;
                long j88 = j87 * j5;
                for (long j89 = 0; j89 < j4; j89 += j11) {
                    dVar3.c(j8 + j89 + j88, dVar4.b(j9 + j89 + j87));
                }
            }
        } else {
            for (long j90 = 0; j90 < j4; j90 += j11) {
                for (long j91 = 0; j91 < j6; j91 += j11) {
                    long j92 = j91 * j4;
                    dVar3.c((j92 * j5) + j8 + j90, dVar4.b(j9 + j90 + j92));
                }
            }
        }
        long j93 = j5 * j4;
        long j94 = j11;
        while (j94 < j16) {
            long j95 = j94 * j6 * j4;
            long j96 = (j5 - j94) * j6 * j4;
            long j97 = j94 * j12 * j4;
            long j98 = 0;
            while (j98 < j6) {
                long j99 = j98 * j4;
                long j100 = j98 * j93;
                long j101 = j93;
                dVar3.c(((((j8 + j4) - j11) + j97) - j4) + j100, dVar4.b(j9 + j99 + j95));
                dVar3.c(j8 + j97 + j100, dVar4.b(j9 + j99 + j96));
                j98 += j11;
                j93 = j101;
                j94 = j94;
            }
            j94 += j11;
        }
        long j102 = j93;
        if (j4 == 1) {
            return;
        }
        if (j17 >= j6) {
            long j103 = j11;
            while (j103 < j16) {
                long j104 = j103 * j6 * j4;
                long j105 = (j5 - j103) * j6 * j4;
                long j106 = j103 * j12 * j4;
                for (long j107 = 0; j107 < j6; j107 += j11) {
                    long j108 = j107 * j102;
                    long j109 = j107 * j4;
                    long j110 = j12;
                    while (j110 < j4) {
                        long j111 = j8 + j110 + j106 + j108;
                        long j112 = (((j8 + (j4 - j110)) + j106) - j4) + j108;
                        long j113 = j9 + j110 + j109;
                        long j114 = j103;
                        long j115 = j113 + j104;
                        long j116 = j113 + j105;
                        float b15 = dVar4.b(j115 - j11);
                        float b16 = dVar4.b(j115);
                        float b17 = dVar4.b(j116 - j11);
                        float b18 = dVar4.b(j116);
                        dVar3.c(j111 - j11, b15 + b17);
                        dVar3.c(j111, AbstractC1518b.a(b15, b17, dVar3, j112 - j11, b16, b18));
                        dVar3.c(j112, b18 - b16);
                        j110 += j12;
                        j103 = j114;
                        j104 = j104;
                    }
                }
                j103 += j11;
            }
            return;
        }
        long j117 = j11;
        while (j117 < j16) {
            long j118 = j117 * j6 * j4;
            long j119 = (j5 - j117) * j6 * j4;
            long j120 = j117 * j12 * j4;
            long j121 = j12;
            while (j121 < j4) {
                long j122 = j8 + j121;
                long j123 = j8 + (j4 - j121);
                long j124 = j9 + j121;
                long j125 = 0;
                while (j125 < j6) {
                    long j126 = j125 * j102;
                    long j127 = j122 + j120 + j126;
                    long j128 = ((j123 + j120) - j4) + j126;
                    long j129 = j124 + (j125 * j4);
                    long j130 = j129 + j118;
                    long j131 = j129 + j119;
                    long j132 = j117;
                    float b19 = dVar4.b(j130 - j11);
                    float b20 = dVar4.b(j130);
                    float b21 = dVar4.b(j131 - j11);
                    float b22 = dVar4.b(j131);
                    dVar3.c(j127 - j11, b19 + b21);
                    dVar3.c(j127, AbstractC1518b.a(b19, b21, dVar3, j128 - j11, b20, b22));
                    dVar3.c(j128, b22 - b20);
                    j125 += j11;
                    dVar4 = dVar2;
                    j118 = j118;
                    j117 = j132;
                }
                j121 += j12;
                dVar4 = dVar2;
            }
            j117 += j11;
            dVar4 = dVar2;
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:132:0x0395  */
    /* JADX WARN: Removed duplicated region for block: B:134:0x0397  */
    /* JADX WARN: Type inference failed for: r9v7, types: [k3.g, k3.d] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    public final void k(float[] fArr, int i4) {
        d dVar;
        float[] fArr2;
        int i5;
        int i6;
        long j4;
        long j5;
        k3.d dVar2;
        long j6;
        long j7;
        d dVar3 = this;
        float[] fArr3 = fArr;
        int i7 = dVar3.f11708k;
        int i8 = dVar3.f11700a;
        boolean z3 = dVar3.f11709l;
        Class<d> cls = d.class;
        int i9 = 4;
        if (z3) {
            k3.d gVar = new k3.d();
            gVar.f = q.f12106g;
            gVar.f12103h = 4L;
            gVar.f12102g = fArr3.length;
            gVar.f12096k = fArr3;
            long j8 = i4;
            long j9 = dVar3.f11701b;
            if (!z3) {
                if (j8 < 2147483647L) {
                    dVar3.k(fArr3, (int) j8);
                } else {
                    throw new IllegalArgumentException("The data array is too big.");
                }
            } else {
                long j10 = 1;
                if (j9 != 1) {
                    int a4 = AbstractC1880e.a(i7);
                    long j11 = 0;
                    if (a4 != 0) {
                        if (a4 != 1) {
                            if (a4 == 2) {
                                long j12 = 0 * 2;
                                k3.d dVar4 = new k3.d(j12, true);
                                int i10 = k3.c.c;
                                if (i10 > 1 && j9 > 8192) {
                                    if (i10 < 4 || j9 <= 65536) {
                                        i9 = 2;
                                    }
                                    Future[] futureArr = new Future[i9];
                                    long j13 = i9;
                                    long j14 = j9 / j13;
                                    int i11 = 0;
                                    while (i11 < i9) {
                                        Class<d> cls2 = cls;
                                        long j15 = i11 * j14;
                                        if (i11 == i9 - 1) {
                                            j7 = j9;
                                        } else {
                                            j7 = j15 + j14;
                                        }
                                        int i12 = i11;
                                        k3.d dVar5 = dVar4;
                                        long j16 = j8;
                                        dVar3 = this;
                                        j8 = j16;
                                        futureArr[i12] = k3.c.a(new b(dVar3, j15, j7, j16, dVar5, gVar));
                                        i11 = i12 + 1;
                                        j13 = j13;
                                        cls = cls2;
                                        j12 = j12;
                                        dVar4 = dVar5;
                                    }
                                    j5 = j12;
                                    dVar2 = dVar4;
                                    long j17 = j13;
                                    Class<d> cls3 = cls;
                                    try {
                                        k3.c.b(futureArr);
                                    } catch (InterruptedException e4) {
                                        Logger.getLogger(cls3.getName()).log(Level.SEVERE, (String) null, (Throwable) e4);
                                    } catch (ExecutionException e5) {
                                        Logger.getLogger(cls3.getName()).log(Level.SEVERE, (String) null, (Throwable) e5);
                                    }
                                    j3.c.h(j5, dVar2, dVar3.f11702d, dVar3.f11705h, dVar3.f);
                                    long j18 = 0 / j17;
                                    for (int i13 = 0; i13 < i9; i13++) {
                                        long j19 = i13 * j18;
                                        if (i13 == i9 - 1) {
                                            j6 = 0;
                                        } else {
                                            j6 = j19 + j18;
                                        }
                                        futureArr[i13] = k3.c.a(new c(dVar3, j19, j6, dVar2));
                                    }
                                    try {
                                        k3.c.b(futureArr);
                                    } catch (InterruptedException e6) {
                                        Logger.getLogger(cls3.getName()).log(Level.SEVERE, (String) null, (Throwable) e6);
                                    } catch (ExecutionException e7) {
                                        Logger.getLogger(cls3.getName()).log(Level.SEVERE, (String) null, (Throwable) e7);
                                    }
                                } else {
                                    j5 = j12;
                                    dVar2 = dVar4;
                                    if (0 >= j9) {
                                        j3.c.h(j5, dVar2, dVar3.f11702d, dVar3.f11705h, dVar3.f);
                                        if (0 < 0) {
                                            dVar2.b(0 * 2);
                                            throw null;
                                        }
                                    } else {
                                        gVar.b(j8 + 0);
                                        throw null;
                                    }
                                }
                                j3.c.r(j5, dVar2, 0L, dVar3.f11702d, dVar3.f11705h, dVar3.f);
                                if (j9 % 2 == 0) {
                                    throw null;
                                }
                                throw null;
                            }
                        } else {
                            if (j9 != 1) {
                                new k3.d(j9, true);
                                throw null;
                            }
                            while (true) {
                                j9--;
                                if (j9 < 2) {
                                    break;
                                }
                                long j20 = j8 + j9;
                                float b4 = gVar.b(j20);
                                long j21 = j20 - 1;
                                gVar.c(j20, gVar.b(j21));
                                gVar.c(j21, b4);
                            }
                        }
                    } else {
                        long j22 = dVar3.f11701b;
                        if (j22 > 4) {
                            j3.c.r(j22, gVar, j8, dVar3.f11702d, dVar3.f11705h, dVar3.f);
                            long j23 = dVar3.f11707j;
                            k3.d dVar6 = dVar3.f;
                            long j24 = dVar3.f11705h;
                            long j25 = j9 >> 1;
                            long j26 = (j23 * 2) / j25;
                            long j27 = 2;
                            while (j27 < j25) {
                                j11 += j26;
                                long j28 = j10;
                                float b5 = 0.5f - dVar6.b((j24 + j23) - j11);
                                float b6 = dVar6.b(j24 + j11);
                                k3.d dVar7 = dVar6;
                                long j29 = j8 + j27;
                                long j30 = j8 + (j9 - j27);
                                float b7 = gVar.b(j29) - gVar.b(j30);
                                long j31 = j23;
                                long j32 = j29 + j28;
                                long j33 = j24;
                                long j34 = j30 + j28;
                                float b8 = gVar.b(j34) + gVar.b(j32);
                                float f = (b5 * b7) - (b6 * b8);
                                float f4 = (b7 * b6) + (b5 * b8);
                                gVar.c(j29, gVar.b(j29) - f);
                                gVar.c(j32, f4 - gVar.b(j32));
                                gVar.c(j30, gVar.b(j30) + f);
                                gVar.c(j34, f4 - gVar.b(j34));
                                j27 += 2;
                                j10 = j28;
                                dVar6 = dVar7;
                                j23 = j31;
                                j24 = j33;
                            }
                            j4 = j10;
                            long j35 = j8 + j25 + j4;
                            gVar.c(j35, -gVar.b(j35));
                        } else {
                            j4 = 1;
                            if (j22 == 4) {
                                long j36 = j8 + 2;
                                float b9 = gVar.b(j8) - gVar.b(j36);
                                long j37 = j8 + 1;
                                long j38 = 3 + j8;
                                float b10 = gVar.b(j38) + (-gVar.b(j37));
                                gVar.c(j8, gVar.b(j36) + gVar.b(j8));
                                gVar.c(j37, gVar.b(j38) + gVar.b(j37));
                                gVar.c(j36, b9);
                                gVar.c(j38, b10);
                            }
                        }
                        long j39 = j8 + j4;
                        float b11 = gVar.b(j8) - gVar.b(j39);
                        gVar.c(j8, gVar.b(j39) + gVar.b(j8));
                        gVar.c(j39, b11);
                    }
                }
            }
        } else {
            int i14 = 0;
            if (i8 != 1) {
                int a5 = AbstractC1880e.a(i7);
                if (a5 != 0) {
                    if (a5 != 1) {
                        if (a5 == 2) {
                            int i15 = 0 * 2;
                            float[] fArr4 = new float[i15];
                            int i16 = k3.c.c;
                            if (i16 > 1) {
                                long j40 = i8;
                                if (j40 >= 8192) {
                                    if (i16 < 4 || j40 < 65536) {
                                        i9 = 2;
                                    }
                                    Future[] futureArr2 = new Future[i9];
                                    int i17 = i8 / i9;
                                    int i18 = 0;
                                    while (i18 < i9) {
                                        int i19 = i18 * i17;
                                        if (i18 == i9 - 1) {
                                            i6 = i8;
                                        } else {
                                            i6 = i19 + i17;
                                        }
                                        futureArr2[i18] = k3.c.a(new a(this, i19, i6, i4, fArr4, fArr3));
                                        i18++;
                                        fArr3 = fArr;
                                    }
                                    dVar = this;
                                    fArr2 = fArr4;
                                    try {
                                        k3.c.b(futureArr2);
                                    } catch (InterruptedException e8) {
                                        Logger.getLogger(cls.getName()).log(Level.SEVERE, (String) null, (Throwable) e8);
                                    } catch (ExecutionException e9) {
                                        Logger.getLogger(cls.getName()).log(Level.SEVERE, (String) null, (Throwable) e9);
                                    }
                                    j3.c.g(i15, fArr2, dVar.c, dVar.f11704g, dVar.f11703e);
                                    int i20 = 0 / i9;
                                    for (int i21 = 0; i21 < i9; i21++) {
                                        int i22 = i21 * i20;
                                        if (i21 == i9 - 1) {
                                            i5 = 0;
                                        } else {
                                            i5 = i22 + i20;
                                        }
                                        futureArr2[i21] = k3.c.a(new I0.d(dVar, i22, i5, fArr2));
                                    }
                                    try {
                                        k3.c.b(futureArr2);
                                    } catch (InterruptedException e10) {
                                        Logger.getLogger(cls.getName()).log(Level.SEVERE, (String) null, (Throwable) e10);
                                    } catch (ExecutionException e11) {
                                        Logger.getLogger(cls.getName()).log(Level.SEVERE, (String) null, (Throwable) e11);
                                    }
                                    j3.c.q(i15, fArr2, 0, dVar.c, dVar.f11704g, dVar.f11703e);
                                    if (i8 % 2 != 0) {
                                        throw null;
                                    }
                                    throw null;
                                }
                            }
                            dVar = this;
                            fArr2 = fArr4;
                            if (i8 <= 0) {
                                j3.c.g(i15, fArr2, dVar.c, dVar.f11704g, dVar.f11703e);
                                j3.c.q(i15, fArr2, 0, dVar.c, dVar.f11704g, dVar.f11703e);
                                if (i8 % 2 != 0) {
                                }
                            } else {
                                float f5 = fArr[i4 + 0];
                                throw null;
                            }
                        }
                    }
                } else {
                        // smali cond_22(a5==0, k=1 时运行时路径!): 单线程 FFT + 后处理
                        // jadx 原把这段错塞进 if(i8!=1) 的 else(死代码), a5==0 的 else 是幻觉(throw null)
                        int i26 = this.f11700a;
                        if (i26 > 4) {
                            j3.c.q(i26, fArr, i4, this.c, this.f11704g, this.f11703e);
                            int i27 = this.f11706i;
                            float[] fArr6 = this.f11703e;
                            int i28 = this.f11704g;
                            int i29 = i8 >> 1;
                            int i30 = (i27 * 2) / i29;
                            for (int i31 = 2; i31 < i29; i31 += 2) {
                                i14 += i30;
                                float f7 = 0.5f - fArr6[(i28 + i27) - i14];
                                float f8 = fArr6[i28 + i14];
                                int i32 = i4 + i31;
                                int i33 = i4 + (i8 - i31);
                                float f9 = fArr[i32];
                                float f10 = f9 - fArr[i33];
                                int i34 = i32 + 1;
                                int i35 = i33 + 1;
                                float f11 = fArr[i34] + fArr[i35];
                                float f12 = (f7 * f10) - (f8 * f11);
                                float f13 = (f8 * f10) + (f7 * f11);
                                fArr[i32] = f9 - f12;
                                fArr[i34] = f13 - fArr[i34];
                                fArr[i33] = fArr[i33] + f12;
                                fArr[i35] = f13 - fArr[i35];
                            }
                            int i36 = i4 + i29 + 1;
                            fArr[i36] = -fArr[i36];
                        } else if (i26 == 4) {
                            float f14 = fArr[i4];
                            int i37 = i4 + 2;
                            float f15 = fArr[i37];
                            int i38 = i4 + 1;
                            int i39 = i4 + 3;
                            float f16 = (-fArr[i38]) + fArr[i39];
                            fArr[i4] = f14 + f15;
                            fArr[i38] = fArr[i38] + fArr[i39];
                            fArr[i37] = f14 - f15;
                            fArr[i39] = f16;
                        }
                        float f17 = fArr[i4];
                        int i40 = i4 + 1;
                        float f18 = fArr[i40];
                        fArr[i4] = f17 + f18;
                        fArr[i40] = f17 - f18;
                    }
                }
            }
        }
    }
