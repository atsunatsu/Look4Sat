package H2;

import B.RunnableC0001b;
import D.n;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import com.ve3nea.morse_expert.MainActivity;
import com.ve3nea.morse_expert.WaterfallSurfaceView;
import i3.d;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import k3.s;
import pas.decoder;
import pas.nativedecoder;

/* loaded from: classes.dex */
public final class b {

    /* renamed from: A, reason: collision with root package name */
    public static final int f602A;

    /* renamed from: B, reason: collision with root package name */
    public static final int f603B;

    /* renamed from: C, reason: collision with root package name */
    public static final int f604C;

    /* renamed from: D, reason: collision with root package name */
    public static final d f605D;

    /* renamed from: z, reason: collision with root package name */
    public static final int f606z;

    /* renamed from: a, reason: collision with root package name */
    public final float[] f607a;

    /* renamed from: b, reason: collision with root package name */
    public final float[] f608b;
    public final float[] c;

    /* renamed from: d, reason: collision with root package name */
    public final float[] f609d;

    /* renamed from: e, reason: collision with root package name */
    public final nativedecoder.TNativeDecoder f610e;
    public int f;

    /* renamed from: g, reason: collision with root package name */
    public int f611g;

    /* renamed from: h, reason: collision with root package name */
    public int f612h;

    /* renamed from: i, reason: collision with root package name */
    public decoder.TDecoderState f613i;

    /* renamed from: j, reason: collision with root package name */
    public String f614j;

    /* renamed from: k, reason: collision with root package name */
    public final c f615k;

    /* renamed from: l, reason: collision with root package name */
    public int f616l;

    /* renamed from: m, reason: collision with root package name */
    public final I2.b f617m;

    /* renamed from: n, reason: collision with root package name */
    public final Handler f618n;

    /* renamed from: o, reason: collision with root package name */
    public WaterfallSurfaceView f619o;

    /* renamed from: p, reason: collision with root package name */
    public MainActivity f620p;

    /* renamed from: q, reason: collision with root package name */
    public MainActivity f621q;

    /* renamed from: r, reason: collision with root package name */
    public final AtomicInteger f622r;

    /* renamed from: s, reason: collision with root package name */
    public final AtomicBoolean f623s;

    /* renamed from: t, reason: collision with root package name */
    public final Context f624t;

    /* renamed from: u, reason: collision with root package name */
    public int f625u;

    /* renamed from: v, reason: collision with root package name */
    public final AtomicBoolean f626v;

    /* renamed from: w, reason: collision with root package name */
    public DataOutputStream f627w;

    /* renamed from: x, reason: collision with root package name */
    public int f628x;

    /* renamed from: y, reason: collision with root package name */
    public String f629y;

