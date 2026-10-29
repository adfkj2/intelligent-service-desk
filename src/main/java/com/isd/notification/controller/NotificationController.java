package com.isd.notification.controller;

import java.util.List;

import com.isd.auth.security.CurrentUserProvider;
import com.isd.common.result.ApiResponse;
import com.isd.notification.dto.NotificationResponse;
import com.isd.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内信接口。
 * 所有接口都只操作"当前登录用户"的数据：身份来自 SecurityContext，路径里没有 userId。
 */
@RestController
@RequestMapping("/api/notifications")
@Tag(name = "站内信", description = "列表 / 未读数 / 单条已读 / 全部已读")
public class NotificationController {

    private final NotificationService notificationService;
    private final CurrentUserProvider currentUserProvider;

    public NotificationController(NotificationService notificationService,
                                  CurrentUserProvider currentUserProvider) {
        this.notificationService = notificationService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public ApiResponse<List<NotificationResponse>> list(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(notificationService.list(currentUserProvider.requireId(), limit));
    }

    /** 路径是单段，和 /{id}/read 的两段模板不会冲突 */
    @GetMapping("/unread-count")
    public ApiResponse<UnreadCount> unreadCount() {
        return ApiResponse.ok(new UnreadCount(notificationService.countUnread(currentUserProvider.requireId())));
    }

    /** 不存在或不属于当前用户，Service 都抛 404（不区分两者，避免探测） */
    @PatchMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(id, currentUserProvider.requireId());
        return ApiResponse.ok();
    }

    @PostMapping("/read-all")
    public ApiResponse<ReadAllResult> markAllRead() {
        return ApiResponse.ok(new ReadAllResult(notificationService.markAllRead(currentUserProvider.requireId())));
    }

    public record UnreadCount(long count) {
    }

    /** 返回实际影响行数：前端可据此决定要不要刷新列表 */
    public record ReadAllResult(int updated) {
    }
}
