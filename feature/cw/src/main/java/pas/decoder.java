package pas;

import pas.cwstru;
import pas.system;

/* loaded from: classes.dex */
public class decoder {

    /* loaded from: classes.dex */
    public static class TCharInfo extends system.TObject {
        public TCharInfo(system.PascalObject pascalObject) {
            super(pascalObject);
        }

        public static TCharInfo Class() {
            return new TCharInfo(system.GetClassRef(1));
        }

        public static native TCharInfo Create();

        public static native TCharInfo Create(char c);

        public static native TCharInfo Create(cwstru.TMorseCharInfo tMorseCharInfo);

        public static system.TClass TClass() {
            return system.GetTClass(1);
        }

        public native char getChar();

        public native boolean getLowSnr();

        public native TMorseWordType getWordType();

        public TCharInfo(long j4) {
            super(j4);
        }
    }

    /* loaded from: classes.dex */
    public static class TDecoder extends system.TObject {
        public TDecoder(system.PascalObject pascalObject) {
            super(pascalObject);
        }

        public static TDecoder Class() {
            return new TDecoder(system.GetClassRef(2));
        }

        public static native TDecoder Create();

        public static system.TClass TClass() {
            return system.GetTClass(2);
        }

        public native TCharInfo GetChar(int i4);

        public native void LockToPitch(int i4);

        public native int getCharCount();

        public native int getDeleteCount();

        public native boolean getHamMode();

        public native int getPitch();

        public native int getSignalCount();

        public native int getSnr();

        public native int getWpm();

        public native void setHamMode(boolean z3);

        public TDecoder(long j4) {
            super(j4);
        }
    }

    /* loaded from: classes.dex */
    public static class TDecoderState extends system.Enum {
        public static final int dsDecoding = 2;
        public static final int dsLockedDecoding = 4;
        public static final int dsLockedPause = 3;
        public static final int dsNone = 0;
        public static final int dsPause = 1;

        public TDecoderState(int i4) {
            this.Value = i4;
        }

        public static TDecoderState dsDecoding() {
            return new TDecoderState(2);
        }

        public static TDecoderState dsLockedDecoding() {
            return new TDecoderState(4);
        }

        public static TDecoderState dsLockedPause() {
            return new TDecoderState(3);
        }

        public static TDecoderState dsNone() {
            return new TDecoderState(0);
        }

        public static TDecoderState dsPause() {
            return new TDecoderState(1);
        }

        @Override // pas.system.Enum
        public boolean equals(Object obj) {
            if (((obj instanceof TDecoderState) && this.Value == ((TDecoderState) obj).Value) || super.equals(obj)) {
                return true;
            }
            return false;
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

    static {
        system.InitJni();
    }
}
