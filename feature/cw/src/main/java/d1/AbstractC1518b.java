package d1;

/* renamed from: d1.b, reason: case insensitive filesystem */
/* 照搬自 Morse Expert 1.15, 混淆名保持。b(Parcel)/c(y2.d) 为库工具已裁剪(L1/y2 库依赖)。 */
public abstract /* synthetic */ class AbstractC1518b {
    public static float a(float f, float f4, k3.d dVar, long j4, float f5, float f6) {
        dVar.c(j4, f - f4);
        return f5 + f6;
    }

    public static String d(String str, int i4) {
        return str + i4;
    }

    public static String e(String str, String str2) {
        return str + str2;
    }

    public static String f(String str, String str2, String str3) {
        return str + str2 + str3;
    }

    public static String g(StringBuilder sb, int i4, String str) {
        sb.append(i4);
        sb.append(str);
        return sb.toString();
    }

    public static String h(StringBuilder sb, String str, String str2) {
        sb.append(str);
        sb.append(str2);
        return sb.toString();
    }

    public static /* synthetic */ boolean i(Object obj) {
        return obj != null;
    }

    public static float j(float f, float f4, k3.d dVar, long j4, float f5, float f6) {
        dVar.c(j4, f + f4);
        return f5 + f6;
    }

    public static String k(String str, String str2) {
        return str + str2;
    }

    public static float l(float f, float f4, k3.d dVar, long j4, float f5, float f6) {
        dVar.c(j4, f - f4);
        return f5 - f6;
    }

    public static float m(float f, float f4, k3.d dVar, long j4, float f5, float f6) {
        dVar.c(j4, f + f4);
        return f5 - f6;
    }

    public static /* synthetic */ String n(int i4) {
        switch (i4) {
            case 1:
                return "NONE";
            case 2:
                return "LEFT";
            case 3:
                return "TOP";
            case 4:
                return "RIGHT";
            case 5:
                return "BOTTOM";
            case 6:
                return "BASELINE";
            case 7:
                return "CENTER";
            case 8:
                return "CENTER_X";
            case 9:
                return "CENTER_Y";
            default:
                throw null;
        }
    }
}
