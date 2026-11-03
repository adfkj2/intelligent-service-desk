package com.isd.ai.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isd.ai.admin.AiRuntimeSnapshot;
import com.isd.ai.dto.ChatAnswerResponse;
import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiUsageFailureTest {
    @Test
    void unavailableInitialLedgerFailsClosedWithoutLeakingUnderlyingError() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenThrow(new IllegalStateException("private connection detail"));
        AiUsageService service = new AiUsageService(mock(JdbcTemplate.class), transactions);
        AiRuntimeSnapshot snapshot = new AiRuntimeSnapshot(null, null, "DEEPSEEK", "environment", "https://api.deepseek.com", "deepseek-chat",
                "sk-secret", "cret", null, null, null, null);
        assertThatThrownBy(() -> service.begin(snapshot, 12L, "CHAT"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR))
                .hasMessageNotContaining("sk-secret").hasMessageNotContaining("private connection detail");
    }

    @Test
    void alreadyConsumedAnswerSurvivesLedgerWriteFailure() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenThrow(new IllegalStateException("unavailable"));
        AiUsageService service = new AiUsageService(mock(JdbcTemplate.class), transactions);
        UsageInvocation invocation = new UsageInvocation(service, "known-call");
        assertThat(invocation.run(() -> "existing answer")).isEqualTo("existing answer");
        assertThatCode(() -> invocation.businessOutcome("SUCCESS")).doesNotThrowAnyException();
        assertThatCode(() -> service.correctFallback("known-call", 12L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> invocation.businessOutcome("invalid")).isInstanceOf(BusinessException.class);
    }

    @Test
    void nestedWorkerScopesRestorePreviousInvocationAndClearAfterErrors() {
        AiUsageService service = mock(AiUsageService.class);
        UsageInvocation outer = new UsageInvocation(service, "outer");
        UsageInvocation inner = new UsageInvocation(service, "inner");
        outer.run(() -> {
            assertThat(UsageInvocation.current()).isSameAs(outer);
            inner.run(() -> { assertThat(UsageInvocation.current()).isSameAs(inner); return "inner reply"; });
            assertThat(UsageInvocation.current()).isSameAs(outer);
            return "outer reply";
        });
        assertThat(UsageInvocation.current()).isNull();
        assertThatThrownBy(() -> inner.run(() -> { throw new AssertionError("worker stopped"); })).isInstanceOf(AssertionError.class);
        assertThat(UsageInvocation.current()).isNull();
        verify(service).finish("inner", "ERROR");
    }

    @Test
    void unreadableResponseClosesConnectionAndRecordsUnknownAttempt() throws Exception {
        AiUsageService service = mock(AiUsageService.class);
        UsageInvocation invocation = new UsageInvocation(service, "broken-body");
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(response.getStatusCode()).thenReturn(HttpStatus.OK);
        when(response.getBody()).thenThrow(new IOException("body incomplete"));
        AiUsageInterceptor interceptor = new AiUsageInterceptor(new ObjectMapper());
        assertThatThrownBy(() -> invocation.run(() -> {
            try { interceptor.intercept(new MockClientHttpRequest(HttpMethod.POST, URI.create("https://example.com/chat/completions")),
                    new byte[0], (request, bytes) -> response); }
            catch (IOException ex) { throw new IllegalStateException(ex); }
            return "unreachable";
        })).isInstanceOf(IllegalStateException.class);
        verify(response).close();
        verify(service).completeAttempt(eq("broken-body"), eq(1), eq(200), isNull(), isNull(), isNull(), anyLong(), eq(false));
    }

    @Test
    void capsRawRequestsBeforeSendingOrInsertingSeventeenthAttempt() {
        AiUsageService service = mock(AiUsageService.class);
        UsageInvocation invocation = new UsageInvocation(service, "bounded-tools");
        AiUsageInterceptor interceptor = new AiUsageInterceptor(new ObjectMapper());
        AtomicInteger sent = new AtomicInteger();
        assertThatThrownBy(() -> invocation.run(() -> {
            for (int number = 0; number < 17; number++) {
                try (ClientHttpResponse response = interceptor.intercept(
                        new MockClientHttpRequest(HttpMethod.POST, URI.create("https://example.com/chat/completions")), new byte[0],
                        (request, bytes) -> {
                            sent.incrementAndGet();
                            return new MockClientHttpResponse("{\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}".getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                        })) {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                } catch (IOException ex) { throw new IllegalStateException(ex); }
            }
            return "unreachable";
        })).isInstanceOf(IllegalStateException.class).hasMessage("AI upstream attempt limit reached");
        assertThat(sent).hasValue(16);
        verify(service, times(16)).startAttempt(eq("bounded-tools"), org.mockito.ArgumentMatchers.anyInt());
        verify(service, never()).startAttempt("bounded-tools", 17);
        verify(service).finish("bounded-tools", "ERROR");
        assertThat(UsageInvocation.current()).isNull();
    }

    @Test
    void usageCorrelationStaysInternalAcrossCopiesAndJsonRoundTrips() throws Exception {
        ObjectMapper json = new ObjectMapper();
        ChatAnswerResponse original = new ChatAnswerResponse("answer", "LLM", List.of("title"), null, 17L, List.of());
        ChatAnswerResponse scoped = original.withUsageInvocation("internal-call-id").withConversation("conversation-id");
        assertThat(scoped.usageInvocationId()).isEqualTo("internal-call-id");
        assertThat(scoped.answerId()).isEqualTo(17L);
        assertThat(new ChatAnswerResponse("short", "LLM", List.of()).usageInvocationId()).isNull();
        String wire = json.writeValueAsString(scoped);
        assertThat(wire).contains("conversation-id", "\"answerId\":17").doesNotContain("usageInvocationId", "internal-call-id");
        ChatAnswerResponse parsed = json.readValue(wire.substring(0, wire.length() - 1) + ",\"usageInvocationId\":\"client-forged\"}", ChatAnswerResponse.class);
        assertThat(parsed.usageInvocationId()).isNull();
    }
}
