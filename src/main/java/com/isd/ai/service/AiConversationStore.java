package com.isd.ai.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.isd.ai.dto.ChatAnswerResponse;
import com.isd.ai.dto.ConversationResponse;
import com.isd.ai.entity.AiConversation;
import com.isd.ai.mapper.AiConversationMapper;
import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short transactions only. The model is always invoked outside this store. */
@Service
public class AiConversationStore {
    private final AiConversationMapper mapper;

    public AiConversationStore(AiConversationMapper mapper) {
        this.mapper = mapper;
    }

    public AiConversation owned(String id, Long userId) {
        return mapper.owned(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在或无权访问"));
    }

    public AiConversation prepare(String id, Long userId, Long ticketId, String purpose, String role) {
        if (id != null) {
            AiConversation existing = owned(id, userId);
            requireRole(existing, role);
            if (!purpose.equals(existing.purpose())
                    || (ticketId != null && !Objects.equals(ticketId, existing.ticketId()))) {
                throw new BusinessException(ErrorCode.CONFLICT, "会话上下文已变化，请开始新会话");
            }
            return existing;
        }
        AiConversation created = new AiConversation(UUID.randomUUID().toString(), userId, ticketId, purpose, role, 0);
        mapper.insert(created);
        return created;
    }

    public List<ConversationResponse.Message> messages(String id) {
        return mapper.messages(id);
    }

    public void requireRole(AiConversation conversation, String currentRole) {
        if (!conversation.accessRole().equals(currentRole)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色已变化，请开始新会话");
        }
    }

    @Transactional
    public void append(AiConversation conversation, String question, ChatAnswerResponse answer) {
        if (question == null || question.isBlank() || answer == null || answer.answer() == null || answer.answer().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "会话消息不能为空");
        }
        if (mapper.advance(conversation) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "该会话有新的回答，请刷新后重试");
        }
        mapper.append(conversation.id(), "user", limit(question, 2000), null);
        mapper.append(conversation.id(), "assistant", limit(answer.answer(), 10000), answer.answerId());
        mapper.trim(conversation.id());
    }

    private String limit(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }
}
