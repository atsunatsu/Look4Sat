package k3;

import java.io.Serializable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/* loaded from: classes.dex */
public abstract class g implements Serializable, Cloneable {
    public q f;

    /* renamed from: g, reason: collision with root package name */
    public long f12102g;

    /* renamed from: h, reason: collision with root package name */
    public long f12103h;

    /* renamed from: i, reason: collision with root package name */
    public boolean f12104i = false;

    /* renamed from: j, reason: collision with root package name */
    public long f12105j = 0;

    public final void a(long j4) {
        long j5;
        g gVar = this;
        long j6 = gVar.f12105j;
        if (j6 != 0) {
            long j7 = c.c;
            double[][] dArr = g3.c.f11585a;
            if (j4 <= j7) {
                j7 = j4;
            }
            int i4 = (int) j7;
            if (i4 > 2 && j4 >= c.f12095d) {
                long j8 = j4 / i4;
                Future[] futureArr = new Future[i4];
                int i5 = 0;
                while (i5 < i4) {
                    long j9 = i5 * j8;
                    if (i5 == i4 - 1) {
                        j5 = j4;
                    } else {
                        j5 = j9 + j8;
                    }
                    futureArr[i5] = c.a(new e(gVar, j9, j5, j6));
                    i5++;
                    gVar = gVar;
                    j6 = j6;
                }
                g gVar2 = gVar;
                try {
                    c.b(futureArr);
                    return;
                } catch (InterruptedException unused) {
                    r.f12109a.setMemory(gVar2.f12105j, gVar2.f12103h * j4, (byte) 0);
                    return;
                } catch (ExecutionException unused2) {
                    r.f12109a.setMemory(gVar2.f12105j, j4 * gVar2.f12103h, (byte) 0);
                    return;
                }
            }
            r.f12109a.setMemory(j6, gVar.f12103h * j4, (byte) 0);
        }
    }

    public boolean equals(Object obj) {
        if (obj != null && (obj instanceof g)) {
            g gVar = (g) obj;
            if (this.f == gVar.f && this.f12102g == gVar.f12102g && this.f12103h == gVar.f12103h && this.f12104i == gVar.f12104i && this.f12105j == gVar.f12105j) {
                return true;
            }
            return false;
        }
        return false;
    }

    public int hashCode() {
        int i4;
        q qVar = this.f;
        if (qVar != null) {
            i4 = qVar.hashCode();
        } else {
            i4 = 0;
        }
        long j4 = this.f12102g;
        int i5 = (((203 + i4) * 29) + ((int) (j4 ^ (j4 >>> 32)))) * 29;
        long j5 = this.f12103h;
        int i6 = (((i5 + ((int) (j5 ^ (j5 >>> 32)))) * 29) + (this.f12104i ? 1 : 0)) * 841;
        long j6 = this.f12105j;
        return i6 + ((int) (j6 ^ (j6 >>> 32)));
    }
}
