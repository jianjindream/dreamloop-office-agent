package com.jianjin.assistant.interfaces.http.security;

public final class RequestIdentityHolder {
    private static final ThreadLocal<RequestIdentity> CURRENT = new ThreadLocal<>();
    private static final RequestIdentity DEFAULT = new RequestIdentity("default", "default");

    private RequestIdentityHolder() {}
    public static RequestIdentity current() { return CURRENT.get() == null ? DEFAULT : CURRENT.get(); }
    static void set(RequestIdentity identity) { CURRENT.set(identity); }
    static void clear() { CURRENT.remove(); }
}
