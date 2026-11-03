package com.isd.ai.service;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isd.ai.dto.ChatAnswerResponse;
import com.isd.ai.dto.DraftRequest;
import com.isd.ai.entity.AiConversation;
import com.isd.ai.mapper.AiFeedbackMapper;
import com.isd.auth.security.CurrentUserProvider;
import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;
import com.isd.support.TestUsers;
import com.isd.ticket.service.TicketService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSuggestionAuditTest {
    @Test
    void rejectedConcurrentDraftIsNotCountedAsReturnedSuggestion() {
        Fixture fixture = fixture(42L);
        doThrow(new BusinessException(ErrorCode.CONFLICT, "会话已更新"))
                .when(fixture.store).append(any(), anyString(), any());
        assertThatThrownBy(() -> fixture.service.draft(new DraftRequest("VPN连不上", null)))
                .isInstanceOf(BusinessException.class);
        verify(fixture.mapper, never()).markSuggestion(any(), any(), anyString());
    }

    @Test
    void suggestionAuditFailureDoesNotDiscardUsefulReturnedDraft() {
        Fixture fixture = fixture(42L);
        when(fixture.mapper.markSuggestion(42L, 9821L, "DRAFT")).thenThrow(new IllegalStateException("audit unavailable"));
        var result = fixture.service.draft(new DraftRequest("VPN连不上", null));
        assertThat(result.description()).isEqualTo("VPN连不上");
        assertThat(result.source()).isEqualTo("FALLBACK");
        verify(fixture.mapper).markSuggestion(42L, 9821L, "DRAFT");
    }

    @Test
    void missingAuditIdDoesNotInventSuggestionIdentity() {
        Fixture fixture = fixture(null);
        assertThat(fixture.service.draft(new DraftRequest("VPN连不上", null)).answerId()).isNull();
        verify(fixture.mapper, never()).markSuggestion(any(), any(), anyString());
    }

    private Fixture fixture(Long answerId) {
        AgentService agent = mock(AgentService.class);
        AiConversationStore store = mock(AiConversationStore.class);
        AiFeedbackMapper mapper = mock(AiFeedbackMapper.class);
        CurrentUserProvider user = mock(CurrentUserProvider.class);
        when(user.require()).thenReturn(TestUsers.employee(9821L));
        when(user.requireId()).thenReturn(9821L);
        AiConversation conversation = new AiConversation("local-test", 9821L, null, "DRAFT", "EMPLOYEE", 0);
        when(store.prepare(null, 9821L, null, "DRAFT", "EMPLOYEE")).thenReturn(conversation);
        when(store.messages("local-test")).thenReturn(List.of());
        when(agent.chatWithContext(anyString(), anyString(), anyString()))
                .thenReturn(new ChatAnswerResponse("模型暂不可用", "FALLBACK", List.of(), null, answerId, List.of()));
        return new Fixture(new AiWorkspaceService(agent, store, mapper, mock(TicketService.class), user, new ObjectMapper()), store, mapper);
    }

    private record Fixture(AiWorkspaceService service, AiConversationStore store, AiFeedbackMapper mapper) { }
}
