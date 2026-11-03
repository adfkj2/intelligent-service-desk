package com.isd.ai.tools;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import com.isd.ai.dto.KnowledgeReference;

/**
 * 统计"一次对话里模型调了几次工具"，用于审计表 ai_chat_log.tool_calls。
 *
 * 为什么要绕一层 ThreadLocal：工具方法运行在 aiExecutor 的线程上（LLM 调用是异步的），
 * 而计数结果要在请求线程里读。所以绑定的不是"数字"，而是那个 AtomicInteger 对象的引用——
 * 线程本地只用来传引用，计数本身是线程安全的。
 *
 * 没有绑定上下文时（比如单测直接调工具）是空操作，工具不该因为"没人统计"而失败。
 */
public final class ToolCallContext {

    private static final ThreadLocal<AtomicInteger> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Map<Long, KnowledgeReference>> REFERENCES = new ThreadLocal<>();

    private ToolCallContext() {
    }

    public static void bind(AtomicInteger counter) {
        CURRENT.set(counter);
        REFERENCES.remove();
    }

    public static void bind(AtomicInteger counter, Map<Long, KnowledgeReference> references) {
        CURRENT.set(counter);
        REFERENCES.set(references);
    }

    public static void clear() {
        CURRENT.remove();
        REFERENCES.remove();
    }

    public static void increment() {
        AtomicInteger counter = CURRENT.get();
        if (counter != null) {
            counter.incrementAndGet();
        }
    }

    public static void reference(KnowledgeReference reference) {
        Map<Long, KnowledgeReference> references = REFERENCES.get();
        if (references != null && reference.id() != null && references.size() < 10) {
            references.putIfAbsent(reference.id(), reference);
        }
    }
}
