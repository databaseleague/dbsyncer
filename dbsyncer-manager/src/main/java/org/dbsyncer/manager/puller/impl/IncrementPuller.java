package org.dbsyncer.manager.puller.impl;

import org.apache.commons.lang.StringUtils;
import org.dbsyncer.common.event.Event;
import org.dbsyncer.common.event.RowChangedEvent;
import org.dbsyncer.common.util.CollectionUtils;
import org.dbsyncer.common.util.UUIDUtil;
import org.dbsyncer.connector.ConnectorFactory;
import org.dbsyncer.connector.config.ConnectorConfig;
import org.dbsyncer.connector.config.Field;
import org.dbsyncer.connector.config.Table;
import org.dbsyncer.connector.constant.ConnectorConstant;
import org.dbsyncer.listener.AbstractExtractor;
import org.dbsyncer.listener.Extractor;
import org.dbsyncer.listener.Listener;
import org.dbsyncer.listener.config.ListenerConfig;
import org.dbsyncer.listener.enums.ListenerTypeEnum;
import org.dbsyncer.listener.quartz.QuartzExtractor;
import org.dbsyncer.listener.quartz.ScheduledTaskJob;
import org.dbsyncer.listener.quartz.ScheduledTaskService;
import org.dbsyncer.manager.Manager;
import org.dbsyncer.manager.config.FieldPicker;
import org.dbsyncer.manager.puller.AbstractPuller;
import org.dbsyncer.parser.Parser;
import org.dbsyncer.parser.enums.PrimaryKeyMappingEnum;
import org.dbsyncer.parser.logger.LogService;
import org.dbsyncer.parser.logger.LogType;
import org.dbsyncer.parser.model.Connector;
import org.dbsyncer.parser.model.Mapping;
import org.dbsyncer.parser.model.Meta;
import org.dbsyncer.parser.model.TableGroup;
import org.dbsyncer.parser.strategy.PrimaryKeyMappingStrategy;
import org.dbsyncer.parser.util.PickerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 增量同步
 *
 * @author AE86
 * @version 1.0.0
 * @date 2020/04/26 15:28
 */
@Component
public class IncrementPuller extends AbstractPuller implements ScheduledTaskJob, InitializingBean, DisposableBean {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    private Parser parser;

    @Autowired
    private Listener listener;

    @Autowired
    private Manager manager;

    @Autowired
    private LogService logService;

    @Autowired
    private ScheduledTaskService scheduledTaskService;

    @Autowired
    private ConnectorFactory connectorFactory;

    private String key;

    private Map<String, Extractor> map = new ConcurrentHashMap<>();

    @Override
    public void asyncStart(Mapping mapping) {
        final String mappingId = mapping.getId();
        final String metaId = mapping.getMetaId();
        try {
            Connector connector = manager.getConnector(mapping.getSourceConnectorId());
            Assert.notNull(connector, "连接器不能为空.");
            List<TableGroup> list = manager.getTableGroupAll(mappingId);
            Assert.notEmpty(list, "映射关系不能为空.");
            Meta meta = manager.getMeta(metaId);
            Assert.notNull(meta, "Meta不能为空.");
            AbstractExtractor extractor = getExtractor(mapping, connector, list, meta);
            Assert.notNull(extractor, "未知的监听配置.");

            long now = Instant.now().toEpochMilli();
            meta.setBeginTime(now);
            meta.setEndTime(now);
            manager.editMeta(meta);
            map.putIfAbsent(metaId, extractor);

            // 执行任务
            logger.info("启动成功:{}", metaId);
            map.get(metaId).start();
        } catch (Exception e) {
            close(metaId);
            logService.log(LogType.TableGroupLog.INCREMENT_FAILED, e.getMessage());
            logger.error("运行异常，结束任务{}:{}", metaId, e.getMessage());
        }
    }

