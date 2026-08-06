package pas;

import java.util.Date;
import java.util.TimeZone;

/* loaded from: classes.dex */
public class system {
    private static boolean _JniLibLoaded = false;

    /* loaded from: classes.dex */
    public static abstract class BaseSet {
        protected int Value = 0;

        public abstract int Base();

        public abstract int ElMax();

        public abstract byte Size();
    }

    /* loaded from: classes.dex */
    public static class Enum {
        public int Value;

        public int Ord() {
            return this.Value;
        }

        public boolean equals(Object obj) {
            return (obj instanceof Integer) && this.Value == ((Integer) obj).intValue();
        }

        public int hashCode() {
            return this.Value;
        }

        public boolean equals(int i4) {
            return this.Value == i4;
        }
    }

    /* loaded from: classes.dex */
    public static class MethodPtr extends PascalObjectEx {
        protected String mName;
        protected Object mObject;
        protected String mSignature;

        public MethodPtr() {
            this._cleanup = true;
            this._pasobj = -1L;
        }

        private native void __Destroy();

        private native void __Init(Object obj, String str, String str2, boolean z3);

        public void Init() {
            boolean z3;
            Object obj = this.mObject;
            String str = this.mName;
            String str2 = this.mSignature;
            if (this != obj) {
                z3 = true;
            } else {
                z3 = false;
            }
            __Init(obj, str, str2, z3);
        }

        @Override // pas.system.PascalObjectEx
        public void __Release() {
            if (this._pasobj > 0) {
                __Destroy();
            }
        }
    }

    /* loaded from: classes.dex */
    public static abstract class PascalInterface extends PascalObjectEx {
        public PascalInterface(long j4, boolean z3) {
            this._pasobj = j4;
            __Init();
        }

        public abstract void __Init();

        public void __TypeCast(PascalObject pascalObject, String str) {
            if (pascalObject != null) {
                if (pascalObject instanceof PascalInterface) {
                    this._pasobj = pascalObject._pasobj;
                    __Init();
                } else {
                    this._pasobj = system.InterfaceCast(pascalObject._pasobj, str);
                }
            }
        }
    }

    /* loaded from: classes.dex */
    public static class Record extends PascalObjectEx {
        protected PascalObject _objref;

        public Record(PascalObject pascalObject) {
            super(pascalObject);
            this._objref = pascalObject;
        }

        public void __Init(long j4, boolean z3) {
            this._pasobj = j4;
            this._cleanup = z3;
            if (j4 == 0 && __Size() != 0) {
                this._pasobj = system.AllocMemory(__Size());
            }
        }

        public int __Size() {
            return 0;
        }

        @Override // pas.system.PascalObjectEx, pas.system.PascalObject
        public void finalize() {
            long j4 = this._pasobj;
            if (j4 < 0) {
                this._pasobj = -j4;
                this._cleanup = true;
            }
            super.finalize();
        }

        public Record(long j4) {
            super(j4);
        }

        public final int __Size(int i4) {
            return system.GetRecordSize(i4);
        }

        public Record() {
        }
    }

    /* loaded from: classes.dex */
    public static abstract class Set<TS extends BaseSet, TE extends Enum> extends BaseSet {
        public Set() {
        }

        public void Assign(TS ts) {
            this.Value = ts.Value;
        }

        public void Exclude(TE... teArr) {
            for (TE te : teArr) {
                this.Value = (~GetMask(te)) & this.Value;
            }
        }

        public int GetMask(TE te) {
            return 1 << (te.Ord() - Base());
        }

        public boolean Has(TE te) {
            if ((GetMask(te) & this.Value) != 0) {
                return true;
            }
            return false;
        }

        public void Include(TE... teArr) {
            for (TE te : teArr) {
                this.Value = GetMask(te) | this.Value;
            }
        }

        public void Intersect(TS ts) {
            this.Value = ts.Value & this.Value;
        }

        public boolean IsEmpty() {
            if (this.Value == 0) {
                return true;
            }
            return false;
        }

        public boolean equals(TS ts) {
            return this.Value == ts.Value;
        }

        public Set(TE... teArr) {
            Include(teArr);
        }

        public void Exclude(TS ts) {
            this.Value = (~ts.Value) & this.Value;
        }

