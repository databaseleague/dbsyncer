package org.dbsyncer.common.event;

import java.util.Map;

/**
 * 数据变更监听器
 *
 * @version 1.0.0
 * @Author AE86
 * @Date 2020-05-11 22:50
 */
public interface Watcher {

    /**
     * 数据变更事件
     *
     * @param event
     */
    void changeEvent(ChangedEvent event);

    /**
     * DDL变更事件
     *
     * @param event
     */
    default void changeEvent(DDLChangedEvent event) {
    }

    /**
     * 持久化增量点事件
     *
     * @param snapshot
     */
    void flushEvent(Map<String, String> snapshot);

    /**
     * 异常事件
     *
     * @param e
     */
    void errorEvent(Exception e);

    /**
     * 获取Meta更新时间
     *
     * @return
     */
    long getMetaUpdateTime();
}