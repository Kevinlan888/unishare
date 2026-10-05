package com.unishare.sdk.internal;
final class PermissionPolicy {
    interface Boundary {boolean foreground();int sdk();int target();boolean granted();}
    static void check(Boundary boundary)throws PortFailure{
        if(!boundary.foreground())throw new PortFailure(10,"SDK requires foreground host",null);
        if(boundary.sdk()>=37&&boundary.target()>=37&&!boundary.granted())throw new PortFailure(1,"ACCESS_LOCAL_NETWORK denied",-1L);
    }
}
