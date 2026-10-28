package com.isd.ai.dto;

import java.util.List;

/**
 * 对话响应。
 *
 * source 字段是这个接口的关键设计：LLM 表示模型正常回答，FALLBACK 表示模型挂了、
 * 返回的是知识库检索结果。把"降级"明确告诉调用方，前端才能提示"AI 暂不可用"，
 * 而不是让用户误以为大模型给出了权威答案。
 *
 * @param answer         回答正文
 * @param source         LLM / FALLBACK
 * @param relatedTitles  降级时命中的知识条目标题（正常回答时为空）
 */
public record ChatAnswerResponse(String answer, String source, List<String> relatedTitles) {
}
