package com.isd.ai.client;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;

import com.isd.ai.admin.AiRuntimeSnapshot;
import com.isd.ai.tools.KnowledgeTools;
import com.isd.ai.tools.TicketTools;
import com.isd.ai.usage.AiUsageInterceptor;
import com.isd.common.config.AiConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

/** Each request pins its configuration, including subsequent tool rounds and retries. */
@Component
public class SpringAiLlmClient implements LlmClient {
    private final OpenAiChatModel template;
    private final TicketTools ticketTools;
    private final KnowledgeTools knowledgeTools;
    private final AiUsageInterceptor usage;
    private final RetryTemplate retries;
    private final String environmentCompletionsPath;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public SpringAiLlmClient(OpenAiChatModel template, TicketTools ticketTools,
                            KnowledgeTools knowledgeTools, AiUsageInterceptor usage,
                            @Value("${spring.ai.retry.max-attempts:2}") int maxAttempts,
                            @Value("${spring.ai.retry.backoff.initial-interval:500ms}") Duration initialBackoff,
                            @Value("${spring.ai.openai.chat.completions-path:/v1/chat/completions}") String environmentCompletionsPath) {
        this.template = template;
        this.ticketTools = ticketTools;
        this.knowledgeTools = knowledgeTools;
        this.usage = usage;
        this.environmentCompletionsPath = environmentCompletionsPath;
        // Boot's default retry listener logs raw exception messages and response fragments.
        this.retries = new RetryTemplate();
        retries.setRetryPolicy(new SimpleRetryPolicy(Math.max(1, maxAttempts),
                Map.of(TransientAiException.class, true), true));
        var backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(Math.max(1, initialBackoff.toMillis()));
        backoff.setMaxInterval(10000);
        retries.setBackOffPolicy(backoff);
    }

    @Override
    public LlmResult chat(String question, AiRuntimeSnapshot snapshot) {
        ChatResponse response = ChatClient.builder(model(snapshot))
                .defaultSystem(AiConfig.SYSTEM_PROMPT)
                .defaultTools(ticketTools, knowledgeTools)
                .build().prompt().user(question).call().chatResponse();
        return result(response);
    }

    /** Only a synthetic callback is available here, never real ticket or knowledge tools. */
    public void probe(AiRuntimeSnapshot snapshot) {
        AtomicBoolean called = new AtomicBoolean();
        var probe = FunctionToolCallback.builder("isd_connection_probe", () -> {
            called.set(true);
            return "isd_probe_ok";
        }).description("Synthetic connectivity check; returns fixed test data.").build();
        ChatResponse response = ChatClient.builder(model(snapshot))
                .defaultSystem("This is a connectivity test with synthetic data. Call isd_connection_probe once, then reply OK.")
                .defaultToolCallbacks(probe).build()
                .prompt().user("Call the test tool and confirm its result.").call().chatResponse();
        if (!called.get() || result(response).text() == null || result(response).text().isBlank()) {
            throw new NonTransientAiException("Model did not complete the synthetic tool compatibility check.");
        }
    }

    private OpenAiChatModel model(AiRuntimeSnapshot snapshot) {
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(40));
        var rest = RestClient.builder().requestFactory(factory).requestInterceptor(usage);
        // The SDK base already contains any /v1 or /compatible-mode/v1 prefix.
        var api = OpenAiApi.builder().baseUrl(snapshot.baseUrl()).apiKey(snapshot.apiKey())
                .completionsPath(snapshot.configurationId() == null ? environmentCompletionsPath : "/chat/completions")
                .restClientBuilder(rest)
                .responseErrorHandler(new SafeErrorHandler()).build();
        var options = OpenAiChatOptions.builder().model(snapshot.model());
        if ("DEEPSEEK".equals(snapshot.provider()) || "QWEN".equals(snapshot.provider())) options.temperature(0.3);
        return template.mutate().openAiApi(api).retryTemplate(retries)
                .defaultOptions(options.build())
                .build();
    }

    private static LlmResult result(ChatResponse response) {
        if (response == null) return LlmResult.text(null);
        var metadata = response.getMetadata();
        var tokens = metadata.getUsage();
        String text = response.getResult() == null ? null : response.getResult().getOutput().getText();
        return new LlmResult(text, metadata.getModel(), metadata.getId(),
                tokens == null || tokens.getPromptTokens() == null ? null : tokens.getPromptTokens().longValue(),
                tokens == null || tokens.getCompletionTokens() == null ? null : tokens.getCompletionTokens().longValue(),
                tokens == null || tokens.getTotalTokens() == null ? null : tokens.getTotalTokens().longValue());
    }

    /** Upstream error bodies can echo credentials: keep them out of exceptions and logs. */
    private static final class SafeErrorHandler implements ResponseErrorHandler {
        @Override public boolean hasError(ClientHttpResponse response) throws IOException {
            return !response.getStatusCode().is2xxSuccessful();
        }
        @Override public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
            int status = response.getStatusCode().value();
            String message = "Model service returned HTTP " + status;
            if (status == 429 || status >= 500) throw new TransientAiException(message);
            throw new NonTransientAiException(message);
        }
    }
}
