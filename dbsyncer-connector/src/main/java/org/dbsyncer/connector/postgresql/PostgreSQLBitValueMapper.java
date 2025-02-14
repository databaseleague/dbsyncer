package org.dbsyncer.connector.postgresql;

import org.dbsyncer.common.spi.ConnectorMapper;
import org.dbsyncer.connector.AbstractValueMapper;
import org.dbsyncer.connector.ConnectorException;

/**
 * @author AE86
 * @version 1.0.0
 * @date 2022/8/25 0:07
 */
public class PostgreSQLBitValueMapper extends AbstractValueMapper<Boolean> {

    @Override
    protected Boolean convert(ConnectorMapper connectorMapper, Object val) {
        if (val instanceof Integer) {
            Integer i = (Integer) val;
            return i == 1;
        }
        if (val instanceof Short) {
            Short s = (Short) val;
            return s == 1;
        }

        throw new ConnectorException(String.format("%s can not find type [%s], val [%s]", getClass().getSimpleName(), val.getClass(), val));
    }

}