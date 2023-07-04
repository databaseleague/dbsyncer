package org.dbsyncer.listener.kafka;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.dbsyncer.common.event.RowChangedEvent;
import org.dbsyncer.connector.config.KafkaConfig;
import org.dbsyncer.connector.constant.ConnectorConstant;
import org.dbsyncer.connector.kafka.KafkaClient;
import org.dbsyncer.connector.kafka.KafkaConnectorMapper;
import org.dbsyncer.listener.AbstractExtractor;
import org.dbsyncer.listener.ListenerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.ClosedWatchServiceException;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author AE86
 * @version 1.0.0
 * @date 2021/12/18 22:19
 */
public class KafkaExtractor extends AbstractExtractor {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final Lock connectLock = new ReentrantLock();
    private KafkaConnectorMapper connectorMapper;
    private volatile boolean connected;
    private KafkaClient kafkaClient;
    private KafkaConfig config;
    private Worker worker;
    private ObjectMapper mapper = new ObjectMapper();

    @Override
    public void start() {
        connectLock.lock();
        try {
            if (connected) {
                logger.error("FileExtractor is already started");
                return;
            }

            connectorMapper = (KafkaConnectorMapper) connectorFactory.connect(connectorConfig);
            config = connectorMapper.getConfig();
            final String mapperCacheKey = connectorFactory.getConnector(connectorMapper).getConnectorMapperCacheKey(connectorConfig);
            connected = true;
            kafkaClient = connectorMapper.getConnection();

            worker = new Worker();
            worker.setName(new StringBuilder("kafka-parser-").append(mapperCacheKey).append("_").append(worker.hashCode()).toString());
            worker.setDaemon(false);
            worker.start();
        } catch (Exception e) {
            logger.error("启动失败:{}", e.getMessage());
            closePipelineAndWatch();
            throw new ListenerException(e);
        } finally {
            connectLock.unlock();
        }
    }

    @Override
    public void close() {
        try {
            closePipelineAndWatch();
            connected = false;
            if (null != worker && !worker.isInterrupted()) {
                worker.interrupt();
                worker = null;
            }
        } catch (Exception e) {
            logger.error("关闭失败:{}", e.getMessage());
        }
    }

    private void closePipelineAndWatch() {
        kafkaClient.close();
    }


    final class Worker extends Thread {
        private KafkaConsumer<String, Object> kafkaConsumer;

        private void buildConsumerAndSubscribe() {
            Properties props = new Properties();
            props.put("bootstrap.servers", config.getBootstrapServers());
            props.put("group.id", config.getGroupId());
            props.put("enable.auto.commit", true);
            props.put("auto.commit.interval.ms", 5000);
            props.put("session.timeout.ms", config.getSessionTimeoutMs());
            props.put("max.partition.fetch.bytes", config.getMaxPartitionFetchBytes());
            props.put("value.deserializer", config.getDeserializer());
            props.put("key.deserializer", config.getDeserializer());
//            props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
//            props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            this.kafkaConsumer = new KafkaConsumer<>(props);
            this.kafkaConsumer.subscribe(Collections.singletonList(config.getTopic()));
        }


        @Override
        public void run() {
            buildConsumerAndSubscribe();
            while (!isInterrupted() && connected) {
                ConsumerRecords<String, Object> records = null;
                try {
                    records = kafkaConsumer.poll(config.getPollTimeout());
                    if (!records.isEmpty()) {
                        List<Object> parserData = parserData(records);
                        for (Object datum : parserData) {
                            changedEvent(new RowChangedEvent(config.getTopic(), ConnectorConstant.OPERTION_INSERT,
                                    (Map<String, Object>) datum));
                        }
                    }

                } catch (ClosedWatchServiceException e) {
                    break;
                } catch (Exception e) {
                    logger.error(String.valueOf(e));
                }
            }
        }

        protected List<Object> parserData(ConsumerRecords<String, Object> records) {
            List<Object> results = new ArrayList<>();
            for (ConsumerRecord<String, Object> record : records) {
                deserializeData(record, results);
            }
            return results;
        }

        private void deserializeData(ConsumerRecord<String, Object> consumerRecord, List<Object> results) {
            Object value = consumerRecord.value();
            if (isJsonArray(value.toString())) {
                List rs = JSON.parseArray(value.toString());
                for (Object v : rs) {
                    if (v instanceof Map) {
                        results.add(v);
                    }
                }
            } else if (isJson(value.toString())) {
                JSONObject formOptions = JSON.parseObject(value.toString());
                Map<String, Object> valueMap = JSON.parseObject(formOptions.toString());
                results.add(valueMap);
            } else if (value instanceof String) {
                results.add(value);
            } else {
                if (logger.isWarnEnabled()) {
                    logger.warn("unknown value type:{}", value.getClass().getName());
                }
                results.add(String.valueOf(value));
            }
        }

        /**
         * 判断是是否为json 组
         */
        public boolean isJsonArray(String content) {
            try {
                mapper.readValue(content, List.class);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /**
         * 判断是是否为json
         */
        public boolean isJson(String content) {
            try {
                mapper.readValue(content, Map.class);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