    /* JADX WARN: Removed duplicated region for block: B:14:0x012d  */
    /* JADX WARN: Type inference failed for: r0v4, types: [i3.d, java.lang.Object] */
    static {
        boolean z3;
        float f;
        float f4;
        float f5;
        int round = Math.round(25.6f);
        f606z = round;
        int round2 = Math.round(153.6f);
        f602A = round2;
        f603B = round2 - round;
        f604C = Math.round(85.333336f);
        i3.d obj = new i3.d();
        if (2048 > 1073741824) {
            z3 = true;
        } else {
            z3 = false;
        }
        obj.f11709l = z3;
        int i4 = (int) 1024;
        obj.f11700a = i4;
        obj.f11701b = 1024L;
        if (!z3) {
            obj.f11708k = 1;
            int[] iArr = new int[((int) g3.c.a((1 << (((int) (g3.c.d(((float) 1024) + 0.5f) / g3.c.d(2.0d))) / 2)) + 2)) + 2];
            obj.c = iArr;
            float[] fArr = new float[i4];
            obj.f11703e = fArr;
            int i5 = (i4 * 2) >> 2;
            obj.f11704g = i5;
            iArr[0] = i5;
            iArr[1] = 1;
            if (i5 > 2) {
                int i6 = i5 >> 1;
                float f6 = 0.7853982f / i6;
                float f7 = 2.0f * f6;
                float b4 = (float) g3.c.b(i6 * f6);  // r9 = i6 (smali)
                fArr[0] = 1.0f;
                fArr[1] = b4;
                if (i6 == 4) {
                    double d4 = f7;
                    f4 = 0.5f;
                    fArr[2] = (float) g3.c.b(d4);
                    fArr[3] = (float) g3.c.f(d4);
                } else {
                    f4 = 0.5f;
                    if (i6 > 4) {
                        iArr[2] = 0;
                        iArr[3] = 16;
                        int i7 = i5;
                        int i8 = 2;
                        f5 = 1.0f;
                        while (i7 > 32) {
                            int i9 = i8 << 1;
                            int i10 = i8 << 4;
                            for (int i11 = i8; i11 < i9; i11++) {
                                int i12 = iArr[i11] << 2;
                                iArr[i8 + i11] = i12;
                                iArr[i9 + i11] = i12 + i10;
                            }
                            i7 >>= 2;
                            i8 = i9;
                        }
                        fArr[2] = 0.5f / ((float) g3.c.b(f7));
                        fArr[3] = 0.5f / ((float) g3.c.b(6.0f * f6));
                        for (int i13 = 4; i13 < i6; i13 += 4) {
                            float f8 = i13 * f6;
                            double d5 = f8;
                            fArr[i13] = (float) g3.c.b(d5);
                            fArr[i13 + 1] = (float) g3.c.f(d5);
                            double d6 = 3.0f * f8;
                            fArr[i13 + 2] = (float) g3.c.b(d6);
                            fArr[i13 + 3] = -((float) g3.c.f(d6));
                        }
                        int i14 = 0;
                        while (i6 > 2) {
                            int i15 = i14 + i6;
                            i6 >>= 1;
                            fArr[i15] = f5;
                            fArr[i15 + 1] = b4;
                            if (i6 == 4) {
                                float f9 = fArr[i14 + 4];
                                float f10 = fArr[i14 + 5];
                                fArr[i15 + 2] = f9;
                                fArr[i15 + 3] = f10;
                            } else if (i6 > 4) {
                                float f11 = fArr[i14 + 4];
                                float f12 = fArr[i14 + 6];
                                fArr[i15 + 2] = f4 / f11;
                                fArr[i15 + 3] = f4 / f12;
                                for (int i16 = 4; i16 < i6; i16 += 4) {
                                    int i17 = (i16 * 2) + i14;
                                    int i18 = i15 + i16;
                                    float f13 = fArr[i17];
                                    float f14 = fArr[i17 + 1];
                                    float f15 = fArr[i17 + 2];
                                    float f16 = fArr[i17 + 3];
                                    fArr[i18] = f13;
                                    fArr[i18 + 1] = f14;
                                    fArr[i18 + 2] = f15;
                                    fArr[i18 + 3] = f16;
                                }
                            }
                            i14 = i15;
                        }
                    }
                }
                f5 = 1.0f;
                int i142 = 0;
                while (i6 > 2) {
                }
            }
            int i19 = i4 >> 2;
            obj.f11706i = i19;
            iArr[1] = i19;
            if (i19 > 1) {
                int i20 = i19 >> 1;
                float f17 = 0.7853982f / i20;
                float b5 = (float) g3.c.b(i20 * f17);  // r6 = i20 (smali)
                fArr[i5] = b5;
                fArr[i5 + i20] = b5 * 0.5f;
                for (int i21 = 1; i21 < i20; i21++) {
                    double d7 = i21 * f17;
                    fArr[i5 + i21] = ((float) g3.c.b(d7)) * 0.5f;
                    fArr[(i5 + i19) - i21] = ((float) g3.c.f(d7)) * 0.5f;
                }
            }
        } else {
            obj.f11708k = 1;
            s sVar = new s(((long) g3.c.a((1 << ((int) (((long) (g3.c.d(((float) 1024) + 0.5f) / g3.c.d(2.0d))) / 2))) + 2)) + 2, true);
            obj.f11702d = sVar;
            k3.d dVar = new k3.d(1024L, true);
            obj.f = dVar;
            obj.f11705h = 512L;
            sVar.c(0L, 512L);
            sVar.c(1L, 1L);
            if (512 > 2) {
                long j4 = 512 >> 1;
                float f18 = 0.7853982f / ((float) j4);
                float f19 = f18 * 2.0f;
                float b6 = (float) g3.c.b(j4 * f18);  // r14 = j4 (smali 同模式)
                dVar.c(0L, 1.0f);
                dVar.c(1L, b6);
                if (j4 == 4) {
                    f = 0.5f;
                    double d8 = f19;
                    dVar.c(2L, (float) g3.c.b(d8));
                    dVar.c(3L, (float) g3.c.f(d8));
                } else {
                    f = 0.5f;
                    if (j4 > 4) {
                        sVar.c(2L, 0L);
                        sVar.c(3L, 16L);
                        long j5 = 2;
                        long j6 = 512;
                        while (j6 > 32) {
                            long j7 = j5 << 1;
                            long j8 = j5 << 4;
                            long j9 = j5;
                            while (j9 < j7) {
                                long b7 = sVar.b(j9) << 2;
                                sVar.c(j5 + j9, b7);
                                sVar.c(j7 + j9, b7 + j8);
                                j9++;
                                f19 = f19;
                            }
                            j6 >>= 2;
                            j5 = j7;
                        }
                        dVar.c(2L, 0.5f / ((float) g3.c.b(f19)));
                        dVar.c(3L, 0.5f / ((float) g3.c.b(6.0f * f18)));
                        long j10 = 4;
                        while (j10 < j4) {
                            float f20 = ((float) j10) * f18;
                            double d9 = f20;
                            dVar.c(j10, (float) g3.c.b(d9));
                            dVar.c(j10 + 1, (float) g3.c.f(d9));
                            double d10 = 3.0f * f20;
                            long j11 = j10;
                            dVar.c(j10 + 2, (float) g3.c.b(d10));
                            dVar.c(j11 + 3, -((float) g3.c.f(d10)));
                            j10 = j11 + 4;
                        }
                    }
                }
                long j12 = 0;
                long j13 = 2;
                while (j4 > j13) {
                    long j14 = j12 + j4;
                    j4 >>= 1;
                    dVar.c(j14, 1.0f);
                    dVar.c(j14 + 1, b6);
                    if (j4 == 4) {
                        float b8 = dVar.b(j12 + 4);
                        float b9 = dVar.b(j12 + 5);
                        j13 = 2;
                        dVar.c(j14 + 2, b8);
                        dVar.c(j14 + 3, b9);
                    } else {
                        j13 = 2;
                        if (j4 > 4) {
                            float b10 = dVar.b(j12 + 4);
                            float b11 = dVar.b(6 + j12);
                            dVar.c(j14 + 2, f / b10);
                            dVar.c(j14 + 3, f / b11);
                            long j15 = 4;
                            while (j15 < j4) {
                                long j16 = (j15 * 2) + j12;
                                float f21 = b6;
                                long j17 = j14 + j15;
                                long j18 = j12;
                                float b12 = dVar.b(j16);
                                long j19 = j14;
                                float b13 = dVar.b(j16 + 1);
                                float b14 = dVar.b(j16 + 2);
                                float b15 = dVar.b(j16 + 3);
                                dVar.c(j17, b12);
                                dVar.c(j17 + 1, b13);
                                dVar.c(j17 + 2, b14);
                                dVar.c(j17 + 3, b15);
                                j15 += 4;
                                b6 = f21;
                                j12 = j18;
                                j14 = j19;
                            }
                        }
                    }
                    b6 = b6;
                    j12 = j14;
                }
            }
            long j20 = 256;
            obj.f11707j = 256L;
            long j21 = 1;
            sVar.c(1L, 256L);
            if (256 > 1) {
                long j22 = 256 >> 1;
                float f22 = 0.7853982f / ((float) j22);
                long j23 = 512;
                dVar.c(512L, (float) g3.c.b(j22 * f22));  // r1 = j22 (smali)
                dVar.c(512 + j22, dVar.b(512L) * 0.5f);
                long j24 = 1;
                while (j24 < j22) {
                    long j25 = j20;
                    long j26 = j21;
                    double d11 = ((float) j24) * f22;
                    long j27 = j23;
                    dVar.c(j23 + j24, ((float) g3.c.b(d11)) * 0.5f);
                    dVar.c((j27 + j25) - j24, ((float) g3.c.f(d11)) * 0.5f);
                    j24 += j26;
                    j20 = j25;
                    j21 = j26;
                    j23 = j27;
                }
            }
        }
        f605D = obj;
    }