        public void Include(TS ts) {
            this.Value = ts.Value | this.Value;
        }

        public boolean equals(TE te) {
            return this.Value == te.Ord();
        }

        public Set(TS... tsArr) {
            for (TS ts : tsArr) {
                Include(ts);  // smali: Set(TS...) 循环调 Include(BaseSet)
            }
        }

        public boolean equals(int i4) {
            return this.Value == i4;
        }
    }

    /* loaded from: classes.dex */
    public static class TClass extends TObject {
        public TClass(PascalObject pascalObject) {
            super(pascalObject);
        }
    }

    /* loaded from: classes.dex */
    public static class TDateTime {
        public static double get(Date date) {
            return ((date.getTime() + TimeZone.getDefault().getOffset(date.getTime())) / 8.64E7d) + 25569.0d;
        }

        public static double getUTC(Date date) {
            return (date.getTime() / 8.64E7d) + 25569.0d;
        }

        public static Date toDate(double d4) {
            return new Date(Math.round((d4 - 25569.0d) * 8.64E7d) - TimeZone.getDefault().getOffset(Math.round((d4 - 25569.0d) * 8.64E7d)));  // r3 = 前值(smali)
        }

        public static Date toDateUTC(double d4) {
            return new Date(Math.round((d4 - 25569.0d) * 8.64E7d));
        }
    }

    /* loaded from: classes.dex */
    public static class TObject extends PascalObject {
        public TObject(PascalObject pascalObject) {
            super(pascalObject);
        }

        public static TObject Class() {
            return new TObject(system.GetClassRef(0));
        }

        public static native TObject Create();

        public static TClass TClass() {
            return system.GetTClass(0);
        }

        public native String ClassName();

        public native boolean ClassNameIs(String str);

        public native TClass ClassParent();

        public native TClass ClassType();

        public native void Destroy();

        public native void Free();

        public native boolean InheritsFrom(TClass tClass);

        public native int InstanceSize();

        public TObject(long j4) {
            super(j4);
        }
    }

    static {
        InitJni();
    }

    public static native long AllocMemory(int i4);

    public static native long GetClassRef(int i4);

    public static native byte[] GetMemoryAsArray(long j4, int i4);

    /* JADX INFO: Access modifiers changed from: private */
    public static native int GetRecordSize(int i4);

    public static TClass GetTClass(int i4) {
        TClass tClass = new TClass(null);
        tClass._pasobj = GetClassRef(i4);
        return tClass;
    }

    public static void InitJni() {
        if (!_JniLibLoaded) {
            _JniLibLoaded = true;
            System.loadLibrary("nativedecoderjni");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static native long InterfaceCast(long j4, String str);

    public static long Pointer(PascalObject pascalObject) {
        if (pascalObject == null) {
            return 0L;
        }
        return pascalObject._pasobj;
    }

    public static native void SetMemoryFromArray(long j4, byte[] bArr);

    /* loaded from: classes.dex */
    public static class PascalObject {
        protected long _pasobj;

        static {
            system.InitJni();
        }

        public PascalObject() {
            this._pasobj = 0L;
        }

        public boolean equals(Object obj) {
            if ((obj instanceof PascalObject) && this._pasobj == ((PascalObject) obj)._pasobj) {
                return true;
            }
            return false;
        }

        public void finalize() {
        }

        public int hashCode() {
            return (int) this._pasobj;
        }

        public PascalObject(PascalObject pascalObject) {
            this._pasobj = 0L;
            if (pascalObject != null) {
                this._pasobj = pascalObject._pasobj;
            }
        }

        public PascalObject(long j4) {
            this._pasobj = j4;
        }
    }

    /* loaded from: classes.dex */
    public static class PascalObjectEx extends PascalObject {
        protected boolean _cleanup;

        public PascalObjectEx() {
            this._cleanup = false;
        }

        public void __Release() {
            this._pasobj = 0L;
        }

        @Override // pas.system.PascalObject
        public void finalize() {
            if (this._cleanup) {
                __Release();
            }
            super.finalize();
        }

        public PascalObjectEx(PascalObject pascalObject) {
            super(pascalObject);
            this._cleanup = false;
        }

        public PascalObjectEx(long j4) {
            super(j4);
            this._cleanup = false;
        }
    }
}
