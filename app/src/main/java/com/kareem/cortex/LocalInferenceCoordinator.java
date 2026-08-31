package com.kareem.cortex;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Process-local admission coordinator for the single non-preemptible llama model handle.
 * Authoritative work wins admission; shadow work never waits and is skipped when the
 * native lane is busy, authority is pending, or authority just completed.
 */
public final class LocalInferenceCoordinator {
    public static final long SHADOW_COOLDOWN_MS = 10_000L;

    public enum Priority {
        AUTHORITATIVE,
        INTERACTIVE,
        LEGACY,
        SHADOW
    }

    public interface Task<T> {
        T run() throws Exception;
    }

    public interface NativeStartListener {
        void onNativeStarted(long atMs);
    }

    public static final class BusyException extends Exception {
        BusyException(String message) {
            super(message);
        }
    }

    public static final class CancelledException extends Exception {
        CancelledException() {
            super("Local inference cancelled before native start");
        }
    }

    public static final class Result<T> {
        public final T value;
        public final long enqueuedAt;
        public final long nativeStartedAt;
        public final long nativeFinishedAt;
        public final long queueWaitMs;
        public final long nativeTotalMs;
        public final long totalMs;

        Result(
                T value,
                long enqueuedAt,
                long nativeStartedAt,
                long nativeFinishedAt
        ) {
            this.value = value;
            this.enqueuedAt = enqueuedAt;
            this.nativeStartedAt = nativeStartedAt;
            this.nativeFinishedAt = nativeFinishedAt;
            this.queueWaitMs = Math.max(0L, nativeStartedAt - enqueuedAt);
            this.nativeTotalMs = Math.max(0L, nativeFinishedAt - nativeStartedAt);
            this.totalMs = Math.max(0L, nativeFinishedAt - enqueuedAt);
        }
    }

    private static final Object LOCK = new Object();
    private static final AtomicInteger AUTHORITY_PENDING = new AtomicInteger();
    private static final AtomicInteger INTERACTIVE_PENDING = new AtomicInteger();
    private static final AtomicInteger LEGACY_PENDING = new AtomicInteger();
    private static final AtomicBoolean NATIVE_BUSY = new AtomicBoolean(false);
    private static final ThreadLocal<Integer> EXECUTION_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static volatile long lastAuthorityAt = 0L;

    private LocalInferenceCoordinator() {}

    public static <T> Result<T> execute(Priority priority, Task<T> task) throws Exception {
        return execute(priority, null, null, task);
    }