    @Override
    public void close(String metaId) {
        Extractor extractor = map.get(metaId);
        if (null != extractor) {
            extractor.clearAllListener();
            extractor.close();
        }
        map.remove(metaId);
        publishClosedEvent(metaId);
        logger.info("关闭成功:{}", metaId);
    }

    @Override
    public void run() {
        // 定时同步增量信息
        map.forEach((k, v) -> v.flushEvent());
    }

    @Override
    public void afterPropertiesSet() {
        key = UUIDUtil.getUUID();
        scheduledTaskService.start(key, "*/10 * * * * ?", this);
    }

    @Override
    public void destroy() {
        scheduledTaskService.stop(key);
    }

    private AbstractExtractor getExtractor(Mapping mapping, Connector connector, List<TableGroup> list, Meta meta)
            throws InstantiationException, IllegalAccessException {
        ConnectorConfig connectorConfig = connector.getConfig();
        ListenerConfig listenerConfig = mapping.getListener();

        // timing/log
        final String listenerType = listenerConfig.getListenerType();

        // 默认定时抽取
        if (ListenerTypeEnum.isTiming(listenerType)) {
            QuartzExtractor extractor = listener.getExtractor(listenerType, QuartzExtractor.class);
            List<Map<String, String>> commands = list.stream().map(t -> t.getCommand()).collect(Collectors.toList());

            setExtractorConfig(extractor, connectorConfig, listenerConfig, meta.getMap(), new QuartzListener(mapping, list));
            extractor.setConnectorFactory(connectorFactory);
            extractor.setScheduledTaskService(scheduledTaskService);
            extractor.setCommands(commands);
            return extractor;
        }

        // 基于日志抽取
        if (ListenerTypeEnum.isLog(listenerType)) {
            final String connectorType = connectorConfig.getConnectorType();
            AbstractExtractor extractor = listener.getExtractor(connectorType, AbstractExtractor.class);
            PrimaryKeyMappingStrategy strategy = PrimaryKeyMappingEnum.getPrimaryKeyMappingStrategy(connectorType);

            setExtractorConfig(extractor, connectorConfig, listenerConfig, meta.getMap(), new LogListener(mapping, list, strategy));
            return extractor;
        }
        return null;
    }

    private void setExtractorConfig(AbstractExtractor extractor, ConnectorConfig connector, ListenerConfig listener,
                                    Map<String, String> map, Event event) {
        extractor.setConnectorConfig(connector);
        extractor.setListenerConfig(listener);
        extractor.setMap(map);
        extractor.addListener(event);
    }

    abstract class AbstractListener implements Event {
        protected Mapping       mapping;
        protected String        metaId;
        protected AtomicBoolean changed = new AtomicBoolean();

        @Override
        public void flushEvent(Map<String, String> map) {
            // 如果有变更，执行更新
            if (changed.compareAndSet(true, false)) {
                Meta meta = manager.getMeta(metaId);
                if (null != meta) {
                    meta.setMap(map);
                    manager.editMeta(meta);
                }
            }
        }

        @Override
        public void errorEvent(Exception e) {
            logService.log(LogType.TableGroupLog.INCREMENT_FAILED, e.getMessage());
        }

        @Override
        public void interruptException(Exception e) {
            errorEvent(e);
            close(metaId);
        }
    }

    /**
     * </p>定时模式
     * <ol>
     * <li>根据过滤条件筛选</li>
     * </ol>
     * </p>同步关系：
     * </p>数据源表 >> 目标源表
     * <ul>
     * <li>A >> B</li>
     * <li>A >> C</li>
     * <li>E >> F</li>
     * </ul>
     * </p>PS：
     * <ol>
     * <li>依次执行同步关系A >> B 然后 A >> C ...</li>
     * </ol>
     */
    final class QuartzListener extends AbstractListener {

        private List<FieldPicker> tablePicker;

