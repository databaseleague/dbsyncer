package org.dbsyncer.biz.checker.impl.tablegroup;

import org.dbsyncer.biz.BizException;
import org.dbsyncer.biz.checker.AbstractChecker;
import org.dbsyncer.biz.checker.ConnectorConfigChecker;
import org.dbsyncer.common.util.CollectionUtils;
import org.dbsyncer.common.util.JsonUtil;
import org.dbsyncer.common.util.StringUtil;
import org.dbsyncer.connector.model.Field;
import org.dbsyncer.connector.model.MetaInfo;
import org.dbsyncer.connector.model.Table;
import org.dbsyncer.connector.util.PrimaryKeyUtil;
import org.dbsyncer.manager.Manager;
import org.dbsyncer.parser.enums.ModelEnum;
import org.dbsyncer.parser.model.ConfigModel;
import org.dbsyncer.parser.model.FieldMapping;
import org.dbsyncer.parser.model.Mapping;
import org.dbsyncer.parser.model.TableGroup;
import org.dbsyncer.parser.util.PickerUtil;
import org.dbsyncer.storage.constant.ConfigConstant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * @author AE86
 * @version 1.0.0
 * @date 2020/1/8 15:17
 */
@Component
public class TableGroupChecker extends AbstractChecker {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private Manager manager;

    @Resource
    private Map<String, ConnectorConfigChecker> map;

    @Override
    public ConfigModel checkAddConfigModel(Map<String, String> params) {
        logger.info("params:{}", params);
        String mappingId = params.get("mappingId");
        String sourceTable = params.get("sourceTable");
        String targetTable = params.get("targetTable");
        String sourceTablePK = params.get("sourceTablePK");
        String targetTablePK = params.get("targetTablePK");
        Assert.hasText(mappingId, "tableGroup mappingId is empty.");
        Assert.hasText(sourceTable, "tableGroup sourceTable is empty.");
        Assert.hasText(targetTable, "tableGroup targetTable is empty.");
        Mapping mapping = manager.getMapping(mappingId);
        Assert.notNull(mapping, "mapping can not be null.");

        // 检查是否存在重复映射关系
        checkRepeatedTable(mappingId, sourceTable, targetTable);

        // 获取连接器信息
        TableGroup tableGroup = new TableGroup();
        tableGroup.setFieldMapping(new ArrayList<>());
        tableGroup.setMappingId(mappingId);
        tableGroup.setSourceTable(getTable(mapping.getSourceConnectorId(), sourceTable, sourceTablePK));
        tableGroup.setTargetTable(getTable(mapping.getTargetConnectorId(), targetTable, targetTablePK));
        tableGroup.setParams(new HashMap<>());

        // 修改基本配置
        this.modifyConfigModel(tableGroup, params);

        // 匹配相似字段映射关系
        matchSimilarFieldMapping(tableGroup);

        // 合并配置
        mergeConfig(mapping, tableGroup);

        return tableGroup;
    }

    @Override
    public ConfigModel checkEditConfigModel(Map<String, String> params) {
        logger.info("params:{}", params);
        Assert.notEmpty(params, "TableGroupChecker check params is null.");
        String id = params.get(ConfigConstant.CONFIG_MODEL_ID);
        TableGroup tableGroup = manager.getTableGroup(id);
        Assert.notNull(tableGroup, "Can not find tableGroup.");
        Mapping mapping = manager.getMapping(tableGroup.getMappingId());
        Assert.notNull(mapping, "mapping can not be null.");
        String fieldMappingJson = params.get("fieldMapping");
        Assert.hasText(fieldMappingJson, "TableGroupChecker check params fieldMapping is empty");

        // 修改基本配置
        this.modifyConfigModel(tableGroup, params);

        // 修改高级配置：过滤条件/转换配置/插件配置
        this.modifySuperConfigModel(tableGroup, params);

        // 字段映射关系
        setFieldMapping(tableGroup, fieldMappingJson);

        // 合并配置
        mergeConfig(mapping, tableGroup);

        return tableGroup;
    }

    /**
     * 刷新表字段
     *
     * @param tableGroup
     */
    public void refreshTableFields(TableGroup tableGroup) {
        Mapping mapping = manager.getMapping(tableGroup.getMappingId());
        Assert.notNull(mapping, "mapping can not be null.");

        Table sourceTable = tableGroup.getSourceTable();
        Table targetTable = tableGroup.getTargetTable();
        List<String> sourceTablePks = sourceTable.getColumn().stream().filter(c -> c.isPk()).map(c -> c.getName()).collect(Collectors.toList());
        List<String> targetTablePks = targetTable.getColumn().stream().filter(c -> c.isPk()).map(c -> c.getName()).collect(Collectors.toList());
        tableGroup.setSourceTable(getTable(mapping.getSourceConnectorId(), sourceTable.getName(), StringUtil.join(sourceTablePks, ",")));
        tableGroup.setTargetTable(getTable(mapping.getTargetConnectorId(), targetTable.getName(), StringUtil.join(targetTablePks, ",")));
    }

    public void mergeConfig(Mapping mapping, TableGroup tableGroup) {
        // 合并高级配置
        TableGroup group = PickerUtil.mergeTableGroupConfig(mapping, tableGroup);

        // 处理策略
        dealIncrementStrategy(mapping, group);

        Map<String, String> command = manager.getCommand(mapping, group);
        tableGroup.setCommand(command);

        // 获取数据源总数
        long count = ModelEnum.isFull(mapping.getModel()) && !CollectionUtils.isEmpty(command) ? manager.getCount(mapping.getSourceConnectorId(), command) : 0;
        tableGroup.getSourceTable().setCount(count);
    }

