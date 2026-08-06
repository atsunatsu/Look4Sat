package k3;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/* loaded from: classes.dex */
public final class b implements ThreadFactory {

    /* renamed from: b, reason: collision with root package name */
    public static final ThreadFactory f12091b = Executors.defaultThreadFactory();

    /* renamed from: a, reason: collision with root package name */
    public final a f12092a;

    public b(a aVar) {
        this.f12092a = aVar;
    }

    @Override // java.util.concurrent.ThreadFactory
    public final Thread newThread(Runnable runnable) {
        Thread newThread = f12091b.newThread(runnable);
        newThread.setUncaughtExceptionHandler(this.f12092a);
        return newThread;
    }
}
