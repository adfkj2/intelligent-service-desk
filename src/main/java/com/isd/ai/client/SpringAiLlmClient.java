package com.isd.ai.client;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * Spring AI 适配器。工具、系统提示词都在 ChatClient Bean 构建时挂上（见 AiConfig）。
 * 本类只做一件事：把一次调用翻译成 ChatClient 的调用，不掺业务逻辑。
 */
@Component
public class SpringAiLlmClient implements LlmClient {

    private final ChatClient chatClient;

    public SpringAiLlmClient(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String chat(String question) {
        return chatClient.prompt()
                .user(question)
                .call()
                .content();
    }
}
