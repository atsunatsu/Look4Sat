package k3;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import com.rtbishop.look4sat.feature.cw.suncompat.Unsafe;

/* loaded from: classes.dex */
public abstract class r {

    /* renamed from: a, reason: collision with root package name */
    public static final Unsafe f12109a;

    static {
        Object obj = null;
        try {
            Class<?> cls = Class.forName("sun.misc.Unsafe");
            Field declaredField = cls.getDeclaredField("theUnsafe");
            declaredField.setAccessible(true);
            obj = declaredField.get(cls);
        } catch (ClassNotFoundException | IllegalAccessException | IllegalArgumentException | NoSuchFieldException | SecurityException ignored) {
            // Android 适配: sun.misc.Unsafe 可能不可用(隐藏 API), 静默降级为 null;
            // 大数组 native 路径(j4 > 1GB)不会在实际解码中触发
        }
        f12109a = (Unsafe) obj;
    }

    public static void a(long j4, long j5, long j6, d dVar, d dVar2) {
        long j7;
        if (j4 >= 0 && j4 < dVar.f12102g) {
            if (j5 >= 0 && j5 < dVar2.f12102g) {
                if (j6 >= 0) {
                    if (!dVar2.f12104i) {
                        long j8 = c.c;
                        double[][] dArr = g3.c.f11585a;
                        if (j6 <= j8) {
                            j8 = j6;
                        }
                        int i4 = (int) j8;
                        if (i4 >= 2 && j6 >= c.f12095d) {
                            long j9 = j6 / i4;
                            Future[] futureArr = new Future[i4];
                            int i5 = 0;
                            while (i5 < i4) {
                                long j10 = i5 * j9;
                                if (i5 == i4 - 1) {
                                    j7 = j6;
                                } else {
                                    j7 = j10 + j9;
                                }
                                Future[] futureArr2 = futureArr;
                                int i6 = i5;
                                futureArr2[i6] = c.a(new j3.b(j10, j7, j5, j4, dVar2, dVar));
                                i5 = i6 + 1;
                                futureArr = futureArr2;
                            }
                            try {
                                c.b(futureArr);
                                return;
                            } catch (InterruptedException unused) {
                                long j11 = j4;
                                long j12 = j5;
                                while (j11 < j4 + j6) {
                                    dVar2.c(j12, dVar.b(j11));
                                    j11++;
                                    j12++;
                                }
                                return;
                            } catch (ExecutionException unused2) {
                                long j13 = j4;
                                long j14 = j5;
                                while (j13 < j4 + j6) {
                                    dVar2.c(j14, dVar.b(j13));
                                    j13++;
                                    j14++;
                                }
                                return;
                            }
                        }
                        long j15 = j4;
                        long j16 = j5;
                        while (j15 < j4 + j6) {
                            dVar2.c(j16, dVar.b(j15));
                            j15++;
                            j16++;
                        }
                        return;
                    }
                    throw new IllegalArgumentException("Constant arrays cannot be modified.");
                }
                throw new IllegalArgumentException("length < 0");
            }
            throw new ArrayIndexOutOfBoundsException("destPos < 0 || destPos >= dest.length()");
        }
        throw new ArrayIndexOutOfBoundsException("srcPos < 0 || srcPos >= src.length()");
    }
}
