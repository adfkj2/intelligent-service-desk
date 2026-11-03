package com.isd.ai;

import com.isd.ai.admin.AiConfigurationService;
import com.isd.ai.admin.AiRuntimeSnapshot;
import com.isd.ai.client.SpringAiLlmClient;
import com.isd.ai.client.TrackedConnectionTester;
import com.isd.ai.service.AgentService;
import com.isd.ai.service.AiChatAuditService;
import com.isd.ai.usage.AiUsageService;
import com.isd.auth.security.CurrentUserProvider;
import com.isd.common.metrics.IsdMetrics;
import com.isd.kb.service.KbService;
import com.isd.support.AbstractIntegrationTest;
import com.isd.support.TestUsers;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Full Spring AI HTTP path against a local fake vendor; never uses real model credentials. */
@SpringBootTest(properties = "spring.ai.openai.api-key=local-placeholder")
@ExtendWith(OutputCaptureExtension.class)
class SpringAiRuntimeClientTest extends AbstractIntegrationTest {
    private static final long USER_ID = 984001L;
    @Autowired SpringAiLlmClient client;
    @Autowired AiUsageService usage;
    @Autowired JdbcTemplate jdbc;
    private HttpServer server;
    private final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    @BeforeEach void startFakeVendor() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Authorization"), body));
            Reply reply = replies.poll();
            if (reply == null) reply = new Reply(400, "{}", 0);
            try { Thread.sleep(reply.delayMs()); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (reply.status() == 302) exchange.getResponseHeaders().set("Location", "/credential-leak");
            byte[] response = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), response.length);
            try (var stream = exchange.getResponseBody()) { stream.write(response); }
        });
        server.start();
    }

    @AfterEach void stopFakeVendor() {
        if (server != null) server.stop(0);
        jdbc.update("DELETE FROM ai_usage_call WHERE user_id=?", USER_ID);
    }

    @Test void snapshotsSelectCredentialsModelAndFullSdkBaseWithoutDuplicateVersionPath() {
        replies.add(ok("first", 12, 3, 2));
        replies.add(ok("second", 20, 4, 0));
        var first = snapshot("/vendor-a/v1", "test-key-A", "model-a");
        var second = snapshot("/compatible-mode/v1", "test-key-B", "model-b");
        var invocation = usage.begin(first, USER_ID, "CHAT");
        var result = invocation.run(() -> client.chat("synthetic question", first));
        invocation.businessOutcome("SUCCESS");
        assertThat(result.text()).isEqualTo("first");
        assertThat(result.model()).isEqualTo("fake-model");
        assertThat(result.responseId()).isEqualTo("fake-response");
        assertThat(result.inputTokens()).isEqualTo(12);
        assertThat(result.outputTokens()).isEqualTo(3);
        usage.begin(second, USER_ID, "CHAT").run(() -> client.chat("another synthetic question", second));
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).path()).isEqualTo("/vendor-a/v1/chat/completions");
        assertThat(requests.get(1).path()).isEqualTo("/compatible-mode/v1/chat/completions");
        assertThat(requests.get(0).authorization()).isEqualTo("Bearer test-key-A");
        assertThat(requests.get(1).authorization()).isEqualTo("Bearer test-key-B");
        assertThat(requests.get(0).body()).contains("model-a");
        assertThat(requests.get(1).body()).contains("model-b");
        assertThat(first.toString()).doesNotContain("test-key-A");
    }

    @Test void syntheticToolProbeCountsEachRawResponseExactlyOnce() {
        replies.add(new Reply(200, """
                {"id":"tool-response","object":"chat.completion","model":"fake-model","choices":[
                  {"index":0,"message":{"role":"assistant","content":null,"tool_calls":[
                    {"id":"probe-1","type":"function","function":{"name":"isd_connection_probe","arguments":"{}"}}]},"finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":2,"prompt_tokens_details":{"cached_tokens":3}}}
                """, 0));
        replies.add(ok("OK", 20, 3, 5));
        var snapshot = snapshot("/v1", "test-probe-key", "probe-model");
        var invocation = usage.begin(snapshot, USER_ID, "CONNECTION_TEST");
        invocation.run(() -> { client.probe(snapshot); return null; });
        invocation.businessOutcome("SUCCESS");
        var row = jdbc.queryForMap("SELECT * FROM ai_usage_call WHERE id=?", invocation.id());
        assertThat(((Number) row.get("upstream_requests")).intValue()).isEqualTo(2);
        assertThat(((Number) row.get("input_tokens")).longValue()).isEqualTo(30);
        assertThat(((Number) row.get("cached_input_tokens")).longValue()).isEqualTo(8);
        assertThat(((Number) row.get("output_tokens")).longValue()).isEqualTo(5);
        assertThat((BigDecimal) row.get("estimated_cost")).isEqualByComparingTo("0.000067");
        assertThat(requests).allSatisfy(request -> assertThat(request.body())
                .contains("isd_connection_probe").doesNotContain("query_my_tickets", "search_knowledge_base"));
        assertThat(requests.get(1).body()).contains("isd_probe_ok");
    }

    @Test void environmentModePreservesExistingSpringAiCompletionPath() {
        replies.add(ok("environment compatible", 9, 2, 0));
        var snapshot = new AiRuntimeSnapshot(null, 0L, "DEEPSEEK", "Environment",
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-model", "test-env-key", "fake",
                null, null, null, null);
        var invocation = usage.begin(snapshot, USER_ID, "CHAT");
        assertThat(invocation.run(() -> client.chat("synthetic environment question", snapshot)).text())
                .isEqualTo("environment compatible");
        assertThat(requests.getFirst().path()).isEqualTo("/v1/chat/completions");
    }

    @Test void retriesRecordUnknownAttemptAndNeverLogUpstreamCredentialEcho(CapturedOutput logs) {
        replies.add(new Reply(503, "{\"error\":\"echo-private-key-never-log\"}", 0));
        replies.add(ok("recovered", 17, 4, 0));
        var snapshot = snapshot("", "test-retry-key", "retry-model");
        var invocation = usage.begin(snapshot, USER_ID, "CHAT");
        assertThat(invocation.run(() -> client.chat("retry synthetic test", snapshot)).text()).isEqualTo("recovered");
        invocation.businessOutcome("SUCCESS");
        assertThat(requests).hasSize(2);
        var row = jdbc.queryForMap("SELECT upstream_requests,input_tokens,estimated_cost,usage_complete FROM ai_usage_call WHERE id=?", invocation.id());
        assertThat(((Number) row.get("upstream_requests")).intValue()).isEqualTo(2);
        assertThat(((Number) row.get("input_tokens")).longValue()).isEqualTo(17);
        assertThat(row.get("estimated_cost")).isNull();
        assertThat(logs.getAll()).doesNotContain("echo-private-key-never-log");
    }

    @Test void redirectDoesNotForwardAuthorizationAndProbeRejectsMissingToolCall() {
        replies.add(new Reply(302, "{}", 0));
        var snapshot = snapshot("", "test-redirect-key", "test-model");
        var invocation = usage.begin(snapshot, USER_ID, "CHAT");
        assertThatThrownBy(() -> invocation.run(() -> client.chat("redirect", snapshot)))
                .hasMessageContaining("302");
        assertThat(requests).hasSize(1);
        replies.add(ok("No tool used", 10, 2, 0));
        var probe = usage.begin(snapshot, USER_ID, "CONNECTION_TEST");
        assertThatThrownBy(() -> probe.run(() -> { client.probe(snapshot); return null; }))
                .hasMessageContaining("tool compatibility");
    }

    @Test void malformedSuccessfulResponseCannotLeakCredentialFragmentsThroughRetryLogs(CapturedOutput logs) {
        replies.add(new Reply(200, """
                {"id":"malformed","model":"fake-model","choices":[],
                 "usage":{"prompt_tokens":"private-fragment-must-not-log","completion_tokens":2}}
                """, 0));
        var snapshot = snapshot("", "test-parse-key", "test-model");
        var invocation = usage.begin(snapshot, USER_ID, "CHAT");
        assertThatThrownBy(() -> invocation.run(() -> client.chat("synthetic parse test", snapshot)))
                .isInstanceOf(RuntimeException.class);
        assertThat(requests).hasSize(1);
        assertThat(logs.getAll()).doesNotContain("private-fragment-must-not-log");
    }

    @Test void connectionTesterTracksBothPassingAndFailedSyntheticTests() {
        replies.add(new Reply(200, """
                {"id":"probe","model":"fake-model","choices":[{"index":0,"message":{"role":"assistant",
                 "tool_calls":[{"id":"p","type":"function","function":{"name":"isd_connection_probe","arguments":"{}"}}]},"finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":2,"completion_tokens":1}}
                """, 0));
        replies.add(ok("OK", 4, 2, 0));
        replies.add(ok("tool unavailable", 3, 1, 0));
        var users = mock(CurrentUserProvider.class);
        when(users.requireId()).thenReturn(USER_ID);
        var beans = new StaticListableBeanFactory(java.util.Map.of("client", client));
        var tester = new TrackedConnectionTester(beans.getBeanProvider(SpringAiLlmClient.class), usage, users);
        var snapshot = snapshot("", "test-tracked-key", "test-model");
        tester.test(snapshot);
        assertThatThrownBy(() -> tester.test(snapshot)).hasMessageContaining("测试未通过");
        assertThat(jdbc.queryForList("SELECT purpose FROM ai_usage_call WHERE user_id=?", String.class, USER_ID))
                .containsExactly("CONNECTION_TEST", "CONNECTION_TEST");
        assertThat(jdbc.queryForList("SELECT business_outcome FROM ai_usage_call WHERE user_id=?", String.class, USER_ID))
                .containsExactlyInAnyOrder("SUCCESS", "ERROR");
    }

    @Test void timedOutBusinessResponseRetainsLateSuccessfulProviderUsage() throws Exception {
        replies.add(new Reply(200, ok("late answer", 22, 7, 0).body(), 1600));
        var snapshot = snapshot("", "test-late-key", "late-model");
        var configurations = mock(AiConfigurationService.class);
        when(configurations.resolve()).thenReturn(Optional.of(snapshot));
        var kb = mock(KbService.class);
        when(kb.retrieve(anyString(), anyInt())).thenReturn(List.of());
        var users = mock(CurrentUserProvider.class);
        when(users.require()).thenReturn(TestUsers.employee(USER_ID));
        CountDownLatch finished = new CountDownLatch(1);
        var registry = new SimpleMeterRegistry();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var service = new AgentService(client, kb, mock(AiChatAuditService.class), users,
                    task -> pool.submit(() -> { try { task.run(); } finally { finished.countDown(); } }),
                    new IsdMetrics(registry), 1, configurations, usage);
            assertThat(service.chat("synthetic timeout").source()).isEqualTo("FALLBACK");
            assertThat(finished.await(8, TimeUnit.SECONDS)).isTrue();
        } finally { registry.close(); }
        var row = jdbc.queryForMap("SELECT business_outcome,upstream_outcome,input_tokens,output_tokens FROM ai_usage_call WHERE user_id=?", USER_ID);
        assertThat(row.get("business_outcome")).isEqualTo("TIMEOUT");
        assertThat(row.get("upstream_outcome")).isEqualTo("SUCCESS");
        assertThat(((Number) row.get("input_tokens")).longValue()).isEqualTo(22);
        assertThat(((Number) row.get("output_tokens")).longValue()).isEqualTo(7);
    }

    private AiRuntimeSnapshot snapshot(String prefix, String key, String model) {
        return new AiRuntimeSnapshot(9001L, 1L, "CUSTOM", "Fake provider",
                "http://127.0.0.1:" + server.getAddress().getPort() + prefix, model, key, "fake", "CNY",
                new BigDecimal("2"), BigDecimal.ONE, new BigDecimal("3"));
    }

    private static Reply ok(String answer, long input, long output, long cached) {
        return new Reply(200, """
                {"id":"fake-response","object":"chat.completion","model":"fake-model","choices":[
                 {"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":%d,"completion_tokens":%d,"total_tokens":%d,
                  "prompt_tokens_details":{"cached_tokens":%d}}}
                """.formatted(answer, input, output, input + output, cached), 0);
    }

    private record Reply(int status, String body, long delayMs) { }
    private record Request(String path, String authorization, String body) { }
}
