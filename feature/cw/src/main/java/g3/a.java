package g3;

/* loaded from: classes.dex */
public final class a {

    /* renamed from: a, reason: collision with root package name */
    public final int f11582a;

    /* renamed from: b, reason: collision with root package name */
    public final double f11583b;
    public final double c;

    public a(double d4) {
        int i4 = (int) (0.6366197723675814d * d4);
        while (true) {
            double d5 = -i4;
            double d6 = 1.570796251296997d * d5;
            double d7 = d4 + d6;
            double d8 = 7.549789948768648E-8d * d5;
            double d9 = d8 + d7;
            double d10 = (-((d7 - d4) - d6)) + (-((d9 - d7) - d8));
            double d11 = d5 * 6.123233995736766E-17d;
            double d12 = d11 + d9;
            double d13 = d10 + (-((d12 - d9) - d11));
            if (d12 > 0.0d) {
                this.f11582a = i4;
                this.f11583b = d12;
                this.c = d13;
                return;
            }
            i4--;
        }
    }
}
