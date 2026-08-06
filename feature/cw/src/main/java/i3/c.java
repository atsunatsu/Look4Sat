package i3;

import k3.g;
import k3.s;

/* loaded from: classes.dex */
public final class c implements Runnable {
    public final /* synthetic */ int f = 1;

    /* renamed from: g, reason: collision with root package name */
    public final /* synthetic */ long f11696g;

    /* renamed from: h, reason: collision with root package name */
    public final /* synthetic */ long f11697h;

    /* renamed from: i, reason: collision with root package name */
    public final /* synthetic */ g f11698i;

    /* renamed from: j, reason: collision with root package name */
    public final /* synthetic */ Object f11699j;

    public c(long j4, long j5, s sVar, s sVar2) {
        this.f11696g = j4;
        this.f11697h = j5;
        this.f11698i = sVar;
        this.f11699j = sVar2;
    }

    @Override // java.lang.Runnable
    public final void run() {
        switch (this.f) {
            case 0:
                k3.d dVar = (k3.d) this.f11698i;
                long j4 = this.f11697h;
                long j5 = this.f11696g;
                if (j5 >= j4) {
                    return;
                }
                dVar.b(j5 * 2);
                throw null;
            default:
                for (long j6 = this.f11696g; j6 < this.f11697h; j6++) {
                    ((s) this.f11698i).c(j6, ((s) this.f11699j).b(j6));
                }
                return;
        }
    }

    public c(d dVar, long j4, long j5, k3.d dVar2) {
        this.f11699j = dVar;
        this.f11696g = j4;
        this.f11697h = j5;
        this.f11698i = dVar2;
    }
}
