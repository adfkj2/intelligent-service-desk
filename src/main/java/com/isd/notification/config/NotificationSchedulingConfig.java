package com.isd.notification.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 打开 @Scheduled。
 *
 * 单独放一个配置类而不是加在启动类上：调度是通知模块带来的能力，
 * 关掉这个模块（或换成 MQ 消费）时只需删掉这个文件，启动类不用动。
 * 线程池用 Boot 默认的单线程调度器即可——投递是每 5 秒一批的小 IO，
 * 多线程只会让多实例环境下抢同一批事件的概率变大。
 */
@Configuration
@EnableScheduling
public class NotificationSchedulingConfig {
}
