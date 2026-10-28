package com.isd.ai.client;

/**
 * LLM 调用的抽象（端口）。
 *
 * 为什么多抽一层接口，而不是直接在 Service 里用 ChatClient：
 * 1) 单测可以替换成"立刻返回文本 / 抛异常 / 睡 2 秒"的桩，不依赖网络和 Key；
 * 2) 换供应商（DeepSeek → Qwen）只改适配器，业务代码不动；
 * 3) 超时、降级这些业务策略留在 Service，适配器只负责"发一次请求"。
 */
public interface LlmClient {

    /**
     * 发送一次对话请求，返回模型文本回复。
     *
     * @param question 用户问题（工具调用由 ChatClient 内部完成，这里不感知）
     */
    String chat(String question);
}