        public QuartzListener(Mapping mapping, List<TableGroup> list) {
            this.mapping = mapping;
            this.metaId = mapping.getMetaId();
            this.tablePicker = new LinkedList<>();
            list.forEach(t -> tablePicker.add(new FieldPicker(PickerUtil.mergeTableGroupConfig(mapping, t))));
        }

        @Override
        public void changedQuartzEvent(RowChangedEvent rowChangedEvent) {
            final FieldPicker picker = tablePicker.get(rowChangedEvent.getTableGroupIndex());
            logger.info("监听数据=> tableName:{}, event:{}, before:{}, after:{}", picker.getTableGroup().getSourceTable().getName(),
                    rowChangedEvent.getEvent(),
                    rowChangedEvent.getBefore(),
                    rowChangedEvent.getAfter());

            // 处理过程有异常向上抛
            parser.execute(mapping, picker.getTableGroup(), rowChangedEvent, null);

            // 标记有变更记录
            changed.compareAndSet(false, true);
        }
    }

    /**
     * </p>日志模式
     * <ol>
     * <li>监听表增量数据</li>
     * <li>根据过滤条件筛选</li>
     * </ol>
     * </p>同步关系：
     * </p>数据源表 >> 目标源表
     * <ul>
     * <li>A >> B</li>
     * <li>A >> C</li>
     * <li>E >> F</li>
     * </ul>
     * </p>PS：
     * <ol>
     * <li>为减少开销而选择复用监听器实例, 启动时只需创建一个数据源连接器.</li>
     * <li>关系A >> B和A >> C会复用A监听的数据, A监听到增量数据，会发送给B和C.</li>
     * <li>该模式下，会监听表所有字段.</li>
     * </ol>
     */
    final class LogListener extends AbstractListener {

        private Map<String, List<FieldPicker>> tablePicker;

        private PrimaryKeyMappingStrategy strategy;

        public LogListener(Mapping mapping, List<TableGroup> list, PrimaryKeyMappingStrategy strategy) {
            this.mapping = mapping;
            this.metaId = mapping.getMetaId();
            this.tablePicker = new LinkedHashMap<>();
            this.strategy = strategy;
            list.forEach(t -> {
                final Table table = t.getSourceTable();
                final String tableName = table.getName();
                List<Field> pkList = t.getTargetTable().getColumn().stream().filter(field -> field.isPk()).collect(Collectors.toList());
                tablePicker.putIfAbsent(tableName, new ArrayList<>());
                TableGroup group = PickerUtil.mergeTableGroupConfig(mapping, t);
                tablePicker.get(tableName).add(new FieldPicker(group, pkList, group.getFilter(), table.getColumn(), group.getFieldMapping()));
            });
        }

        @Override
        public void changedLogEvent(RowChangedEvent rowChangedEvent) {
            logger.info("监听数据=> tableName:{}, event:{}, before:{}, after:{}, rowId:{}", rowChangedEvent.getTableName(),
                    rowChangedEvent.getEvent(),
                    rowChangedEvent.getBefore(), rowChangedEvent.getAfter(), rowChangedEvent.getRowId());

            // 处理过程有异常向上抛
            List<FieldPicker> pickers = tablePicker.get(rowChangedEvent.getTableName());
            if (!CollectionUtils.isEmpty(pickers)) {
                pickers.parallelStream().forEach(picker -> {
                    final Map<String, Object> before = picker.getColumns(rowChangedEvent.getBeforeData());
                    final Map<String, Object> after = picker.getColumns(rowChangedEvent.getAfterData());
                    if (picker.filter(StringUtils.equals(ConnectorConstant.OPERTION_DELETE, rowChangedEvent.getEvent()) ? before : after)) {
                        rowChangedEvent.setBefore(before);
                        rowChangedEvent.setAfter(after);
                        rowChangedEvent.setPk(picker.getPk());
                        parser.execute(mapping, picker.getTableGroup(), rowChangedEvent, strategy);
                    }
                });
            }

            // 标记有变更记录
            changed.compareAndSet(false, true);
        }

    }

}