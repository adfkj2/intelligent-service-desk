package com.isd.notification.mapper;

import java.time.LocalDateTime;
import java.util.List;

import com.isd.notification.entity.OutboxEvent;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 发件箱数据访问。
 *
 * 取待投递事件用 `status='PENDING' ORDER BY id LIMIT n`，正好命中 idx_status_created 的前缀，
 * 不会随表增长变成全表扫描。多实例同时跑调度器时，同一批事件可能被两个实例取到——
 * 幂等由 notification 的 (event_id, user_id) 唯一索引兜底，不需要分布式锁。
 */
@Mapper
public interface OutboxEventMapper {

    @Insert("""
            INSERT INTO outbox_event (event_type, aggregate_type, aggregate_id, payload, status)
            VALUES (#{eventType}, #{aggregateType}, #{aggregateId}, #{payload}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OutboxEvent event);

    @Select("""
            SELECT id, event_type, aggregate_type, aggregate_id, payload, status,
                   attempts, last_error, created_at, sent_at
            FROM outbox_event
            WHERE status = 'PENDING'
            ORDER BY id
            LIMIT #{limit}
            """)
    List<OutboxEvent> findPending(@Param("limit") int limit);

    @Update("""
            UPDATE outbox_event
            SET status = 'SENT', sent_at = NOW(), last_error = NULL
            WHERE id = #{id}
            """)
    int markSent(@Param("id") Long id);

    /**
     * 投递失败：次数 +1，达到上限转 FAILED（避免一条坏事件被无限重试拖住队列）。
     * 用 CASE 而不是先查后写，省一次往返，也避免并发下次数丢更新。
     *
     * 注意 SET 子句的顺序：MySQL 按从左到右依次求值，后面的表达式看到的是**已更新**的值。
     * 所以 status 必须写在 attempts = attempts + 1 之前，否则 CASE 里的 attempts 已经是新值，
     * 会变成 attempts + 2，导致提前一次（第 4 次而不是第 5 次）就转 FAILED。
     */
    @Update("""
            UPDATE outbox_event
            SET status = CASE WHEN attempts + 1 >= #{maxAttempts} THEN 'FAILED' ELSE 'PENDING' END,
                attempts = attempts + 1,
                last_error = #{lastError}
            WHERE id = #{id}
            """)
    int markFailed(@Param("id") Long id,
                   @Param("lastError") String lastError,
                   @Param("maxAttempts") int maxAttempts);

    @Select("SELECT COUNT(*) FROM outbox_event WHERE status = 'PENDING'")
    long countPending();

    /**
     * 保留策略：清理已投递的历史事件。
     *
     * 只删 SENT——PENDING 还要投递，FAILED 是留给人工排查的现场（谁把它的错误原因删了，
     * 下次同类问题就查不到了）。条件命中 idx_status_created(status, created_at)，不会扫全表。
     */
    @Delete("""
            DELETE FROM outbox_event
            WHERE status = 'SENT' AND created_at < #{cutoff}
            ORDER BY created_at
            LIMIT #{limit}
            """)
    int deleteSentBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
