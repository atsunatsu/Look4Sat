package H2;

import android.media.AudioRecord;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/* loaded from: classes.dex */
public final class a implements Runnable {
    public final int f;

    /* renamed from: g, reason: collision with root package name */
    public final AudioRecord f596g;

    /* renamed from: h, reason: collision with root package name */
    public final ExecutorService f597h;

    /* renamed from: i, reason: collision with root package name */
    public final AtomicBoolean f598i;

    /* renamed from: j, reason: collision with root package name */
    public final short[] f599j;

    /* renamed from: k, reason: collision with root package name */
    public final float[] f600k;

    /* renamed from: l, reason: collision with root package name */
    public b f601l;

    public a() {
        int minBufferSize = AudioRecord.getMinBufferSize(8000, 16, 2);
        int i4 = b.f604C;
        this.f = i4;
        ExecutorService newSingleThreadExecutor = Executors.newSingleThreadExecutor();
        this.f597h = newSingleThreadExecutor;
        AtomicBoolean atomicBoolean = new AtomicBoolean(false);
        this.f598i = atomicBoolean;
        this.f599j = new short[i4];
        this.f600k = new float[i4];
        AudioRecord audioRecord = new AudioRecord(1, 8000, 16, 2, minBufferSize * 4);
        this.f596g = audioRecord;
        if (audioRecord.getState() == 1) {
            audioRecord.startRecording();
            if (audioRecord.getState() != 1) {
                Log.e("AudioRecord", "cannot start");
            }
            atomicBoolean.set(true);
            newSingleThreadExecutor.execute(this);
            return;
        }
        Log.e("AudioInput", "Failed to initialize");
    }

    @Override // java.lang.Runnable
    public final void run() {
        while (true) {
            try {
                if (this.f598i.get()) {
                    float[] fArr = this.f600k;
                    AudioRecord audioRecord = this.f596g;
                    short[] sArr = this.f599j;
                    int i4 = this.f;
                    if (audioRecord.read(sArr, 0, i4) == i4) {
                        for (int i5 = 0; i5 < i4; i5++) {
                            fArr[i5] = sArr[i5] / 32768.0f;
                        }
                        b bVar = this.f601l;
                        if (bVar != null) {
                            bVar.a(fArr);
                        }
                    }
                } else {
                    TimeUnit.MILLISECONDS.sleep(100L);
                }
            } catch (Exception e4) {
                Log.e("Audio thread", "Exception: ", e4);
            }
        }
    }
}
