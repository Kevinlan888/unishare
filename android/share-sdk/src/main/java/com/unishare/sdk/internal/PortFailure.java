package com.unishare.sdk.internal;
final class PortFailure extends Exception {
    final int code; final Long nativeCode;
    PortFailure(int code,String message,Long nativeCode){super(message);this.code=code;this.nativeCode=nativeCode;}
}