    public b(Context context) {
        float[] fArr = new float[1024];
        fArr[512] = 1.0f;
        for (int i4 = 1; i4 < 512; i4++) {
            double d4 = (i4 * 6.283185307179586d) / 1024;
            float cos = (float) ((Math.cos(d4 * 2.0d) * 0.08d) + (0.42d - (Math.cos(d4) * 0.5d)));
            fArr[i4] = cos;
            double d5 = 0.003125f * 6.283185307179586d * (i4 - 512);
            float sin = (float) ((Math.sin(d5) / d5) * cos);
            fArr[i4] = sin;
            fArr[1024 - i4] = sin;
        }
        this.f607a = fArr;
        this.f608b = new float[1024];
        this.c = new float[1024];
        this.f609d = new float[f603B];
        this.f610e = nativedecoder.TNativeDecoder.Create();
        this.f614j = "";
        this.f615k = new c(4000, 0);
        I2.b bVar = new I2.b();
        this.f617m = bVar;
        this.f618n = new Handler(Looper.getMainLooper());
        this.f622r = new AtomicInteger();
        this.f623s = new AtomicBoolean(false);
        this.f625u = 0;
        this.f626v = new AtomicBoolean(false);
        this.f628x = 0;
        this.f624t = context;
        bVar.b(context);
    }

