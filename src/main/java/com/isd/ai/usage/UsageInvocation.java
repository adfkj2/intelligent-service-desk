package com.isd.ai.usage;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** One logical operation; worker lifetime is independent of the waiting HTTP request. */
public final class UsageInvocation {
    private static final int MAX_UPSTREAM_ATTEMPTS = 16;
    private static final ThreadLocal<UsageInvocation> CURRENT = new ThreadLocal<>();
    private final AiUsageService service;
    private final String id;
    private final AtomicInteger attempts = new AtomicInteger();

    UsageInvocation(AiUsageService service, String id) {
        this.service = service;
        this.id = id;
    }

    public String id() { return id; }

    static UsageInvocation current() { return CURRENT.get(); }

    public <T> T run(Supplier<T> operation) {
        UsageInvocation previous = CURRENT.get();
        CURRENT.set(this);
        try {
            T result = operation.get();
            service.finish(id, "SUCCESS");
            return result;
        } catch (RuntimeException | Error ex) {
            service.finish(id, "ERROR");
            throw ex;
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    public void businessOutcome(String outcome) { service.businessOutcome(id, outcome); }

    int startAttempt() {
        int number = attempts.incrementAndGet();
        if (number > MAX_UPSTREAM_ATTEMPTS) {
            throw new IllegalStateException("AI upstream attempt limit reached");
        }
        service.startAttempt(id, number);
        return number;
    }

    void completeAttempt(int number, Integer status, AiUsageInterceptor.ProviderUsage usage,
                         String responseId, String model, long durationMs, boolean success) {
        service.completeAttempt(id, number, status, usage, responseId, model, durationMs, success);
    }
}
