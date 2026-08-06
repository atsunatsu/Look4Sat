package pas;

import pas.decoder;
import pas.system;

/* loaded from: classes.dex */
public class nativedecoder {

    /* loaded from: classes.dex */
    public static class TNativeDecoder extends decoder.TDecoder {
        public TNativeDecoder(system.PascalObject pascalObject) {
            super(pascalObject);
        }

        public static TNativeDecoder Class() {
            return new TNativeDecoder(system.GetClassRef(3));
        }

        public static native TNativeDecoder Create();

        public static system.TClass TClass() {
            return system.GetTClass(3);
        }

        public native decoder.TDecoderState ProcessSpectrum(Object obj);

        public TNativeDecoder(long j4) {
            super(j4);
        }
    }

    static {
        system.InitJni();
    }
}