    public void dealIncrementStrategy(Mapping mapping, TableGroup tableGroup) {
        String connectorType = manager.getConnector(mapping.getSourceConnectorId()).getConfig().getConnectorType();
        String type = StringUtil.toLowerCaseFirstOne(connectorType).concat("ConfigChecker");
        ConnectorConfigChecker checker = map.get(type);
        Assert.notNull(checker, "Checker can not be null.");
        checker.dealIncrementStrategy(mapping, tableGroup);
    }

    private Table getTable(String connectorId, String tableName, String primaryKeyStr) {
        MetaInfo metaInfo = manager.getMetaInfo(connectorId, tableName);
        Assert.notNull(metaInfo, "无法获取连接器表信息:" + tableName);
        // 自定义主键
        if (StringUtil.isNotBlank(primaryKeyStr) && !CollectionUtils.isEmpty(metaInfo.getColumn())) {
            String[] pks = StringUtil.split(primaryKeyStr, ",");
            Arrays.asList(pks).stream().forEach(pk -> {
                for (Field field : metaInfo.getColumn()) {
                    if (StringUtil.equalsIgnoreCase(field.getName(), pk)) {
                        field.setPk(true);
                        break;
                    }
                }
            });
        }
        return new Table(tableName, metaInfo.getTableType(), metaInfo.getColumn(), metaInfo.getSql());
    }

    private void checkRepeatedTable(String mappingId, String sourceTable, String targetTable) {
        List<TableGroup> list = manager.getTableGroupAll(mappingId);
        if (!CollectionUtils.isEmpty(list)) {
            for (TableGroup g : list) {
                // 数据源表和目标表都存在
                if (StringUtil.equals(sourceTable, g.getSourceTable().getName())
                        && StringUtil.equals(targetTable, g.getTargetTable().getName())) {
                    final String error = String.format("映射关系已存在.%s > %s", sourceTable, targetTable);
                    logger.error(error);
                    throw new BizException(error);
                }
            }
        }
    }

    private void matchSimilarFieldMapping(TableGroup tableGroup) {
        List<Field> sCol = tableGroup.getSourceTable().getColumn();
        List<Field> tCol = tableGroup.getTargetTable().getColumn();
        if (CollectionUtils.isEmpty(sCol) || CollectionUtils.isEmpty(tCol)) {
            return;
        }

        Map<String, Field> m1 = new HashMap<>();
        Map<String, Field> m2 = new HashMap<>();
        Set<String> sourceFieldNames = new LinkedHashSet<>();
        Set<String> targetFieldNames = new LinkedHashSet<>();
        shuffleColumn(sCol, sourceFieldNames, m1);
        shuffleColumn(tCol, targetFieldNames, m2);

        // 模糊匹配相似字段
        AtomicBoolean existSourcePKFieldMapping = new AtomicBoolean();
        AtomicBoolean existTargetPKFieldMapping = new AtomicBoolean();
        sourceFieldNames.forEach(s -> {
            for (String t : targetFieldNames) {
                if (StringUtil.equalsIgnoreCase(s, t)) {
                    Field f1 = m1.get(s);
                    Field f2 = m2.get(t);
                    tableGroup.getFieldMapping().add(new FieldMapping(f1, f2));
                    if (f1.isPk()) {
                        existSourcePKFieldMapping.set(true);
                    }
                    if (f2.isPk()) {
                        existTargetPKFieldMapping.set(true);
                    }
                    break;
                }
            }
        });

        // 沒有主键映射关系，取第一个主键作为映射关系
        if (!existSourcePKFieldMapping.get() || !existTargetPKFieldMapping.get()) {
            List<String> sourceTablePrimaryKeys = PrimaryKeyUtil.findTablePrimaryKeys(tableGroup.getSourceTable());
            List<String> targetTablePrimaryKeys = PrimaryKeyUtil.findTablePrimaryKeys(tableGroup.getTargetTable());
            Assert.isTrue(!CollectionUtils.isEmpty(sourceTablePrimaryKeys) && !CollectionUtils.isEmpty(targetTablePrimaryKeys), "数据源表和目标源表必须包含主键.");
            String sPK = sourceTablePrimaryKeys.stream().findFirst().get();
            String tPK = targetTablePrimaryKeys.stream().findFirst().get();
            tableGroup.getFieldMapping().add(new FieldMapping(m1.get(sPK), m2.get(tPK)));
        }
    }

    private void shuffleColumn(List<Field> col, Set<String> key, Map<String, Field> map) {
        col.forEach(f -> {
            if (!key.contains(f.getName())) {
                key.add(f.getName());
                map.put(f.getName(), f);
            }
        });
    }

    /**
     * 解析映射关系
     *
     * @param tableGroup
     * @param json       [{"source":"id","target":"id"}]
     * @return
     */
    private void setFieldMapping(TableGroup tableGroup, String json) {
        List<Map> mappings = JsonUtil.parseList(json);
        if (null == mappings) {
            throw new BizException("映射关系不能为空");
        }

        final Map<String, Field> sMap = PickerUtil.convert2Map(tableGroup.getSourceTable().getColumn());
        final Map<String, Field> tMap = PickerUtil.convert2Map(tableGroup.getTargetTable().getColumn());
        int length = mappings.size();
        List<FieldMapping> list = new ArrayList<>();
        Map row = null;
        Field s = null;
        Field t = null;
        for (int i = 0; i < length; i++) {
            row = mappings.get(i);
            s = sMap.get(row.get("source"));
            t = tMap.get(row.get("target"));
            if (null == s && null == t) {
                continue;
            }

            if (null != t) {
                t.setPk((Boolean) row.get("pk"));
            }
            list.add(new FieldMapping(s, t));
        }
        tableGroup.setFieldMapping(list);
    }

}