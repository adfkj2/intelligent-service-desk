package com.isd.ai.entity;

import java.time.LocalDateTime;

/**
 * AI 一次问答的审计快照（表 ai_chat_log，见 V4）。
 *
 * 记录"模型看到了什么、用了几个工具、最后说了什么、花了多久"，
 * 出问题时按 trace_id 就能把应用日志和这次回答对上。
 */
public class AiChatLog {

    private Long id;
    private String traceId;
    private Long userId;
    private String question;
    private String answer;
    /** LLM / FALLBACK */
    private String source;
    private Integer kbHits;
    private Integer toolCalls;
    private Integer durationMs;
    private Boolean success;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Integer getKbHits() {
        return kbHits;
    }

    public void setKbHits(Integer kbHits) {
        this.kbHits = kbHits;
    }

    public Integer getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(Integer toolCalls) {
        this.toolCalls = toolCalls;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
