package k3;

/* loaded from: classes.dex */
public final class e implements Runnable {
    public final /* synthetic */ long f;

    /* renamed from: g, reason: collision with root package name */
    public final /* synthetic */ long f12097g;

    /* renamed from: h, reason: collision with root package name */
    public final /* synthetic */ long f12098h;

    /* renamed from: i, reason: collision with root package name */
    public final /* synthetic */ g f12099i;

    public e(g gVar, long j4, long j5, long j6) {
        this.f12099i = gVar;
        this.f = j4;
        this.f12097g = j5;
        this.f12098h = j6;
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Failed to find 'out' block for switch in B:2:0x0011. Please report as an issue. */
    @Override // java.lang.Runnable
    public final void run() {
        g gVar = this.f12099i;
        int ordinal = gVar.f.ordinal();
        long j4 = this.f12098h;
        long j5 = this.f12097g;
        long j6 = this.f;
        switch (ordinal) {
            case 0:
            case 1:
            case 2:
            case 10:
            case 11:
                while (j6 < j5) {
                    r.f12109a.putByte((gVar.f12103h * j6) + j4, (byte) 0);
                    j6++;
                }
                return;
            case 3:
                while (j6 < j5) {
                    r.f12109a.putShort((gVar.f12103h * j6) + j4, (short) 0);
                    j6++;
                }
                return;
            case 4:
                while (j6 < j5) {
                    r.f12109a.putInt((gVar.f12103h * j6) + j4, 0);
                    j6++;
                }
                return;
            case 5:
                while (j6 < j5) {
                    r.f12109a.putLong((gVar.f12103h * j6) + j4, 0L);
                    j6++;
                }
                return;
            case 6:
                while (j6 < j5) {
                    r.f12109a.putFloat((gVar.f12103h * j6) + j4, 0.0f);
                    j6++;
                }
                return;
            case 7:
                while (j6 < j5) {
                    r.f12109a.putDouble((gVar.f12103h * j6) + j4, 0.0d);
                    j6++;
                }
                return;
            case 8:
            case 9:
            default:
                throw new IllegalArgumentException("Invalid array type.");
        }
    }
}
