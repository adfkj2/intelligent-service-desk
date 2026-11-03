package com.isd.ai.admin;

/** Performs an explicitly requested synthetic, metered compatibility test; no business data. */
public interface AiConnectionTester {
    void test(AiRuntimeSnapshot snapshot);
}
