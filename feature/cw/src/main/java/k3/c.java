package k3;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/* loaded from: classes.dex */
public abstract class c {

    /* renamed from: a, reason: collision with root package name */
    public static final ExecutorService f12093a;

    /* renamed from: b, reason: collision with root package name */
    public static ExecutorService f12094b;
    public static final int c;

    /* renamed from: d, reason: collision with root package name */
    public static final long f12095d;

    /* JADX WARN: Type inference failed for: r1v0, types: [java.lang.Object, k3.a] */
    static {
        ExecutorService newCachedThreadPool = Executors.newCachedThreadPool(new b(new a()));
        f12093a = newCachedThreadPool;
        f12094b = newCachedThreadPool;
        c = Runtime.getRuntime().availableProcessors();
        f12095d = 100000L;
    }

    public static Future a(Runnable runnable) {
        if (f12094b.isShutdown() || f12094b.isTerminated()) {
            f12094b = f12093a;
        }
        return f12094b.submit(runnable);
    }

    public static void b(Future[] futureArr) throws InterruptedException, java.util.concurrent.ExecutionException {
        // smali: 无 catch 直接向上抛 -> Java 声明 throws(忠实表达)
        for (Future future : futureArr) {
            future.get();
        }
    }
}