    /* JADX WARN: Removed duplicated region for block: B:57:0x01e2  */
    /* JADX WARN: Removed duplicated region for block: B:63:0x020a A[LOOP:6: B:62:0x0208->B:63:0x020a, LOOP_END] */
    /* JADX WARN: Removed duplicated region for block: B:66:0x0216 A[LOOP:7: B:65:0x0214->B:66:0x0216, LOOP_END] */
    /* JADX WARN: Removed duplicated region for block: B:70:0x0232  */
    /* JADX WARN: Removed duplicated region for block: B:73:? A[RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    public final void a(float[] fArr) {
        c cVar;
        float[] fArr2;
        float[] fArr3;
        b bVar;
        Exception exc;
        int i4;
        float f;
        int i5;
        WaterfallSurfaceView waterfallSurfaceView;
        int length = fArr.length;
        int i6 = 0;
        while (true) {
            cVar = this.f615k;
            if (i6 >= length) {
                break;
            }
            cVar.c(Math.abs(fArr[i6]));
            i6++;
        }
        this.f616l = Math.round(((float) Math.log10(Math.max(-0.5d, cVar.f633e))) * 20.0f);
        int i7 = f604C;
        int i8 = 1024 - i7;
        float[] fArr4 = this.f608b;
        System.arraycopy(fArr4, i7, fArr4, 0, i8);
        System.arraycopy(fArr, 0, fArr4, i8, i7);
        int i9 = 0;
        while (true) {
            fArr2 = this.c;
            if (i9 >= 1024) {
                break;
            }
            fArr2[i9] = fArr4[i9] * this.f607a[i9];
            i9++;
        }
        f605D.k(fArr2, 0);
        int i10 = f606z;
        int i11 = i10;
        while (true) {
            int i12 = f602A;
            float f4 = 0.0f;
            fArr3 = this.f609d;
            if (i11 < i12) {
                int i13 = i11 * 2;
                float f5 = fArr2[i13];
                float f6 = fArr2[i13 + 1];
                float f7 = ((f6 * f6) + (f5 * f5)) * 0.001f;
                if (!Float.isNaN(f7)) {
                    f4 = f7;
                }
                fArr3[i11 - i10] = f4;
                i11++;
            } else {
                // smali: 循环内无 try; 录音写入 try 在循环后(见下)
                break;
            }
        }
        DataOutputStream dataOutputStream = this.f627w;
        Context context = this.f624t;
        AtomicBoolean atomicBoolean = this.f626v;
        try { // smali try_start_0: 录音写入整块(原 jadx 误把 try 放 break 处)
            if (dataOutputStream == null && atomicBoolean.get()) {
                File file = new File(context.getExternalFilesDir(null), ((String) DateFormat.format("yyyy-MM-dd_HH_mm_ss", new Date())) + "_spectra.bin");
                this.f629y = file.getAbsoluteFile().toString();
                this.f627w = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file, false)));
                this.f628x = 0;
                this.f614j = " Recording...";
            }
            if (this.f627w != null) {
                for (float f8 : fArr3) {
                    this.f627w.writeFloat(f8);
                }
            }
            if (atomicBoolean.get()) {
                int i14 = this.f628x + 1;
                this.f628x = i14;
                if (i14 >= 2048) {
                    atomicBoolean.set(false);
                    this.f627w.close();
                    this.f627w = null;
                    this.f614j = "";
                    MediaScannerConnection.scanFile(context, new String[]{this.f629y}, new String[]{"application/x-binary"}, null);
                }
            }
        } catch (IOException e4) {
            Log.e("File write", "Exception: ", e4);
        }
        nativedecoder.TNativeDecoder tNativeDecoder = this.f610e;
        try {
            this.f613i = tNativeDecoder.ProcessSpectrum(fArr3);
            int i15 = this.f625u + 1;
            this.f625u = i15;
            Handler handler = this.f618n;
            if (i15 == 15) {
                try {
                    this.f625u = 0;
                    this.f = tNativeDecoder.getPitch();
                    this.f611g = tNativeDecoder.getSnr();
                    this.f612h = tNativeDecoder.getWpm();
                    handler.post(new B0.b(this, this.f614j, new int[]{this.f616l, this.f, this.f611g, this.f612h, tNativeDecoder.getSignalCount(), this.f613i.Value}));
                    AtomicInteger atomicInteger = this.f622r;
                    int i16 = atomicInteger.get();
                    if (i16 != 0) {
                        tNativeDecoder.LockToPitch(i16);
                        atomicInteger.set(0);
                    }
                    tNativeDecoder.setHamMode(this.f623s.get());
                } catch (Exception e5) {
                    // jadx 幻觉区(空循环/空 if 为 catch 错位还原)已裁剪; 原 smali: 记录异常后跳回正常流程尾部
                    exc = e5;
                    bVar = this;
                    Log.e("Audio Processor", "Exception", exc);
                }
            }
            int charCount = tNativeDecoder.getCharCount();
            if (charCount > 0) {
                SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder();
                int i18 = 0;
                while (i18 < charCount) {
                    decoder.TCharInfo GetChar = tNativeDecoder.GetChar(i18);
                    spannableStringBuilder.append(GetChar.getChar());
                    ForegroundColorSpan foregroundColorSpan = new ForegroundColorSpan(this.f617m.a(GetChar));
                    int i19 = i18 + 1;
                    spannableStringBuilder.setSpan(foregroundColorSpan, i18, i19, 33);
                    i18 = i19;
                }
                bVar = this;
                try {
                    handler.post(new RunnableC0001b(bVar, spannableStringBuilder, tNativeDecoder.getDeleteCount(), 2, false));
                } catch (Exception e6) {
                    // jadx 幻觉区已裁剪(同 catch e5); 原 smali: 记录异常后跳回正常流程尾部
                    exc = e6;
                    Log.e("Audio Processor", "Exception", exc);
                }
            } else {
                bVar = this;
            }
        } catch (Exception e7) {
            // jadx 幻觉(e 变量)已裁剪
            bVar = this;
        }
        i4 = f603B;
        int i1722 = i4 / 2;
        float[] copyOfRange22 = Arrays.copyOfRange(fArr3, i1722 - 20, i1722 + 20);
        Arrays.sort(copyOfRange22);
        f = copyOfRange22[20];
        if (f > 0.0f) {
            for (int i20 = 0; i20 < i4; i20++) {
                fArr3[i20] = fArr3[i20] / f;
            }
        }
        float[] fArr522 = (float[]) fArr3.clone();
        new c(12, 1).a(fArr522);
        new c(12, 0).a(fArr522);
        for (i5 = 0; i5 < i4; i5++) {
            fArr3[i5] = fArr3[i5] - fArr522[i5];
        }
        for (int i21 = 0; i21 < i4; i21++) {
            fArr3[i21] = ((float) Math.log10(Math.max(-0.5d, fArr3[i21]) + 1.0d)) * 10.0f;
        }
        waterfallSurfaceView = bVar.f619o;
        if (waterfallSurfaceView != null) {
            waterfallSurfaceView.queueEvent(new n(waterfallSurfaceView, 1, (float[]) fArr3.clone()));
        }
    }
}
