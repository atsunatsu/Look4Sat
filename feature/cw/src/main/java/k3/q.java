package k3;

/**
 * R8 生成的伪 enum(k3 FFT 库的数组类型枚举)。
 * smali 中 q 的直接子类:l(LONG/5)、m(FLOAT/6)、n(DOUBLE/7),
 * 其余常量(BYTE/UNSIGNED_BYTE/SHORT/INT/COMPLEX_FLOAT/COMPLEX_DOUBLE/STRING/OBJECT)
 * 直接以 q 实例表达。
 */
public class q {
    public static final l f;

    /* renamed from: g, reason: collision with root package name */
    public static final m f12106g;

    /* renamed from: h, reason: collision with root package name */
    public static final n f12107h;

    /* renamed from: i, reason: collision with root package name */
    public static final /* synthetic */ q[] f12108i;

    private final String name;
    private final int ordinal;

    protected q(String name, int ordinal) {
        this.name = name;
        this.ordinal = ordinal;
    }

    public final String name() {
        return name;
    }

    public final int ordinal() {
        return ordinal;
    }

    public static q valueOf(String str) {
        for (q qVar : f12108i) {
            if (qVar.name.equals(str)) {
                return qVar;
            }
        }
        throw new IllegalArgumentException("No enum constant k3.q." + str);
    }

    public static q[] values() {
        return (q[]) f12108i.clone();
    }

    static {
        q logic = new q("LOGIC", 0);
        q byteType = new q("BYTE", 1);
        q unsignedByte = new q("UNSIGNED_BYTE", 2);
        q shortType = new q("SHORT", 3);
        q intType = new q("INT", 4);
        l longType = new l();
        f = longType;
        m floatType = new m();
        f12106g = floatType;
        n doubleType = new n();
        f12107h = doubleType;
        q complexFloat = new q("COMPLEX_FLOAT", 8);
        q complexDouble = new q("COMPLEX_DOUBLE", 9);
        q string = new q("STRING", 10);
        q object = new q("OBJECT", 11);
        f12108i = new q[]{logic, byteType, unsignedByte, shortType, intType, longType, floatType, doubleType, complexFloat, complexDouble, string, object};
    }
}
