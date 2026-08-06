package k3;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import com.rtbishop.look4sat.feature.cw.suncompat.Cleaner;
import com.rtbishop.look4sat.feature.cw.suncompat.Unsafe;

/* loaded from: classes.dex */
public final class s extends g {

    /* renamed from: k, reason: collision with root package name */
    public long[] f12110k;

    public s() {
    }

    public s(long j4, boolean z3) {
        this.f = q.f;
        this.f12103h = 8L;
        if (j4 > 0) {
            this.f12102g = j4;
            if (j4 > 1073741824) {
                this.f12105j = r.f12109a.allocateMemory(8 * j4);
                if (z3) {
                    a(j4);
                }
                Cleaner.create(this, new f(this.f12105j, this.f12102g, this.f12103h));
                K1.a.c += this.f12102g * this.f12103h;
                return;
            }
            this.f12110k = new long[(int) j4];
            return;
        }
        throw new IllegalArgumentException(j4 + " is not a positive long value");
    }

    public final long b(long j4) {
        long[] jArr = this.f12110k;
        long j5 = this.f12105j;
        if (j5 != 0) {
            return r.f12109a.getLong((this.f12103h * j4) + j5);
        }
        if (this.f12104i) {
            return jArr[0];
        }
        return jArr[(int) j4];
    }

    public final void c(long j4, long j5) {
        long j6 = this.f12105j;
        if (j6 != 0) {
            r.f12109a.putLong((this.f12103h * j4) + j6, j5);
        } else {
            if (!this.f12104i) {
                this.f12110k[(int) j4] = j5;
                return;
            }
            throw new IllegalAccessError("Constant arrays cannot be modified.");
        }
    }

    /* JADX WARN: Type inference failed for: r0v29, types: [k3.g, java.lang.Object, k3.s] */
    public final Object clone() {
        long j4;
        int i4 = 0;
        long j5 = 0;
        if (this.f12104i) {
            long j6 = this.f12102g;
            long b4 = b(0L);
            s gVar = new s();
            gVar.f = q.f12107h;
            gVar.f12103h = 8L;
            if (j6 > 0) {
                gVar.f12102g = j6;
                gVar.f12104i = true;
                gVar.f12110k = new long[]{b4};
                return gVar;
            }
            throw new IllegalArgumentException(j6 + " is not a positive long value");
        }
        s sVar = new s(this.f12102g, false);
        long j7 = this.f12102g;
        Unsafe unsafe = r.f12109a;
        if (0 < j7) {
            if (0 < sVar.f12102g) {
                if (j7 >= 0) {
                    if (!sVar.f12104i) {
                        long j8 = c.c;
                        double[][] dArr = g3.c.f11585a;
                        if (j7 <= j8) {
                            j8 = j7;
                        }
                        int i5 = (int) j8;
                        if (i5 >= 2 && j7 >= c.f12095d) {
                            long j9 = j7 / i5;
                            Future[] futureArr = new Future[i5];
                            while (i4 < i5) {
                                long j10 = i4 * j9;
                                if (i4 == i5 - 1) {
                                    j4 = j7;
                                } else {
                                    j4 = j10 + j9;
                                }
                                Future[] futureArr2 = futureArr;
                                int i6 = i4;
                                futureArr2[i6] = c.a(new i3.c(j10, j4, sVar, this));
                                i4 = i6 + 1;
                                futureArr = futureArr2;
                            }
                            try {
                                c.b(futureArr);
                                return sVar;
                            } catch (InterruptedException unused) {
                                long j11 = 0;
                                while (j5 < j7) {
                                    sVar.c(j11, b(j5));
                                    j5++;
                                    j11++;
                                }
                            } catch (ExecutionException unused2) {
                                long j12 = 0;
                                while (j5 < j7) {
                                    sVar.c(j12, b(j5));
                                    j5++;
                                    j12++;
                                }
                            }
                        } else {
                            long j13 = 0;
                            while (j5 < j7) {
                                sVar.c(j13, b(j5));
                                j5++;
                                j13++;
                            }
                        }
                        return sVar;
                    }
                    throw new IllegalArgumentException("Constant arrays cannot be modified.");
                }
                throw new IllegalArgumentException("length < 0");
            }
            throw new ArrayIndexOutOfBoundsException("destPos < 0 || destPos >= dest.length()");
        }
        throw new ArrayIndexOutOfBoundsException("srcPos < 0 || srcPos >= src.length()");
    }

    @Override // k3.g
    public final boolean equals(Object obj) {
        if (!super.equals(obj) || this.f12110k != ((s) obj).f12110k) {
            return false;
        }
        return true;
    }

    @Override // k3.g
    public final int hashCode() {
        int i4;
        int hashCode = super.hashCode() * 29;
        long[] jArr = this.f12110k;
        if (jArr != null) {
            i4 = jArr.hashCode();
        } else {
            i4 = 0;
        }
        return hashCode + i4;
    }
}
