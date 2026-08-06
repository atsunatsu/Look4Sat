package k3;

import com.rtbishop.look4sat.feature.cw.suncompat.Cleaner;

/* loaded from: classes.dex */
public final class d extends g {

    /* renamed from: k, reason: collision with root package name */
    public float[] f12096k;

    public d() {
    }

    public d(long j4, boolean z3) {
        this.f = q.f12106g;
        this.f12103h = 4L;
        if (j4 > 0) {
            this.f12102g = j4;
            if (j4 > 1073741824) {
                this.f12105j = r.f12109a.allocateMemory(4 * j4);
                if (z3) {
                    a(j4);
                }
                Cleaner.create(this, new f(this.f12105j, this.f12102g, this.f12103h));
                K1.a.c += this.f12102g * this.f12103h;
                return;
            }
            this.f12096k = new float[(int) j4];
            return;
        }
        throw new IllegalArgumentException(j4 + " is not a positive long value");
    }

    public final float b(long j4) {
        float[] fArr = this.f12096k;
        long j5 = this.f12105j;
        if (j5 != 0) {
            return r.f12109a.getFloat((this.f12103h * j4) + j5);
        }
        if (this.f12104i) {
            return fArr[0];
        }
        return fArr[(int) j4];
    }

    public final void c(long j4, float f) {
        long j5 = this.f12105j;
        if (j5 != 0) {
            r.f12109a.putFloat((this.f12103h * j4) + j5, f);
        } else {
            if (!this.f12104i) {
                this.f12096k[(int) j4] = f;
                return;
            }
            throw new IllegalAccessError("Constant arrays cannot be modified.");
        }
    }

    /* JADX WARN: Type inference failed for: r0v1, types: [k3.g, java.lang.Object, k3.d] */
    public final Object clone() {
        if (this.f12104i) {
            long j4 = this.f12102g;
            float b4 = b(0L);
            d gVar = new d();
            gVar.f = q.f12106g;
            gVar.f12103h = 4L;
            if (j4 > 0) {
                gVar.f12102g = j4;
                gVar.f12104i = true;
                gVar.f12096k = new float[]{b4};
                return gVar;
            }
            throw new IllegalArgumentException(j4 + " is not a positive long value");
        }
        d dVar = new d(this.f12102g, false);
        r.a(0L, 0L, this.f12102g, this, dVar);
        return dVar;
    }

    @Override // k3.g
    public final boolean equals(Object obj) {
        if (!super.equals(obj) || this.f12096k != ((d) obj).f12096k) {
            return false;
        }
        return true;
    }

    @Override // k3.g
    public final int hashCode() {
        int i4;
        int hashCode = super.hashCode() * 29;
        float[] fArr = this.f12096k;
        if (fArr != null) {
            i4 = fArr.hashCode();
        } else {
            i4 = 0;
        }
        return hashCode + i4;
    }
}
