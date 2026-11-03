package com.isd.ai.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Captures each real response before Spring AI folds tool-round usage into cumulative totals. */
@Component
public class AiUsageInterceptor implements ClientHttpRequestInterceptor {
    private final ObjectMapper json;

    public AiUsageInterceptor(ObjectMapper json) { this.json = json; }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        UsageInvocation invocation = UsageInvocation.current();
        if (invocation == null) return execution.execute(request, body);
        int attempt = invocation.startAttempt();
        long started = System.nanoTime();
        ClientHttpResponse response = null;
        Integer status = null;
        try {
            response = execution.execute(request, body);
            status = response.getStatusCode().value();
            byte[] bytes = StreamUtils.copyToByteArray(response.getBody());
            Metadata metadata = metadata(bytes);
            invocation.completeAttempt(attempt, status, metadata.usage(), metadata.responseId(), metadata.model(),
                    elapsed(started), response.getStatusCode().is2xxSuccessful());
            return new ReplayableResponse(response, bytes);
        } catch (IOException | RuntimeException ex) {
            if (response != null) response.close();
            invocation.completeAttempt(attempt, status, null, null, null, elapsed(started), false);
            throw ex;
        }
    }

    private Metadata metadata(byte[] bytes) {
        try {
            JsonNode root = json.readTree(bytes);
            if (root == null) return new Metadata(null, null, null);
            JsonNode usage = root.path("usage");
            Long input = nonnegative(usage.get("prompt_tokens"));
            Long output = nonnegative(usage.get("completion_tokens"));
            JsonNode cachedNode = usage.path("prompt_tokens_details").get("cached_tokens");
            if (cachedNode == null) cachedNode = usage.get("prompt_cache_hit_tokens");
            Long cached = cachedNode == null ? 0L : nonnegative(cachedNode);
            ProviderUsage tokens = input != null && output != null && cached != null && cached <= input
                    ? new ProviderUsage(input, cached, output) : null;
            return new Metadata(tokens, text(root.get("id")), text(root.get("model")));
        } catch (IOException | RuntimeException ignored) {
            // Never retain or log the body: it can contain user text or provider credential errors.
            return new Metadata(null, null, null);
        }
    }

    private static Long nonnegative(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0
                ? node.longValue() : null;
    }
    private static String text(JsonNode node) { return node != null && node.isTextual() ? node.textValue() : null; }
    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }

    record ProviderUsage(long inputTokens, long cachedInputTokens, long outputTokens) { }
    private record Metadata(ProviderUsage usage, String responseId, String model) { }
    private record ReplayableResponse(ClientHttpResponse delegate, byte[] bytes) implements ClientHttpResponse {
        @Override public HttpStatusCode getStatusCode() throws IOException { return delegate.getStatusCode(); }
        @Override public String getStatusText() throws IOException { return delegate.getStatusText(); }
        @Override public HttpHeaders getHeaders() { return delegate.getHeaders(); }
        @Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
        @Override public void close() { delegate.close(); }
    }
}
