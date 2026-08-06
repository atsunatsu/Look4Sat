package pas;

import pas.system;

/* loaded from: classes.dex */
public class cwstru {

    /* loaded from: classes.dex */
    public static class TMorseCharInfo extends system.Record {
        public TMorseCharInfo(long j4, boolean z3) {
            __Init(j4, z3);
        }

        private native void __Destroy(long j4);

        @Override // pas.system.PascalObjectEx
        public void __Release() {
            __Destroy(this._pasobj);
            this._pasobj = 0L;
        }

        @Override // pas.system.Record
        public int __Size() {
            return __Size(0);
        }

        public native char getCh();

        public native int getIdxE();

        public native boolean getLowSnr();

        public native TWordEndType getWordEnd();

        public native float getWordSpaceProb();

        public native TMorseWordType getWordType();

        public native void setCh(char c);

        public native void setIdxE(int i4);

        public native void setLowSnr(boolean z3);

        public native void setWordEnd(TWordEndType tWordEndType);

        public native void setWordSpaceProb(float f);

        public native void setWordType(TMorseWordType tMorseWordType);

        public TMorseCharInfo() {
            __Init(0L, true);
        }

        public TMorseCharInfo(system.PascalObject pascalObject) {
            super(pascalObject);
        }

        public TMorseCharInfo(long j4) {
            super(j4);
        }
    }

    /* loaded from: classes.dex */
    public static class TMorseWordType extends system.Enum {
        public static final int wtCall = 5;
        public static final int wtCq = 3;
        public static final int wtGenWord = 0;
        public static final int wtKeyword = 1;
        public static final int wtQrl = 4;
        public static final int wtRst = 2;

        public TMorseWordType(int i4) {
            this.Value = i4;
        }

        public static TMorseWordType wtCall() {
            return new TMorseWordType(5);
        }

        public static TMorseWordType wtCq() {
            return new TMorseWordType(3);
        }

        public static TMorseWordType wtGenWord() {
            return new TMorseWordType(0);
        }

        public static TMorseWordType wtKeyword() {
            return new TMorseWordType(1);
        }

        public static TMorseWordType wtQrl() {
            return new TMorseWordType(4);
        }

        public static TMorseWordType wtRst() {
            return new TMorseWordType(2);
        }

        @Override // pas.system.Enum
        public boolean equals(Object obj) {
            if (((obj instanceof TMorseWordType) && this.Value == ((TMorseWordType) obj).Value) || super.equals(obj)) {
                return true;
            }
            return false;
        }
    }

    /* loaded from: classes.dex */
    public static class TWordEndType extends system.Enum {
        public static final int weLong = 2;
        public static final int weNone = 0;
        public static final int weShort = 1;

        public TWordEndType(int i4) {
            this.Value = i4;
        }

        public static TWordEndType weLong() {
            return new TWordEndType(2);
        }

        public static TWordEndType weNone() {
            return new TWordEndType(0);
        }

        public static TWordEndType weShort() {
            return new TWordEndType(1);
        }

        @Override // pas.system.Enum
        public boolean equals(Object obj) {
            if (((obj instanceof TWordEndType) && this.Value == ((TWordEndType) obj).Value) || super.equals(obj)) {
                return true;
            }
            return false;
        }
    }

    static {
        system.InitJni();
    }
}
