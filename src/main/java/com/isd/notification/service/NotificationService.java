package com.isd.notification.service;

import java.util.List;

import com.isd.auth.domain.UserRole;
import com.isd.auth.security.LoginUser;
import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;
import com.isd.notification.dto.NotificationResponse;
import com.isd.notification.mapper.NotificationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 站内信查询与已读状态。
 *
 * 越权防护的口径：身份一律由 Controller 从 SecurityContext 取。读取或标记时同时检查
 * 通知归属和当前工单访问范围，防止技术支持降为员工后通过历史通知读取别人工单标题。
 * 影响行数为 0 统一转 404 —— 和工单一致，不告诉调用方"这条通知真实存在只是不属于你"。
 */
@Service
public class NotificationService {

    private static final int MAX_LIMIT = 100;

    private final NotificationMapper notificationMapper;

    public NotificationService(NotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    /**
     * 最近的通知。
     * 不做 offset 分页：站内信是"看最新几条"的场景，深翻页既没有用户价值，
     * 又会让 LIMIT offset, n 随页数增长而变慢。要历史记录应该按时间范围查。
     */
    @Transactional(readOnly = true)
    public List<NotificationResponse> list(LoginUser viewer, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return notificationMapper.listByUser(viewer.getId(), safeLimit, isStaff(viewer)).stream()
                .map(NotificationResponse::from)
                .toList();
    }

    /** 走 idx_user_read 的等值前缀，不需要扫全表 */
    @Transactional(readOnly = true)
    public long countUnread(LoginUser viewer) {
        return notificationMapper.countUnread(viewer.getId(), isStaff(viewer));
    }

    @Transactional
    public void markRead(Long id, LoginUser viewer) {
        if (notificationMapper.markRead(id, viewer.getId(), isStaff(viewer)) == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "通知不存在: id=" + id);
        }
    }

    @Transactional
    public int markAllRead(LoginUser viewer) {
        return notificationMapper.markAllRead(viewer.getId(), isStaff(viewer));
    }

    private boolean isStaff(LoginUser viewer) {
        return viewer.getRole() != UserRole.EMPLOYEE;
    }
}