    public static <T> Result<T> execute(
            Priority priority,
            NativeStartListener listener,
            BooleanSupplier cancelled,
            Task<T> task
    ) throws Exception {
        if (priority == null) priority = Priority.INTERACTIVE;
        if (task == null) throw new IllegalArgumentException("Local inference task is null");

        final Priority p = priority;
        final long enqueuedAt = System.currentTimeMillis();
        incrementPending(p);

        boolean acquired = false;
        long nativeStartedAt = 0L;
        try {
            synchronized (LOCK) {
                if (p == Priority.SHADOW) {
                    if (!canStartShadowLocked(enqueuedAt)) {
                        throw new BusyException("SKIPPED_BUSY");
                    }
                } else {
                    while (NATIVE_BUSY.get() || higherPriorityPending(p)) {
                        if (cancelled != null && cancelled.getAsBoolean()) {
                            throw new CancelledException();
                        }
                        try {
                            LOCK.wait(50L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw interrupted;
                        }
                    }
                }

                if (cancelled != null && cancelled.getAsBoolean()) {
                    throw new CancelledException();
                }

                decrementWaitingOnStart(p);
                NATIVE_BUSY.set(true);
                acquired = true;
                nativeStartedAt = System.currentTimeMillis();
            }

            if (listener != null) listener.onNativeStarted(nativeStartedAt);
            enterExecution();
            try {
                T value = task.run();
                long nativeFinishedAt = System.currentTimeMillis();
                return new Result<>(value, enqueuedAt, nativeStartedAt, nativeFinishedAt);
            } finally {
                exitExecution();
            }
        } finally {
            synchronized (LOCK) {
                if (!acquired) {
                    decrementPendingIfNotStarted(p);
                } else {
                    NATIVE_BUSY.set(false);
                    if (p == Priority.AUTHORITATIVE) {
                        AUTHORITY_PENDING.decrementAndGet();
                        lastAuthorityAt = System.currentTimeMillis();
                    }
                }
                LOCK.notifyAll();
            }
        }
    }

    /** True only while the current thread already owns the coordinated native lane. */
    public static boolean isInsideExecution() {
        return EXECUTION_DEPTH.get() > 0;
    }

    public static boolean canStartShadow() {
        synchronized (LOCK) {
            return canStartShadowLocked(System.currentTimeMillis());
        }
    }

    public static int authorityPendingCount() {
        return Math.max(0, AUTHORITY_PENDING.get());
    }

    public static boolean nativeBusy() {
        return NATIVE_BUSY.get();
    }

    public static long lastAuthorityAt() {
        return lastAuthorityAt;
    }

    public static boolean isBusy(Throwable error) {
        Throwable x = error;
        while (x != null) {
            if (x instanceof BusyException) return true;
            x = x.getCause();
        }
        return false;
    }

    private static void enterExecution() {
        EXECUTION_DEPTH.set(EXECUTION_DEPTH.get() + 1);
    }

    private static void exitExecution() {
        int next = EXECUTION_DEPTH.get() - 1;
        if (next <= 0) EXECUTION_DEPTH.remove();
        else EXECUTION_DEPTH.set(next);
    }

    private static boolean canStartShadowLocked(long now) {
        if (AUTHORITY_PENDING.get() > 0) return false;
        if (INTERACTIVE_PENDING.get() > 0) return false;
        if (LEGACY_PENDING.get() > 0) return false;
        if (NATIVE_BUSY.get()) return false;
        return lastAuthorityAt <= 0L || now - lastAuthorityAt >= SHADOW_COOLDOWN_MS;
    }

    private static boolean higherPriorityPending(Priority p) {
        switch (p) {
            case AUTHORITATIVE:
                return false;
            case INTERACTIVE:
                return AUTHORITY_PENDING.get() > 0;
            case LEGACY:
                return AUTHORITY_PENDING.get() > 0 || INTERACTIVE_PENDING.get() > 0;
            case SHADOW:
            default:
                return AUTHORITY_PENDING.get() > 0
                        || INTERACTIVE_PENDING.get() > 0
                        || LEGACY_PENDING.get() > 0;
        }
    }

    private static void incrementPending(Priority p) {
        switch (p) {
            case AUTHORITATIVE:
                AUTHORITY_PENDING.incrementAndGet();
                break;
            case INTERACTIVE:
                INTERACTIVE_PENDING.incrementAndGet();
                break;
            case LEGACY:
                LEGACY_PENDING.incrementAndGet();
                break;
            case SHADOW:
            default:
                break;
        }
    }

    /**
     * Authority remains "pending" for its whole native execution so lower priority work
     * cannot enter behind it. Other priorities stop being pending once they own native.
     */
    private static void decrementWaitingOnStart(Priority p) {
        switch (p) {
            case INTERACTIVE:
                INTERACTIVE_PENDING.decrementAndGet();
                break;
            case LEGACY:
                LEGACY_PENDING.decrementAndGet();
                break;
            case AUTHORITATIVE:
            case SHADOW:
            default:
                break;
        }
    }

    private static void decrementPendingIfNotStarted(Priority p) {
        switch (p) {
            case AUTHORITATIVE:
                AUTHORITY_PENDING.decrementAndGet();
                break;
            case INTERACTIVE:
                INTERACTIVE_PENDING.decrementAndGet();
                break;
            case LEGACY:
                LEGACY_PENDING.decrementAndGet();
                break;
            case SHADOW:
            default:
                break;
        }
    }
}
