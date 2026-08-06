package com.rtbishop.look4sat.feature.cw.suncompat;

/**
 * 编译期 stub:Android 无 sun.misc.Unsafe(运行时通过反射获取,失败时 k3.r.f12109a 为 null)。
 * 照搬自 Morse Expert 1.15(k3 FFT 库的大数组 native 路径 >1GB 不触发,实际 FFT 走 float[] 分支)。
 */
public class Unsafe {
    public long allocateMemory(long bytes) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public void freeMemory(long address) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public byte getByte(long address) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public void putByte(long address, byte value) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public short getShort(long address) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public void putShort(long address, short value) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public int getInt(long address) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public void putInt(long address, int value) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public long getLong(long address) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public void putLong(long address, long value) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public float getFloat(long address) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public void putFloat(long address, float value) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public double getDouble(long address) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public void putDouble(long address, double value) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public void setMemory(long address, long bytes, byte value) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public void copyMemory(long src, long dst, long len) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public Object getObject(Object o, long offset) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public void putObject(Object o, long offset, Object value) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public long objectFieldOffset(java.lang.reflect.Field field) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }
}
