package org.dbsyncer.connector.database;

import org.dbsyncer.common.model.Result;
import org.dbsyncer.common.util.CollectionUtils;
import org.dbsyncer.common.util.StringUtil;
import org.dbsyncer.connector.AbstractConnector;
import org.dbsyncer.connector.Connector;
import org.dbsyncer.connector.ConnectorException;
import org.dbsyncer.connector.ConnectorMapper;
import org.dbsyncer.connector.config.*;
import org.dbsyncer.connector.constant.ConnectorConstant;
import org.dbsyncer.connector.enums.OperationEnum;
import org.dbsyncer.connector.enums.SetterEnum;
import org.dbsyncer.connector.enums.SqlBuilderEnum;
import org.dbsyncer.connector.enums.TableTypeEnum;
import org.dbsyncer.connector.util.DatabaseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.support.rowset.ResultSetWrappingSqlRowSet;
import org.springframework.jdbc.support.rowset.SqlRowSet;
import org.springframework.jdbc.support.rowset.SqlRowSetMetaData;
import org.springframework.util.Assert;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public abstract class AbstractDatabaseConnector extends AbstractConnector implements Connector<DatabaseConnectorMapper, DatabaseConfig>, Database {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    protected abstract String getTableSql(DatabaseConfig config);

    @Override
    public ConnectorMapper connect(DatabaseConfig config) {
        try {
            return new DatabaseConnectorMapper(config);
        } catch (Exception e) {
            logger.error("Failed to connect:{}, message:{}", config.getUrl(), e.getMessage());
        }
        throw new ConnectorException(String.format("Failed to connect:%s", config.getUrl()));
    }

    @Override
    public void disconnect(DatabaseConnectorMapper connectorMapper) {
        connectorMapper.close();
    }

    @Override
    public boolean isAlive(DatabaseConnectorMapper connectorMapper) {
        Integer count = connectorMapper.execute(databaseTemplate -> databaseTemplate.queryForObject(getValidationQuery(), Integer.class));
        return null != count && count > 0;
    }

    @Override
    public String getConnectorMapperCacheKey(DatabaseConfig config) {
        return String.format("%s-%s", config.getUrl(), config.getUsername());
    }

    @Override
    public List<Table> getTable(DatabaseConnectorMapper connectorMapper) {
        String sql = getTableSql(connectorMapper.getConfig());
        List<String> tableNames = connectorMapper.execute(databaseTemplate -> databaseTemplate.queryForList(sql, String.class));
        if (!CollectionUtils.isEmpty(tableNames)) {
            return tableNames.stream().map(name -> new Table(name)).collect(Collectors.toList());
        }
        return Collections.EMPTY_LIST;
    }

    @Override
    public MetaInfo getMetaInfo(DatabaseConnectorMapper connectorMapper, String tableName) {
        String quotation = buildSqlWithQuotation();
        StringBuilder queryMetaSql = new StringBuilder("SELECT * FROM ").append(quotation).append(tableName).append(quotation).append(" WHERE 1 != 1");
        return connectorMapper.execute(databaseTemplate -> getMetaInfo(databaseTemplate, queryMetaSql.toString(), tableName));
    }

    @Override
    public long getCount(DatabaseConnectorMapper connectorMapper, Map<String, String> command) {
        // 1、获取select SQL
        String queryCountSql = command.get(ConnectorConstant.OPERTION_QUERY_COUNT);
        Assert.hasText(queryCountSql, "查询总数语句不能为空.");

        // 2、返回结果集
        return connectorMapper.execute(databaseTemplate -> databaseTemplate.queryForObject(queryCountSql, Long.class));
    }

    @Override
    public Result reader(DatabaseConnectorMapper connectorMapper, ReaderConfig config) {
        // 1、获取select SQL
        String querySql = config.getCommand().get(SqlBuilderEnum.QUERY.getName());
        Assert.hasText(querySql, "查询语句不能为空.");

        // 2、设置参数
        Collections.addAll(config.getArgs(), getPageArgs(config.getPageIndex(), config.getPageSize()));

        // 3、执行SQL
        List<Map<String, Object>> list = connectorMapper.execute(databaseTemplate -> databaseTemplate.queryForList(querySql, config.getArgs().toArray()));

        // 4、返回结果集
        return new Result(list);
    }

    @Override
    public Result writer(DatabaseConnectorMapper connectorMapper, WriterBatchConfig config) {
        String event = config.getEvent();
        List<Map> data = config.getData();

        // 1、获取SQL
        String executeSql = config.getCommand().get(event);
        Assert.hasText(executeSql, "执行SQL语句不能为空.");
        if (CollectionUtils.isEmpty(config.getFields())) {
            logger.error("writer fields can not be empty.");
            throw new ConnectorException("writer fields can not be empty.");
        }
        if (CollectionUtils.isEmpty(data)) {
            logger.error("writer data can not be empty.");
            throw new ConnectorException("writer data can not be empty.");
        }
        List<Field> fields = new ArrayList<>(config.getFields());
        Field pkField = getPrimaryKeyField(config.getFields());
        // Update / Delete
        if (!isInsert(event)) {
            if (isDelete(event)) {
                fields.clear();
            }
            fields.add(pkField);
        }

        final int size = data.size();
        final int fSize = fields.size();
        Result result = new Result();
        int[] execute = null;
        try {
            // 2、设置参数
            execute = connectorMapper.execute(databaseTemplate -> {
                int[] batchUpdate = databaseTemplate.batchUpdate(executeSql, new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement preparedStatement, int i) {
                        batchRowsSetter(databaseTemplate.getConnection(), preparedStatement, fields, fSize, data.get(i));
                    }

                    @Override
                    public int getBatchSize() {
                        return size;
                    }
                });
                return batchUpdate;
            });
        } catch (Exception e) {
            result.getError().append("SQL:").append(executeSql).append(System.lineSeparator())
                    .append("ERROR:").append(e.getMessage()).append(System.lineSeparator());
            logger.error(result.getError().toString());
        }

        if (null != execute) {
            int batchSize = execute.length;
            for (int i = 0; i < batchSize; i++) {
                if (execute[i] == 0) {
                    result.getFailData().add(data.get(i));
                    result.getError().append("SQL:").append(executeSql).append(System.lineSeparator())
                            .append("DATA:").append(data.get(i)).append(System.lineSeparator());
                    continue;
                }
                result.getSuccessData().add(data.get(i));
            }
        }
        return result;
    }

    @Override
    public Result writer(DatabaseConnectorMapper connectorMapper, WriterSingleConfig config) {
        String event = config.getEvent();
        List<Field> fields = config.getFields();
        Map<String, Object> data = config.getData();
        // 1、获取 SQL
        String sql = config.getCommand().get(event);
        Assert.hasText(sql, "执行语句不能为空.");
        if (CollectionUtils.isEmpty(data) || CollectionUtils.isEmpty(fields)) {
            logger.error("writer data can not be empty.");
            throw new ConnectorException("writer data can not be empty.");
        }

        Field pkField = getPrimaryKeyField(fields);
        // Update / Delete
        if (!isInsert(event)) {
            if (isDelete(event)) {
                fields.clear();
            }
            fields.add(pkField);
        }

        int size = fields.size();
        Result result = new Result();
        int execute = 0;
        try {
            // 2、设置参数
            execute = connectorMapper.execute(databaseTemplate ->
                    databaseTemplate.update(sql, (ps) ->
                            batchRowsSetter(databaseTemplate.getConnection(), ps, fields, size, data)
                    )
            );
        } catch (Exception e) {
            // 记录错误数据
            if (!config.isForceUpdate()) {
                result.getFailData().add(data);
                result.getError().append("SQL:").append(sql).append(System.lineSeparator())
                        .append("DATA:").append(data).append(System.lineSeparator())
                        .append("ERROR:").append(e.getMessage()).append(System.lineSeparator());
                logger.error(result.getError().toString());
            }
        }

        if (0 == execute && !config.isRetry() && null != pkField) {
            // 不存在转insert
            if (isUpdate(event)) {
                String queryCount = config.getCommand().get(ConnectorConstant.OPERTION_QUERY_COUNT_EXIST);
                if (!existRow(connectorMapper, queryCount, data.get(pkField.getName()))) {
                    fields.remove(fields.size() - 1);
                    config.setEvent(ConnectorConstant.OPERTION_INSERT);
                    config.setRetry(true);
                    logger.warn("{}表执行{}失败, 尝试执行{}, {}", config.getTable(), event, config.getEvent(), data);
                    return writer(connectorMapper, config);
                }
                return result;
            }
            // 存在转update
            if (isInsert(event)) {
                config.setEvent(ConnectorConstant.OPERTION_UPDATE);
                config.setRetry(true);
                return writer(connectorMapper, config);
            }

        }
        return result;
    }

    @Override
    public Map<String, String> getSourceCommand(CommandConfig commandConfig) {
        // 获取过滤SQL
        List<Filter> filter = commandConfig.getFilter();
        String queryFilterSql = getQueryFilterSql(filter);

        // 获取查询SQL
        Table table = commandConfig.getTable();
        Map<String, String> map = new HashMap<>();

        String query = ConnectorConstant.OPERTION_QUERY;
        map.put(query, buildSql(query, table, commandConfig.getOriginalTable(), queryFilterSql));

        // 获取查询总数SQL
        String quotation = buildSqlWithQuotation();
        String pk = findTablePrimaryKey(commandConfig.getOriginalTable(), quotation);
        StringBuilder queryCount = new StringBuilder();
        queryCount.append("SELECT COUNT(1) FROM (SELECT 1 FROM ").append(quotation).append(table.getName()).append(quotation);
        if (StringUtil.isNotBlank(queryFilterSql)) {
            queryCount.append(queryFilterSql);
        }
        if (!StringUtil.isBlank(pk)) {
            queryCount.append(" GROUP BY ").append(pk);
        }
        queryCount.append(") DBSYNCER_T");
        map.put(ConnectorConstant.OPERTION_QUERY_COUNT, queryCount.toString());
        return map;
    }

    @Override
    public Map<String, String> getTargetCommand(CommandConfig commandConfig) {
        // 获取增删改SQL
        Map<String, String> map = new HashMap<>();
        Table table = commandConfig.getTable();
        Table originalTable = commandConfig.getOriginalTable();

        String insert = SqlBuilderEnum.INSERT.getName();
        map.put(insert, buildSql(insert, table, originalTable, null));

        String update = SqlBuilderEnum.UPDATE.getName();
        map.put(update, buildSql(update, table, originalTable, null));

        String delete = SqlBuilderEnum.DELETE.getName();
        map.put(delete, buildSql(delete, table, originalTable, null));

        // 获取查询数据行是否存在
        String quotation = buildSqlWithQuotation();
        String pk = findTablePrimaryKey(commandConfig.getOriginalTable(), quotation);
        StringBuilder queryCount = new StringBuilder().append("SELECT COUNT(1) FROM ").append(quotation).append(table.getName()).append(
                quotation).append(" WHERE ").append(pk).append(" = ?");
        String queryCountExist = ConnectorConstant.OPERTION_QUERY_COUNT_EXIST;
        map.put(queryCountExist, queryCount.toString());
        return map;
    }

    /**
     * 获取DQL表信息
     *
     * @param config
     * @return
     */
    protected List<Table> getDqlTable(DatabaseConnectorMapper config) {
        MetaInfo metaInfo = getDqlMetaInfo(config);
        Assert.notNull(metaInfo, "SQL解析异常.");
        DatabaseConfig cfg = config.getConfig();
        List<Table> tables = new ArrayList<>();
        tables.add(new Table(cfg.getSql()));
        return tables;
    }

    /**
     * 获取DQL元信息
     *
     * @param config
     * @return
     */
    protected MetaInfo getDqlMetaInfo(DatabaseConnectorMapper config) {
        DatabaseConfig cfg = config.getConfig();
        String sql = cfg.getSql().toUpperCase();
        String queryMetaSql = StringUtil.contains(sql, " WHERE ") ? sql + " AND 1!=1 " : sql + " WHERE 1!=1 ";
        return config.execute(databaseTemplate -> getMetaInfo(databaseTemplate, queryMetaSql, cfg.getTable()));
    }

    /**
     * 获取DQL源配置
     *
     * @param commandConfig
     * @param appendGroupByPK
     * @return
     */
    protected Map<String, String> getDqlSourceCommand(CommandConfig commandConfig, boolean appendGroupByPK) {
        // 获取过滤SQL
        List<Filter> filter = commandConfig.getFilter();
        String queryFilterSql = getQueryFilterSql(filter);

        // 获取查询SQL
        Table table = commandConfig.getTable();
        Map<String, String> map = new HashMap<>();
        String querySql = table.getName();

        // 存在条件
        if (StringUtil.isNotBlank(queryFilterSql)) {
            querySql += queryFilterSql;
        }
        String quotation = buildSqlWithQuotation();
        String pk = findTablePrimaryKey(commandConfig.getOriginalTable(), quotation);
        map.put(SqlBuilderEnum.QUERY.getName(), getPageSql(new PageSqlConfig(querySql, pk)));

        // 获取查询总数SQL
        StringBuilder queryCount = new StringBuilder();
        queryCount.append("SELECT COUNT(1) FROM (").append(table.getName());
        if (StringUtil.isNotBlank(queryFilterSql)) {
            queryCount.append(queryFilterSql);
        }
        // Mysql
        if (appendGroupByPK) {
            queryCount.append(" GROUP BY ").append(pk);
        }
        queryCount.append(") DBSYNCER_T");
        map.put(ConnectorConstant.OPERTION_QUERY_COUNT, queryCount.toString());
        return map;
    }

    /**
     * 查询语句表名和字段带上引号（默认不加）
     *
     * @return
     */
    protected String buildSqlWithQuotation() {
        return "";
    }

    /**
     * 获取查询条件SQL
     *
     * @param filter
     * @return
     */
    protected String getQueryFilterSql(List<Filter> filter) {
        if (CollectionUtils.isEmpty(filter)) {
            return "";
        }
        // 过滤条件SQL
        StringBuilder sql = new StringBuilder();

        // 拼接并且SQL
        String addSql = getFilterSql(OperationEnum.AND.getName(), filter);
        // 如果Add条件存在
        if (StringUtil.isNotBlank(addSql)) {
            sql.append(addSql);
        }

        // 拼接或者SQL
        String orSql = getFilterSql(OperationEnum.OR.getName(), filter);
        // 如果Or条件和Add条件都存在
        if (StringUtil.isNotBlank(orSql) && StringUtil.isNotBlank(addSql)) {
            sql.append(" OR ");
        }
        sql.append(orSql);

        // 如果有条件加上 WHERE
        if (StringUtil.isNotBlank(sql.toString())) {
            // WHERE (USER.USERNAME = 'zhangsan' AND USER.AGE='20') OR (USER.TEL='18299996666')
            sql.insert(0, " WHERE ");
        }
        return sql.toString();
    }

    /**
     * 获取查询SQL
     *
     * @param type           {@link SqlBuilderEnum}
     * @param table
     * @param originalTable
     * @param queryFilterSQL
     * @return
     */
    protected String buildSql(String type, Table table, Table originalTable, String queryFilterSQL) {
        if (null == table) {
            logger.error("Table can not be null.");
            throw new ConnectorException("Table can not be null.");
        }
        List<Field> column = table.getColumn();
        if (CollectionUtils.isEmpty(column)) {
            logger.warn("Table column is null.");
            return null;
        }
        String pk = null;
        Set<String> mark = new HashSet<>();
        List<Field> fields = new ArrayList<>();
        for (Field c : column) {
            String name = c.getName();
            if (StringUtil.isBlank(name)) {
                throw new ConnectorException("The field name can not be empty.");
            }
            if (c.isPk()) {
                pk = name;
            }
            if (!mark.contains(name)) {
                fields.add(c);
                mark.add(name);
            }
        }
        if (CollectionUtils.isEmpty(fields)) {
            logger.error("The fields can not be empty.");
            throw new ConnectorException("The fields can not be empty.");
        }
        String tableName = table.getName();
        if (StringUtil.isBlank(tableName)) {
            logger.error("Table name can not be empty.");
            throw new ConnectorException("Table name can not be empty.");
        }
        if (StringUtil.isBlank(pk)) {
            pk = findTablePrimaryKey(originalTable, "");
        }

        SqlBuilderConfig config = new SqlBuilderConfig(this, tableName, pk, fields, queryFilterSQL, buildSqlWithQuotation());
        return SqlBuilderEnum.getSqlBuilder(type).buildSql(config);
    }

    /**
     * 健康检查
     *
     * @return
     */
    protected String getValidationQuery() {
        return "select 1";
    }

    /**
     * 根据过滤条件获取查询SQL
     *
     * @param queryOperator and/or
     * @param filter
     * @return
     */
    private String getFilterSql(String queryOperator, List<Filter> filter) {
        List<Filter> list = filter.stream().filter(f -> StringUtil.equals(f.getOperation(), queryOperator)).collect(Collectors.toList());
        if (CollectionUtils.isEmpty(list)) {
            return "";
        }

        int size = list.size();
        int end = size - 1;
        StringBuilder sql = new StringBuilder();
        sql.append("(");
        Filter c = null;
        String quotation = buildSqlWithQuotation();
        for (int i = 0; i < size; i++) {
            c = list.get(i);
            // "USER" = 'zhangsan'
            sql.append(quotation).append(c.getName()).append(quotation).append(c.getFilter()).append("'").append(c.getValue()).append("'");
            if (i < end) {
                sql.append(" ").append(queryOperator).append(" ");
            }
        }
        sql.append(")");
        return sql.toString();
    }

    /**
     * @param connection 连接
     * @param ps         参数构造器
     * @param fields     同步字段，例如[{name=ID, type=4}, {name=NAME, type=12}]
     * @param fSize      同步字段个数
     * @param row        同步字段对应的值，例如{ID=123, NAME=张三11}
     */
    private void batchRowsSetter(Connection connection, PreparedStatement ps, List<Field> fields, int fSize, Map row) {
        Field f = null;
        int type;
        Object val = null;
        for (int i = 0; i < fSize; i++) {
            // 取出字段和对应值
            f = fields.get(i);
            type = f.getType();
            val = row.get(f.getName());
            SetterEnum.getSetter(type).set(connection, ps, i + 1, type, val);
        }
    }

    private boolean existRow(DatabaseConnectorMapper connectorMapper, String sql, Object value) {
        int rowNum = 0;
        try {
            rowNum = connectorMapper.execute(databaseTemplate -> databaseTemplate.queryForObject(sql, new Object[] {value}, Integer.class));
        } catch (Exception e) {
            logger.error("检查数据行存在异常:{}，SQL:{},参数:{}", e.getMessage(), sql, value);
        }
        return rowNum > 0;
    }

    /**
     * 获取数据库表元数据信息
     *
     * @param databaseTemplate
     * @param metaSql          查询元数据
     * @param tableName        表名
     * @return
     */
    private MetaInfo getMetaInfo(DatabaseTemplate databaseTemplate, String metaSql, String tableName) throws SQLException {
        SqlRowSet sqlRowSet = databaseTemplate.queryForRowSet(metaSql);
        ResultSetWrappingSqlRowSet rowSet = (ResultSetWrappingSqlRowSet) sqlRowSet;
        SqlRowSetMetaData metaData = rowSet.getMetaData();

        // 查询表字段信息
        int columnCount = metaData.getColumnCount();
        if (1 > columnCount) {
            throw new ConnectorException("查询表字段不能为空.");
        }
        List<Field> fields = new ArrayList<>(columnCount);
        Map<String, List<String>> tables = new HashMap<>();
        try {
            DatabaseMetaData md = databaseTemplate.getConnection().getMetaData();
            String name = null;
            String label = null;
            String typeName = null;
            String table = null;
            int columnType;
            boolean pk;
            for (int i = 1; i <= columnCount; i++) {
                table = StringUtil.isNotBlank(tableName) ? tableName : metaData.getTableName(i);
                if (null == tables.get(table)) {
                    tables.putIfAbsent(table, findTablePrimaryKeys(md, table));
                }
                name = metaData.getColumnName(i);
                label = metaData.getColumnLabel(i);
                typeName = metaData.getColumnTypeName(i);
                columnType = metaData.getColumnType(i);
                pk = isPk(tables, table, name);
                fields.add(new Field(label, typeName, columnType, pk));
            }
        } finally {
            tables.clear();
        }
        return new MetaInfo().setColumn(fields);
    }

    /**
     * 返回主键名称
     *
     * @param table
     * @param quotation
     * @return
     */
    private String findTablePrimaryKey(Table table, String quotation) {
        if (null != table) {
            List<Field> column = table.getColumn();
            if (!CollectionUtils.isEmpty(column)) {
                for (Field c : column) {
                    if (c.isPk()) {
                        return new StringBuilder(quotation).append(c.getName()).append(quotation).toString();
                    }
                }
            }
        }
        if (!TableTypeEnum.isView(table.getType())) {
            throw new ConnectorException("Table primary key can not be empty.");
        }
        return "";
    }

    private boolean isPk(Map<String, List<String>> tables, String tableName, String name) {
        List<String> pk = tables.get(tableName);
        return !CollectionUtils.isEmpty(pk) && pk.contains(name);
    }

    private List<String> findTablePrimaryKeys(DatabaseMetaData md, String tableName) throws SQLException {
        //根据表名获得主键结果集
        ResultSet rs = null;
        List<String> primaryKeys = new ArrayList<>();
        try {
            rs = md.getPrimaryKeys(null, null, tableName);
            while (rs.next()) {
                primaryKeys.add(rs.getString("COLUMN_NAME"));
            }
        } finally {
            DatabaseUtil.close(rs);
        }
        return primaryKeys;
    }

}