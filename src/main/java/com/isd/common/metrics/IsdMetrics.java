package com.isd.common.metrics;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * 业务指标的唯一出口。
 *
 * 口径约定（写在代码里，避免以后被"优化"）：
 * <ul>
 *   <li>按"边界 + 结果"建指标，不给同一个边界拆多个指标名——否则算降级率要跨指标拼分母；</li>
 *   <li>比率一律在查询侧算（冲突率 = conflicts / (transitions + conflicts)），不存比率；</li>
 *   <li>标签只能是有限枚举，禁止 userId / ticketId / IP，那些人进日志；</li>
 *   <li>Timer 自带 count 与直方图，不再另配 Counter 统计次数。</li>
 * </ul>
 * Micrometer 会把点号转成下划线：{@code isd.ai.chat} → {@code isd_ai_chat_seconds}。
 */
@Component
public class IsdMetrics {

    private static final String AI_CHAT = "isd.ai.chat";
    private static final String AI_TOOL_CALLS = "isd.ai.tool.calls";
    private static final String TICKET_TRANSITIONS = "isd.ticket.transitions";
    private static final String TICKET_CONFLICTS = "isd.ticket.conflicts";
    private static final String AUTH_LOGIN = "isd.auth.login";
    private static final String AUTH_SESSION_REVOKED = "isd.auth.session.revoked";
    private static final String AUTH_FORBIDDEN = "isd.auth.forbidden";

    /** AI 结果的取值：与 AgentService 的实际分支一一对应 */
    public static final String AI_SUCCESS = "success";
    public static final String AI_TIMEOUT = "timeout";
    public static final String AI_ERROR = "error";
    public static final String AI_EMPTY = "empty";
    public static final String AI_NOT_CONFIGURED = "not_configured";

    public static final String TOOL_SUCCESS = "success";
    public static final String TOOL_EMPTY = "empty";
    public static final String TOOL_ERROR = "error";

    private final MeterRegistry registry;

    public IsdMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** AI 对话端到端耗时：一次落库同时给出 QPS、P95 与降级率 */
    public void recordAiChat(String result, long startNanos) {
        Timer.builder(AI_CHAT)
                .description("AI 对话端到端耗时，result 表示为什么成功或降级")
                .tag("result", result)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(200))
                .maximumExpectedValue(Duration.ofSeconds(60))
                .register(registry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 工具调用：与对话分开埋点。
     * 原因：Spring AI 会捕获工具异常并把错误文本交给模型，端到端仍然是 success，
     * 用户看到的是"查不到"，与真的没有数据无法区分——只有这一层能看出来。
     */
    public void recordToolCall(String tool, String result, long startNanos) {
        Timer.builder(AI_TOOL_CALLS)
                .description("AI 工具调用耗时与结果")
                .tag("tool", tool)
                .tag("result", result)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(5))
                .maximumExpectedValue(Duration.ofSeconds(5))
                .register(registry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    /** 成功的状态迁移；from 为 null 表示创建（归到 NONE） */
    public void recordTransition(String action, String fromStatus, String toStatus) {
        Counter.builder(TICKET_TRANSITIONS)
                .description("工单状态迁移成功次数")
                .tag("action", action)
                .tag("from", fromStatus == null ? "NONE" : fromStatus)
                .tag("to", toStatus)
                .register(registry)
                .increment();
    }

    /** 乐观锁冲突：updated == 0 的那一次，说明有人抢先改了 */
    public void recordConflict(String action) {
        Counter.builder(TICKET_CONFLICTS)
                .description("工单乐观锁冲突次数")
                .tag("action", action)
                .register(registry)
                .increment();
    }

    public void recordLogin(String result) {
        Counter.builder(AUTH_LOGIN)
                .description("登录尝试，result=success/bad_credentials/disabled")
                .tag("result", result)
                .register(registry)
                .increment();
    }

    public void recordSessionRevoked(String reason, int count) {
        if (count <= 0) {
            return;
        }
        Counter.builder(AUTH_SESSION_REVOKED)
                .description("被强制失效的会话数，reason=password_reset/password_changed")
                .tag("reason", reason)
                .register(registry)
                .increment(count);
    }

    /** layer: url（URL 规则拦下）/ method（方法级注解）/ resource（Service 判权） */
    public void recordForbidden(String layer) {
        Counter.builder(AUTH_FORBIDDEN)
                .description("越权访问被拒绝的次数")
                .tag("layer", layer)
                .register(registry)
                .increment();
    }
}
