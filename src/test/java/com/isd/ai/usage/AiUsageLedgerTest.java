package com.isd.ai.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isd.ai.admin.AiRuntimeSnapshot;
import com.isd.common.exception.BusinessException;
import com.isd.common.time.BusinessTime;
import com.isd.support.AbstractIntegrationTest;
import com.isd.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"isd.ai.usage.stale-check-ms=3600000"})
@AutoConfigureMockMvc
class AiUsageLedgerTest extends AbstractIntegrationTest {
    private static final long USER_ID = 983001L;
    private static final Map<String, String> FILTER = Map.of("userId", Long.toString(USER_ID));
    @Autowired AiUsageService service;
    @Autowired AiUsageInterceptor interceptor;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;

    @AfterEach
    void cleanupOwnRecords() { jdbc.update("DELETE FROM ai_usage_call WHERE user_id=?", USER_ID); }

    @Test
    void countsRawToolRoundsOnceSnapshotsPricesAndReturnsReplayableBodies() throws Exception {
        UsageInvocation invocation = service.begin(snapshot("CNY"), USER_ID, "ASSIST");
        invocation.run(() -> {
            assertThat(response("{\"id\":\"r1\",\"model\":\"resolved-model\",\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"prompt_tokens_details\":{\"cached_tokens\":40},\"completion_tokens_details\":{\"reasoning_tokens\":5}}}", HttpStatus.OK))
                    .contains("\"r1\"");
            response("{\"id\":\"r2\",\"model\":\"resolved-model\",\"usage\":{\"prompt_tokens\":200,\"completion_tokens\":30,\"prompt_cache_hit_tokens\":50}}", HttpStatus.OK);
            return "done";
        });
        invocation.businessOutcome("SUCCESS");
        AiUsageResponses.Call row = onlyCall();
        assertThat(row.upstreamRequests()).isEqualTo(2);
        assertThat(row.inputTokens()).isEqualTo(300);
        assertThat(row.cachedInputTokens()).isEqualTo(90);
        assertThat(row.outputTokens()).isEqualTo(50);
        assertThat(row.actualModel()).isEqualTo("resolved-model");
        assertThat(row.usageComplete()).isTrue();
        assertThat(row.estimatedCost()).isEqualByComparingTo("0.000710");
        assertThat(row.configurationVersion()).isEqualTo(7L);
        assertThat(json.writeValueAsString(row)).doesNotContain("secret-key-never-store", "choices", "reasoning_tokens");
        assertThat(service.summary(FILTER).upstreamRequests()).isEqualTo(2);
        assertThat(service.daily(FILTER).getFirst().inputTokens()).isEqualTo(300);
        assertThat(service.users(FILTER).getFirst().outputTokens()).isEqualTo(50);
        assertThat(service.calls(Map.of("userId", Long.toString(USER_ID), "model", "resolved-model")).total()).isEqualTo(1);
        assertThat(service.summary(Map.of("userId", Long.toString(USER_ID), "model", "resolved-model")).calls()).isEqualTo(1);
        assertThat(service.calls(Map.of("userId", Long.toString(USER_ID), "model", "test-model")).total()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT input_price FROM ai_usage_call WHERE id=?", BigDecimal.class, invocation.id()))
                .isEqualByComparingTo("2");
    }

    @Test
    void retryWithoutUsageLeavesKnownTokensAndMarksCostUnknown() {
        UsageInvocation invocation = service.begin(snapshot("USD"), USER_ID, "CHAT");
        invocation.run(() -> {
            response("{\"error\":\"temporary\"}", HttpStatus.SERVICE_UNAVAILABLE);
            response(usage(100, 20), HttpStatus.OK);
            return "ok";
        });
        invocation.businessOutcome("SUCCESS");
        assertThat(onlyCall().upstreamRequests()).isEqualTo(2);
        assertThat(onlyCall().inputTokens()).isEqualTo(100);
        assertThat(onlyCall().usageComplete()).isFalse();
        assertThat(onlyCall().estimatedCost()).isNull();
        AiUsageResponses.Summary summary = service.summary(FILTER);
        assertThat(summary.unknownUsageCalls()).isEqualTo(1);
        assertThat(summary.costs().getFirst().unknownCostCalls()).isEqualTo(1);
    }

    @Test
    void timeoutDoesNotLoseLaterTokensOrOverwriteBusinessResult() throws Exception {
        UsageInvocation invocation = service.begin(snapshot("CNY"), USER_ID, "DRAFT");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> invocation.run(() -> {
                started.countDown();
                try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                response(usage(80, 10), HttpStatus.OK);
                return "late reply";
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            invocation.businessOutcome("TIMEOUT");
            assertThat(onlyCall().upstreamOutcome()).isEqualTo("PENDING");
            release.countDown();
            assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("late reply");
        }
        invocation.businessOutcome("SUCCESS");
        assertThat(onlyCall().businessOutcome()).isEqualTo("TIMEOUT");
        assertThat(onlyCall().upstreamOutcome()).isEqualTo("SUCCESS");
        assertThat(onlyCall().inputTokens()).isEqualTo(80);
        assertThat(onlyCall().usageComplete()).isTrue();
        assertThat(service.summary(FILTER).failedCalls()).isEqualTo(1);
    }

    @Test
    void failedLaterToolRoundRetainsEarlierUsageAndThreadScopeIsRestored() throws Exception {
        UsageInvocation invocation = service.begin(snapshot("CNY"), USER_ID, "KNOWLEDGE_DRAFT");
        assertThatThrownBy(() -> invocation.run(() -> {
            response(usage(100, 30), HttpStatus.OK);
            try {
                interceptor.intercept(request(), new byte[0], (req, body) -> { throw new IOException("network unavailable"); });
            } catch (IOException ex) { throw new IllegalStateException("provider request failed"); }
            return "unreachable";
        })).isInstanceOf(IllegalStateException.class);
        invocation.businessOutcome("ERROR");
        assertThat(UsageInvocation.current()).isNull();
        assertThat(onlyCall().upstreamRequests()).isEqualTo(2);
        assertThat(onlyCall().inputTokens()).isEqualTo(100);
        assertThat(onlyCall().upstreamOutcome()).isEqualTo("ERROR");
        assertThat(onlyCall().estimatedCost()).isNull();
        response(usage(900, 900), HttpStatus.OK);
        assertThat(onlyCall().upstreamRequests()).isEqualTo(2);
    }

    @Test
    void malformedOrNegativeUsageIsUnknownAndDoesNotChangeProviderResponse() {
        UsageInvocation invocation = service.begin(snapshot("CNY"), USER_ID, "CONNECTION_TEST");
        invocation.run(() -> {
            assertThat(response("not json", HttpStatus.OK)).isEqualTo("not json");
            response("{\"usage\":{\"prompt_tokens\":-1,\"completion_tokens\":2}}", HttpStatus.OK);
            response("{\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"prompt_cache_hit_tokens\":5}}", HttpStatus.OK);
            return "synthetic";
        });
        invocation.businessOutcome("FALLBACK");
        assertThat(onlyCall().upstreamRequests()).isEqualTo(3);
        assertThat(onlyCall().usageComplete()).isFalse();
        assertThat(onlyCall().inputTokens()).isZero();
        assertThat(service.summary(Map.of("userId", Long.toString(USER_ID), "purpose", "CONNECTION_TEST")).calls()).isEqualTo(1);
    }

    @Test
    void missingPriceAndMultipleCurrenciesRemainDistinct() {
        UsageInvocation cny = service.begin(snapshot("CNY"), USER_ID, "CHAT");
        cny.run(() -> { response(usage(100, 10), HttpStatus.OK); return "ok"; }); cny.businessOutcome("SUCCESS");
        UsageInvocation usd = service.begin(snapshot("USD"), USER_ID, "CHAT");
        usd.run(() -> { response(usage(200, 20), HttpStatus.OK); return "ok"; }); usd.businessOutcome("SUCCESS");
        AiRuntimeSnapshot missingPrice = new AiRuntimeSnapshot(99L, 8L, "CUSTOM", "No prices", "https://example.com/v1", "test-model",
                "secret-key-never-store", "tore", null, null, null, null);
        UsageInvocation missing = service.begin(missingPrice, USER_ID, "CHAT");
        missing.run(() -> { response(usage(80, 20), HttpStatus.OK); return "ok"; }); missing.businessOutcome("SUCCESS");
        AiUsageResponses.Summary totals = service.summary(FILTER);
        assertThat(totals.calls()).isEqualTo(3);
        assertThat(totals.costs()).extracting(AiUsageResponses.Cost::currency).containsExactly("CNY", "UNCONFIGURED", "USD");
        assertThat(totals.costs().get(1).unknownCostCalls()).isEqualTo(1);
        assertThat(service.calls(Map.of("userId", Long.toString(USER_ID), "currency", "USD", "provider", "CUSTOM")).total()).isEqualTo(1);
        assertThat(service.calls(Map.of("userId", Long.toString(USER_ID), "configurationId", "12", "model", "test-model", "outcome", "SUCCESS", "size", "1", "page", "2")).items()).hasSize(1);
    }

    @Test
    void administratorEndpointsProtectMetadataAndValidateFilters() throws Exception {
        UsageInvocation invocation = service.begin(snapshot("CNY"), USER_ID, "CHAT");
        invocation.run(() -> "no outbound request"); invocation.businessOutcome("SUCCESS");
        for (String endpoint : new String[]{"summary", "daily", "users", "calls"}) {
            mvc.perform(get("/api/admin/ai/usage/" + endpoint)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/admin/ai/usage/" + endpoint).with(user(TestUsers.employee(USER_ID)))).andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/ai/usage/" + endpoint).with(user(TestUsers.support(USER_ID)))).andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/ai/usage/" + endpoint).param("userId", Long.toString(USER_ID)).with(user(TestUsers.admin(USER_ID))))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(get("/api/admin/ai/usage/calls").param("userId", Long.toString(USER_ID)).with(user(TestUsers.admin(USER_ID))))
                .andExpect(jsonPath("$.data.items[0].inputTokens").value(0))
                .andExpect(jsonPath("$.data.items[0].usageComplete").value(true));
        for (Map<String, String> params : new Map[]{Map.of("from", "bad"), Map.of("userId", "bad"), Map.of("outcome", "invalid"),
                Map.of("purpose", "invalid"), Map.of("size", "101"), Map.of("page", "0"), Map.of("to", "2099-01-01")}) {
            var request = get("/api/admin/ai/usage/calls").with(user(TestUsers.admin(USER_ID)));
            params.forEach(request::param); mvc.perform(request).andExpect(status().isBadRequest());
        }
        assertThatThrownBy(() -> service.summary(Map.of("from", BusinessTime.now().minusDays(366).toLocalDate().toString())))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.begin(snapshot("CNY"), USER_ID, "INVALID")).isInstanceOf(BusinessException.class);
    }

    @Test
    void interruptedCallsStayIncompleteAndCleanupCascadesOnlyExpiredMetadata() {
        UsageInvocation pending = service.begin(snapshot("CNY"), USER_ID, "CHAT");
        jdbc.update("UPDATE ai_usage_call SET created_at=? WHERE id=?", BusinessTime.now().minusMinutes(20), pending.id());
        service.markInterrupted();
        assertThat(onlyCall().upstreamOutcome()).isEqualTo("INTERRUPTED");
        assertThat(onlyCall().usageComplete()).isFalse();
        pending.run(() -> { response(usage(10, 5), HttpStatus.OK); return "late"; });
        assertThat(onlyCall().upstreamOutcome()).isEqualTo("SUCCESS");
        assertThat(onlyCall().usageComplete()).isTrue();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("UPDATE ai_usage_call SET created_at=? WHERE id=?", BusinessTime.now().minusDays(400), pending.id());
            service.cleanup();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_call WHERE id=?", Integer.class, pending.id())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_attempt WHERE call_id=?", Integer.class, pending.id())).isZero();
            status.setRollbackOnly();
        });
        assertThat(onlyCall().upstreamRequests()).isEqualTo(1);
    }

    @Test
    void invalidJsonCorrectionRequiresOwnerSuccessfulSuggestionAndKeepsConsumedTokens() {
        for (String purpose : new String[]{"DRAFT", "ASSIST", "KNOWLEDGE_DRAFT"}) {
            UsageInvocation invocation = service.begin(snapshot("CNY"), USER_ID, purpose);
            invocation.run(() -> { response(usage(70, 20), HttpStatus.OK); return "invalid structured output"; });
            invocation.businessOutcome("SUCCESS");
            service.correctFallback(invocation.id(), USER_ID + 1);
            assertThat(call(invocation.id()).businessOutcome()).isEqualTo("SUCCESS");
            service.correctFallback(invocation.id(), USER_ID);
            assertThat(call(invocation.id()).businessOutcome()).isEqualTo("FALLBACK");
            assertThat(call(invocation.id()).inputTokens()).isEqualTo(70);
            assertThat(call(invocation.id()).estimatedCost()).isEqualByComparingTo("0.000220");
            service.correctFallback(invocation.id(), USER_ID);
            assertThat(call(invocation.id()).businessOutcome()).isEqualTo("FALLBACK");
        }
        for (String outcome : new String[]{"TIMEOUT", "ERROR"}) {
            UsageInvocation invocation = service.begin(snapshot("CNY"), USER_ID, "DRAFT");
            invocation.run(() -> "late or unusable result"); invocation.businessOutcome(outcome);
            service.correctFallback(invocation.id(), USER_ID);
            assertThat(call(invocation.id()).businessOutcome()).isEqualTo(outcome);
        }
        UsageInvocation chat = service.begin(snapshot("CNY"), USER_ID, "CHAT");
        chat.run(() -> "ordinary chat"); chat.businessOutcome("SUCCESS");
        service.correctFallback(chat.id(), USER_ID);
        service.correctFallback(null, USER_ID);
        service.correctFallback(chat.id(), null);
        assertThat(call(chat.id()).businessOutcome()).isEqualTo("SUCCESS");
    }

    private AiUsageResponses.Call onlyCall() { return service.calls(FILTER).items().getFirst(); }
    private AiUsageResponses.Call call(String id) { return service.calls(FILTER).items().stream().filter(row -> id.equals(row.id())).findFirst().orElseThrow(); }
    private static AiRuntimeSnapshot snapshot(String currency) {
        return new AiRuntimeSnapshot(12L, 7L, "DEEPSEEK", "Usage test", "https://example.com/v1", "test-model",
                "secret-key-never-store", "tore", currency, new BigDecimal("2"), BigDecimal.ONE, new BigDecimal("4"));
    }
    private static MockClientHttpRequest request() { return new MockClientHttpRequest(HttpMethod.POST, URI.create("https://example.com/v1/chat/completions")); }
    private static String usage(int input, int output) { return "{\"usage\":{\"prompt_tokens\":" + input + ",\"completion_tokens\":" + output + "}}"; }
    private String response(String body, HttpStatus status) {
        try (ClientHttpResponse result = interceptor.intercept(request(), new byte[0], (req, data) ->
                new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status))) {
            assertThat(result.getStatusCode()).isEqualTo(status);
            assertThat(result.getHeaders()).isNotNull();
            assertThat(result.getStatusText()).isNotNull();
            return new String(result.getBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) { throw new IllegalStateException(ex); }
    }
}
