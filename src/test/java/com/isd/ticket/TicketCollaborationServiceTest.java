package com.isd.ticket;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.isd.auth.domain.UserRole;
import com.isd.auth.entity.User;
import com.isd.auth.mapper.UserMapper;
import com.isd.auth.security.LoginUser;
import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;
import com.isd.common.metrics.IsdMetrics;
import com.isd.common.time.BusinessTime;
import com.isd.notification.service.NotificationPublisher;
import com.isd.support.TestUsers;
import com.isd.ticket.dto.*;
import com.isd.ticket.entity.*;
import com.isd.ticket.mapper.*;
import com.isd.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TicketCollaborationServiceTest {
    private static final LoginUser EMPLOYEE = TestUsers.employee(1001L);
    private static final LoginUser OTHER = TestUsers.employee(1002L);
    private static final LoginUser SUPPORT = TestUsers.support(2001L);
    private static final LoginUser ADMIN = TestUsers.admin(3001L);
    @Mock TicketMapper tickets;
    @Mock TicketOperationLogMapper logs;
    @Mock UserMapper users;
    @Mock TicketCollaborationMapper collaboration;
    @Mock NotificationPublisher notifications;
    @Mock IsdMetrics metrics;
    TicketService service;

    @BeforeEach
    void setup() { service = new TicketService(tickets,logs,users,notifications,metrics,collaboration); }

    private Ticket ticket(String status) {
        Ticket value = new Ticket();
        value.setId(1L); value.setCreatorId(1001L); value.setAssigneeId(2001L);
        value.setStatus(status); value.setVersion(3); value.setPriority("HIGH");
        value.setPausedSeconds(0L); value.setReopenCount(0);
        value.setCreatedAt(BusinessTime.now().minusDays(1));
        value.setResolutionDueAt(BusinessTime.now().plusHours(4));
        return value;
    }

    private void visible(Ticket ticket) { when(tickets.findById(1L)).thenReturn(Optional.of(ticket)); }
    private void locked(Ticket ticket) { when(tickets.findByIdForUpdate(1L)).thenReturn(Optional.of(ticket)); }


    @Test
    void createdDeadlinesUseShanghaiBusinessTimeWhenHostDefaultIsUtc() {
        java.util.TimeZone original=java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
            java.util.concurrent.atomic.AtomicReference<Ticket> inserted=new java.util.concurrent.atomic.AtomicReference<>();
            doAnswer(invocation -> {
                Ticket value=invocation.getArgument(0); value.setId(1L); inserted.set(value); return 1;
            }).when(tickets).insert(any());
            when(tickets.findById(1L)).thenAnswer(invocation -> Optional.of(inserted.get()));
            var result=service.create(new CreateTicketRequest("VPN故障","客户端报错","IT_FAULT","HIGH"),EMPLOYEE).ticket();
            long responseMinutes=java.time.Duration.between(BusinessTime.now(),result.responseDueAt()).toMinutes();
            long resolutionMinutes=java.time.Duration.between(BusinessTime.now(),result.resolutionDueAt()).toMinutes();
            assertThat(responseMinutes).isBetween(239L,240L);
            assertThat(resolutionMinutes).isBetween(1439L,1440L);
            assertThat(result.ticketNo()).startsWith("T"+BusinessTime.now().toLocalDate()
                    .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    @Test
    void internalRepliesAndAuditAreHiddenFromEmployees() {
        Ticket ticket = ticket("IN_PROGRESS"); visible(ticket);
        var note = new TicketOperationLog(); note.setAction("INTERNAL_NOTE");
        var reply = new TicketOperationLog(); reply.setAction("REPLY");
        when(logs.findByTicketId(1L)).thenReturn(List.of(note,reply));
        when(collaboration.replies(1L,true)).thenReturn(List.of());
        assertThat(service.replies(1L,EMPLOYEE)).isEmpty();
        assertThat(service.operations(1L,EMPLOYEE)).containsExactly(reply);
        verify(collaboration).replies(1L,true);
    }

    @Test
    void otherEmployeeCannotProbeReplies() {
        visible(ticket("IN_PROGRESS"));
        assertThatThrownBy(() -> service.replies(1L,OTHER)).isInstanceOfSatisfying(
                BusinessException.class,ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(collaboration);
    }

    @Test
    void employeesCannotCreateInternalNotes() {
        Ticket ticket = ticket("IN_PROGRESS"); visible(ticket); locked(ticket);
        assertThatThrownBy(() -> service.addReply(1L,new AddReplyRequest("内部说明","INTERNAL"),EMPLOYEE))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(collaboration,never()).insertReply(anyMap());
    }

    @Test
    void publicStaffReplyRecordsFirstResponseButInternalNoteDoesNot() {
        Ticket ticket = ticket("IN_PROGRESS"); visible(ticket); locked(ticket);
        doAnswer(invocation -> { Map<String,Object> row=invocation.getArgument(0); row.put("id",7L); return 1; })
                .when(collaboration).insertReply(anyMap());
        service.addReply(1L,new AddReplyRequest("请尝试重启客户端","PUBLIC"),SUPPORT);
        verify(tickets).recordFirstResponse(1L);
        service.addReply(1L,new AddReplyRequest("待核对网关配置","INTERNAL"),SUPPORT);
        verify(tickets,times(1)).recordFirstResponse(1L);
        verify(notifications).publishTicketReply(ticket,SUPPORT,true);
        verify(metrics,never()).recordTransition(anyString(),any(),any());
    }

    @Test
    void closedTicketRejectsRepliesAfterTakingCurrentRowLock() {
        Ticket snapshot=ticket("IN_PROGRESS"),current=ticket("CLOSED");
        visible(snapshot); locked(current);
        assertThatThrownBy(() -> service.addReply(1L,new AddReplyRequest("补充","PUBLIC"),EMPLOYEE))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(collaboration,never()).insertReply(anyMap());
    }

    @Test
    void ownerCanReopenResolvedTicketRetainingAssigneeAndAudit() {
        Ticket ticket=ticket("RESOLVED");
        ticket.setResolvedAt(BusinessTime.now().minusMinutes(1));
        visible(ticket); when(tickets.transition(any(),eq("RESOLVED"),eq(3))).thenReturn(1);
        var result=service.changeStatus(1L,"REOPEN","仍无法连接",EMPLOYEE);
        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        assertThat(result.assigneeId()).isEqualTo(2001L);
        assertThat(result.reopenCount()).isEqualTo(1);
        assertThat(result.resolvedAt()).isNull();
        assertThat(result.resolutionDueAt()).isAfter(BusinessTime.now().plusHours(23));
        verify(notifications).publishTicketStatusChanged(ticket,"RESOLVED",EMPLOYEE);
    }

    @Test
    void supplyingInformationResumesDeadlineAndExcludesWaitingTime() {
        Ticket ticket=ticket("WAITING_CUSTOMER"); ticket.setWaitingSince(BusinessTime.now().minusHours(2));
        LocalDateTime previous=ticket.getResolutionDueAt();
        visible(ticket); when(tickets.transition(any(),eq("WAITING_CUSTOMER"),eq(3))).thenReturn(1);
        var result=service.changeStatus(1L,"PROVIDE_INFO","已补充截图",EMPLOYEE);
        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        assertThat(result.waitingSince()).isNull();
        assertThat(result.pausedSeconds()).isBetween(7200L,7202L);
        assertThat(result.resolutionDueAt()).isAfterOrEqualTo(previous.plusHours(2));
    }

    @Test
    void concurrentStateChangeCannotPublishSuccess() {
        Ticket ticket=ticket("IN_PROGRESS"); visible(ticket);
        when(tickets.transition(any(),anyString(),anyInt())).thenReturn(0);
        assertThatThrownBy(() -> service.changeStatus(1L,"REQUEST_INFO","请提供错误提示",SUPPORT))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verifyNoInteractions(notifications);
        verify(logs,never()).insert(any());
    }

    @Test
    void transferRequiresCurrentAssigneeOrAdministrator() {
        visible(ticket("IN_PROGRESS"));
        assertThatThrownBy(() -> service.transfer(1L,2002L,"网络问题转交",EMPLOYEE))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(tickets,never()).transition(any(),anyString(),anyInt());
    }

    @Test
    void internalAttachmentCannotBeDownloadedByOwner() {
        visible(ticket("IN_PROGRESS"));
        TicketAttachment attachment=new TicketAttachment(); attachment.setVisibility("INTERNAL");
        when(collaboration.attachment(1L,2L)).thenReturn(Optional.of(attachment));
        assertThatThrownBy(() -> service.downloadAttachment(1L,2L,EMPLOYEE))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void attachmentUploadEnforcesLimitAndRejectsInternalEmployeeUpload() {
        Ticket ticket=ticket("IN_PROGRESS"); visible(ticket); locked(ticket);
        var file=new MockMultipartFile("file","screen.png","image/png",new byte[]{1,2});
        assertThatThrownBy(() -> service.uploadAttachment(1L,file,"INTERNAL",EMPLOYEE))
                .isInstanceOf(BusinessException.class);
        when(collaboration.attachmentIdsForUpdate(1L)).thenReturn(java.util.stream.LongStream.range(1,21).boxed().toList());
        assertThatThrownBy(() -> service.uploadAttachment(1L,file,"PUBLIC",EMPLOYEE))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(collaboration,never()).insertAttachment(any());
    }

    @Test
    void advancedFiltersNeverOverrideEmployeeScope() {
        service.list(null,null,null,1,1000,EMPLOYEE,2002L,"HIGH",null,null,"OVERDUE");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String,Object>> filter=ArgumentCaptor.forClass(Map.class);
        verify(tickets).countAdvanced(filter.capture());
        assertThat(filter.getValue()).containsEntry("creatorId",1001L).containsEntry("viewerId",1001L)
                .containsEntry("assigneeId",2002L).containsEntry("queue","OVERDUE");
    }

    @Test
    void slaRemindersAreDeduplicatedAndIgnorePausedTicketsAfterLock() {
        Ticket current=ticket("IN_PROGRESS");
        current.setFirstResponseAt(BusinessTime.now());
        current.setResolutionDueAt(BusinessTime.now().plusMinutes(20));
        when(users.findEnabledAdmins()).thenReturn(List.of());
        when(tickets.findSlaCandidates()).thenReturn(List.of(current));
        locked(current);
        when(collaboration.claimReminder(eq(1L),anyString())).thenReturn(1,0);
        service.sendSlaReminders(); service.sendSlaReminders();
        verify(notifications,times(1)).publishTicketSla(eq(current),eq("RESOLUTION"),eq(false),anyList());
        current.setStatus("WAITING_CUSTOMER");
        service.sendSlaReminders();
        verify(collaboration,times(2)).claimReminder(eq(1L),anyString());
    }


    @Test
    void transferChangesAssigneeAndRejectsDisabledTargets() {
        Ticket ticket=ticket("IN_PROGRESS"); visible(ticket);
        User target=new User(); target.setId(2002L); target.setRole(UserRole.SUPPORT); target.setStatus(0);
        when(users.findById(2002L)).thenReturn(Optional.of(target));
        assertThatThrownBy(() -> service.transfer(1L,2002L,"网络问题转交",SUPPORT))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_PARAM));
        target.setStatus(1);
        when(tickets.transition(any(),eq("IN_PROGRESS"),eq(3))).thenReturn(1);
        assertThat(service.transfer(1L,2002L,"网络问题转交",SUPPORT).assigneeId()).isEqualTo(2002L);
        verify(notifications).publishTicketAssigned(ticket,SUPPORT);
    }

    @Test
    void supportCanSaveOnlyValidDistinctSkillCategories() {
        assertThat(service.saveSupportProfile(List.of("IT_FAULT","IT_FAULT","DEVICE"),SUPPORT))
                .containsExactly("DEVICE","IT_FAULT");
        verify(collaboration).saveSkills(2001L,"DEVICE,IT_FAULT");
        assertThatThrownBy(() -> service.saveSupportProfile(List.of("NETWORK"),SUPPORT))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.saveSupportProfile(List.of("IT_FAULT"),EMPLOYEE))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void attachmentUploadStripsPathsAndNeverTrustsExecutableMimeType() {
        Ticket ticket=ticket("IN_PROGRESS"); visible(ticket); locked(ticket);
        when(collaboration.attachmentIdsForUpdate(1L)).thenReturn(List.of());
        doAnswer(invocation -> { TicketAttachment item=invocation.getArgument(0); item.setId(4L); return 1; })
                .when(collaboration).insertAttachment(any());
        when(collaboration.attachments(1L,false)).thenAnswer(invocation -> {
            TicketAttachment item=new TicketAttachment();
            item.setId(4L); item.setTicketId(1L); item.setSize(3L); item.setFileName("safe.html");
            item.setVisibility("PUBLIC"); item.setContentType("application/octet-stream");
            return List.of(item);
        });
        var result=service.uploadAttachment(1L,new MockMultipartFile("file","../safe.html","text/html",new byte[]{1,2,3}),
                "PUBLIC",EMPLOYEE);
        ArgumentCaptor<TicketAttachment> captured=ArgumentCaptor.forClass(TicketAttachment.class);
        verify(collaboration).insertAttachment(captured.capture());
        assertThat(captured.getValue().getFileName()).isEqualTo("safe.html");
        assertThat(captured.getValue().getContentType()).isEqualTo("application/octet-stream");
        assertThat(result.id()).isEqualTo(4L);
    }

    @Test
    void oversizedOrEmptyAttachmentsAreRejectedBeforeStorage() {
        Ticket ticket=ticket("IN_PROGRESS"); visible(ticket); locked(ticket);
        assertThatThrownBy(() -> service.uploadAttachment(1L,
                new MockMultipartFile("file",new byte[0]),"PUBLIC",EMPLOYEE)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.uploadAttachment(1L,
                new MockMultipartFile("file",new byte[5*1024*1024+1]),"PUBLIC",EMPLOYEE)).isInstanceOf(BusinessException.class);
        verify(collaboration,never()).insertAttachment(any());
    }

    @Test
    void overviewReturnsOnlyScopedMetricsToEmployee() {
        when(tickets.overviewMetrics(1001L)).thenReturn(Map.of("averageResponseSeconds",60d,
                "averageResolutionSeconds",3600d,"averageFillDurationSeconds",45d,"reopenRate",0.25d));
        var result=service.overview(EMPLOYEE);
        assertThat(result.averageResponseSeconds()).isEqualTo(60d);
        assertThat(result.averageResolutionSeconds()).isEqualTo(3600d);
        assertThat(result.reopenRate()).isEqualTo(0.25);
        assertThat(result.supportWorkloads()).isEmpty();
        verifyNoInteractions(users);
    }

    @Test
    void requestInfoRequiresExplanationAndRecordsResponseOnlyWhenAuthorized() {
        Ticket ticket=ticket("IN_PROGRESS"); visible(ticket);
        assertThatThrownBy(() -> service.changeStatus(1L,"REQUEST_INFO"," ",SUPPORT))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_PARAM));
        when(tickets.transition(any(),eq("IN_PROGRESS"),eq(3))).thenReturn(1);
        var result=service.changeStatus(1L,"REQUEST_INFO","请补充错误码",SUPPORT);
        assertThat(result.status()).isEqualTo("WAITING_CUSTOMER");
        assertThat(result.firstResponseAt()).isNotNull();
        assertThat(result.waitingSince()).isNotNull();
    }

    @Test
    void recommendationPrefersMatchingSkillsThenLowerWorkload() {
        Ticket ticket=ticket("OPEN"); ticket.setCategory("IT_FAULT"); visible(ticket);
        User first=new User(); first.setId(2001L); first.setUsername("network"); first.setRole(UserRole.SUPPORT); first.setStatus(1);
        User second=new User(); second.setId(2002L); second.setUsername("device"); second.setRole(UserRole.SUPPORT); second.setStatus(1);
        when(users.findAll()).thenReturn(List.of(first,second));
        when(collaboration.skills(2001L)).thenReturn("IT_FAULT");
        when(collaboration.skills(2002L)).thenReturn("DEVICE");
        when(collaboration.activeTickets(2001L)).thenReturn(4L);
        when(collaboration.activeTickets(2002L)).thenReturn(0L);
        assertThat(service.assignmentSuggestions(1L,ADMIN)).extracting(AssignmentSuggestion::userId)
                .containsExactly(2001L,2002L);
    }
}
