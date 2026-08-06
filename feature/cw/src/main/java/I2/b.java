package I2;

import android.content.Context;
import android.content.SharedPreferences;
import pas.decoder;

/* loaded from: classes.dex */
public final class b {

    /* renamed from: b, reason: collision with root package name */
    public static final String[] f663b = {"bg_color", "text_color", "text_color_weak", "call_color", "call_color_weak", "cq_color", "cq_color_weak", "rst_color", "rst_color_weak"};
    public static final String[] c = {"Background color", "Text color", "Text color, weak", "Callsign color", "Callsign color, weak", "CQ color", "CQ color, weak", "RST color", "RST color, weak"};

    /* renamed from: d, reason: collision with root package name */
    public static final int[] f664d = {-3084048, -16777216, -5592406, -65536, -30584, -16776961, -7829249, -65281, -30465};

    /* renamed from: a, reason: collision with root package name */
    public final int[] f665a = (int[]) f664d.clone();

    public final int a(decoder.TCharInfo tCharInfo) {
        boolean lowSnr = tCharInfo.getLowSnr();
        int[] iArr = this.f665a;
        if (lowSnr) {
            int i4 = tCharInfo.getWordType().Value;
            if (i4 != 0) {
                if (i4 != 1) {
                    if (i4 != 2) {
                        if (i4 != 3) {
                            if (i4 != 4) {
                                if (i4 == 5) {
                                    return iArr[4];
                                }
                                return -16777216;
                            }
                            return iArr[2];
                        }
                        return iArr[6];
                    }
                    return iArr[8];
                }
                return iArr[2];
            }
            return iArr[2];
        }
        int i5 = tCharInfo.getWordType().Value;
        if (i5 != 0) {
            if (i5 != 1) {
                if (i5 != 2) {
                    if (i5 != 3) {
                        if (i5 != 4) {
                            if (i5 != 5) {
                                return -16777216;
                            }
                            return iArr[3];
                        }
                        return iArr[1];
                    }
                    return iArr[5];
                }
                return iArr[7];
            }
            return iArr[1];
        }
        return iArr[1];
    }

    public final void b(Context context) {
        SharedPreferences sharedPreferences = context.getSharedPreferences(context.getPackageName() + "_preferences", 0);  // 原 c0.x.a(context)
        for (int i4 = 0; i4 < 9; i4++) {
            this.f665a[i4] = sharedPreferences.getInt(f663b[i4], f664d[i4]);
        }
    }
}
