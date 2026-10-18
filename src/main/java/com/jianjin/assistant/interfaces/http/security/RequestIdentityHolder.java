package com.jianjin.assistant.interfaces.http.security;

import java.util.concurrent.Callable;

public final class RequestIdentityHolder {
    private static final ThreadLocal<RequestIdentity> CURRENT = new ThreadLocal<>();
    private static final RequestIdentity DEFAULT = new RequestIdentity("default", "default", "default", "");

    private RequestIdentityHolder() {}
    public static RequestIdentity current() { return CURRENT.get() == null ? DEFAULT : CURRENT.get(); }
    static void set(RequestIdentity identity) { CURRENT.set(identity); }
    static void clear() { CURRENT.remove(); }

    /** Bind an identity for the current thread and restore the previous value on close. */
    public static Scope bind(RequestIdentity identity) {
        RequestIdentity previous = CURRENT.get();
        CURRENT.set(identity == null ? DEFAULT : identity);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    /** Propagate request identity into worker threads without leaking ThreadLocal state. */
    public static <T> T callAs(RequestIdentity identity, Callable<T> action) throws Exception {
        try (Scope ignored = bind(identity)) {
            return action.call();
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override void close();
    }
}
