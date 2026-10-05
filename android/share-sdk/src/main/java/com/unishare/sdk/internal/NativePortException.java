package com.unishare.sdk.internal;
/** Preserves the stable core code and optional original platform code for the Kotlin wrapper. */
public final class NativePortException extends RuntimeException {
    public final int code;public final Long platformError;
    public NativePortException(int code,boolean hasPlatformError,long platformError){super("native port error "+code);this.code=code;this.platformError=hasPlatformError?platformError:null;}
}
