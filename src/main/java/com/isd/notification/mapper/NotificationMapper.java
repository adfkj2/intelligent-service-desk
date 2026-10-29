package com.isd.notification.mapper;

import java.time.LocalDateTime;
import java.util.List;

import com.isd.notification.entity.Notification;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NotificationMapper {

    @Insert("""
            INSERT INTO notification (user_id, ticket_id, type, title, content, read_flag, event_id)
            VALUES (#{userId}, #{ticketId}, #{type}, #{title}, #{content}, 0, #{eventId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Notification notification);

    @Select("""
            SELECT id, user_id, ticket_id, type, title, content, read_flag, event_id, created_at
            FROM notification
            WHERE user_id = #{userId}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<Notification> listByUser(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM notification WHERE user_id = #{userId} AND read_flag = 0")
    long countUnread(@Param("userId") Long userId);

    /** 带上 user_id 条件：别人的通知不能被标记已读（返回 0 行由 Service 转 404） */
    @Update("UPDATE notification SET read_flag = 1 WHERE id = #{id} AND user_id = #{userId}")
    int markRead(@Param("id") Long id, @Param("userId") Long userId);

    @Update("UPDATE notification SET read_flag = 1 WHERE user_id = #{userId} AND read_flag = 0")
    int markAllRead(@Param("userId") Long userId);

    /**
     * 保留策略：删除某个时间点之前、且已读的站内信。
     *
     * 只删已读：未读是用户还没看到的消息，按时间删等于把通知吃了。
     * 命中 V6 的 idx_notification_read_created(read_flag, created_at)。
     */
    @Delete("""
            DELETE FROM notification
            WHERE read_flag = 1 AND created_at < #{cutoff}
            ORDER BY created_at
            LIMIT #{limit}
            """)
    int deleteReadBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
