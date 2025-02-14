package org.dbsyncer.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 表执行器配置
 *
 * @author AE86
 * @version 1.0.0
 * @date 2023/8/28 23:50
 */
@Configuration
@ConfigurationProperties(prefix = "dbsyncer.parser.table.group")
public class TableGroupBufferConfig extends BufferActuatorConfig {
    /**
     * 最多可分配的表执行器个数
     */
    private int maxBufferActuatorSize;

    /**
     * 工作线程数
     */
    private int threadCoreSize = Runtime.getRuntime().availableProcessors() * 2;

    /**
     * 工作线任务队列
     */
    private int threadQueueCapacity = 1000;

    public int getMaxBufferActuatorSize() {
        return maxBufferActuatorSize;
    }

    public void setMaxBufferActuatorSize(int maxBufferActuatorSize) {
        this.maxBufferActuatorSize = maxBufferActuatorSize;
    }

    public int getThreadCoreSize() {
        return threadCoreSize;
    }

    public void setThreadCoreSize(int threadCoreSize) {
        this.threadCoreSize = threadCoreSize;
    }

    public int getThreadQueueCapacity() {
        return threadQueueCapacity;
    }

    public void setThreadQueueCapacity(int threadQueueCapacity) {
        this.threadQueueCapacity = threadQueueCapacity;
    }
}