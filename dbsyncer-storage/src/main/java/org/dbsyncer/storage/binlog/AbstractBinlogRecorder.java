package org.dbsyncer.storage.binlog;

import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.util.BytesRef;
import org.dbsyncer.common.config.BinlogRecorderConfig;
import org.dbsyncer.common.model.Paging;
import org.dbsyncer.common.scheduled.ScheduledTaskJob;
import org.dbsyncer.common.scheduled.ScheduledTaskService;
import org.dbsyncer.common.snowflake.SnowflakeIdWorker;
import org.dbsyncer.common.util.CollectionUtils;
import org.dbsyncer.common.util.DateFormatUtil;
import org.dbsyncer.storage.binlog.proto.BinlogMessage;
import org.dbsyncer.storage.constant.BinlogConstant;
import org.dbsyncer.storage.enums.IndexFieldResolverEnum;
import org.dbsyncer.storage.lucene.Shard;
import org.dbsyncer.storage.query.Option;
import org.dbsyncer.storage.util.ParamsUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author AE86
 * @version 1.0.0
 * @date 2022/6/8 0:53
 */
public abstract class AbstractBinlogRecorder<Message> implements BinlogRecorder, DisposableBean {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private static final String PATH = new StringBuilder(System.getProperty("user.dir")).append(File.separatorChar).append("data").append(
            File.separatorChar).append("data").append(File.separatorChar).toString();

    @Autowired
    private ScheduledTaskService scheduledTaskService;

    @Autowired
    private SnowflakeIdWorker snowflakeIdWorker;

    @Autowired
    private BinlogRecorderConfig binlogRecorderConfig;

    private static Queue<BinlogMessage> queue;

    private static Shard shard;

    private WriterTask writerTask = new WriterTask();

    private ReaderTask readerTask = new ReaderTask();

    @PostConstruct
    private void init() throws IOException {
        queue = new LinkedBlockingQueue(binlogRecorderConfig.getQueueCapacity());
        shard = new Shard(PATH + binlogRecorderConfig.getTaskName());
        scheduledTaskService.start(binlogRecorderConfig.getWriterPeriodMillisecond(), writerTask);
        scheduledTaskService.start(binlogRecorderConfig.getReaderPeriodMillisecond(), readerTask);
    }

    /**
     * 反序列化任务
     *
     * @param message
     * @return
     */
    protected abstract Message deserialize(String messageId, BinlogMessage message);

    @Override
    public void flush(BinlogMessage message) {
        queue.offer(message);
    }

    @Override
    public void destroy() throws IOException {
        shard.close();
    }

    /**
     * 消息同步完成后，删除消息记录
     *
     * @param messageIds
     */
    protected void completeMessage(List<String> messageIds) {
        if (!CollectionUtils.isEmpty(messageIds)) {
            try {
                int size = messageIds.size();
                Term[] terms = new Term[size];
                for (int i = 0; i < size; i++) {
                    terms[i] = new Term(BinlogConstant.BINLOG_ID, messageIds.get(i));
                }
                shard.deleteBatch(terms);
            } catch (IOException e) {
                logger.error(e.getMessage());
            }
        }
    }

    /**
     * 合并缓存队列任务到磁盘
     */
    final class WriterTask implements ScheduledTaskJob {

        @Override
        public void run() {
            if (queue.isEmpty()) {
                return;
            }

            List<Document> tasks = new ArrayList<>();
            int count = 0;
            long now = Instant.now().toEpochMilli();
            while (!queue.isEmpty() && count < binlogRecorderConfig.getBatchCount()) {
                BinlogMessage message = queue.poll();
                if (null != message) {
                    tasks.add(ParamsUtil.convertBinlog2Doc(String.valueOf(snowflakeIdWorker.nextId()), BinlogConstant.READY, new BytesRef(message.toByteArray()), now));
                }
                count++;
            }

            if (!CollectionUtils.isEmpty(tasks)) {
                try {
                    shard.insertBatch(tasks);
                } catch (IOException e) {
                    logger.error(e.getMessage());
                }
            }
        }
    }

    /**
     * 从磁盘读取日志到任务队列
     */
    final class ReaderTask implements ScheduledTaskJob {

        private final Lock lock = new ReentrantLock(true);

        private volatile boolean running;

        @Override
        public void run() {
            if (running || (binlogRecorderConfig.getBatchCount() * 2) + getQueue().size() >= getQueueCapacity()) {
                return;
            }

            final Lock binlogLock = lock;
            boolean locked = false;
            try {
                locked = binlogLock.tryLock();
                if (locked) {
                    running = true;
                    doParse();
                }
            } catch (Exception e) {
                logger.error(e.getMessage());
            } finally {
                if (locked) {
                    running = false;
                    binlogLock.unlock();
                }
            }
        }

        private void doParse() throws IOException {
            //  查询[待处理] 或 [处理中 & 处理超时]
            long maxProcessingSeconds = Timestamp.valueOf(LocalDateTime.now().minusSeconds(binlogRecorderConfig.getMaxProcessingSeconds())).getTime();
            BooleanQuery query = new BooleanQuery.Builder()
                    .add(new BooleanQuery.Builder()
                            .add(IntPoint.newExactQuery(BinlogConstant.BINLOG_STATUS, BinlogConstant.READY), BooleanClause.Occur.MUST)
                            .build(), BooleanClause.Occur.SHOULD)
                    .add(new BooleanQuery.Builder()
                            .add(IntPoint.newExactQuery(BinlogConstant.BINLOG_STATUS, BinlogConstant.PROCESSING), BooleanClause.Occur.MUST)
                            .add(LongPoint.newRangeQuery(BinlogConstant.BINLOG_TIME, Long.MIN_VALUE, maxProcessingSeconds), BooleanClause.Occur.MUST)
                            .build(), BooleanClause.Occur.SHOULD)
                    .build();
            Option option = new Option(query);
            option.addIndexFieldResolverEnum(BinlogConstant.BINLOG_STATUS, IndexFieldResolverEnum.INT);
            option.addIndexFieldResolverEnum(BinlogConstant.BINLOG_CONTENT, IndexFieldResolverEnum.BINARY);
            option.addIndexFieldResolverEnum(BinlogConstant.BINLOG_TIME, IndexFieldResolverEnum.LONG);

            // 优先处理最早记录
            Sort sort = new Sort(new SortField(BinlogConstant.BINLOG_TIME, SortField.Type.LONG));
            Paging paging = shard.query(option, 1, binlogRecorderConfig.getBatchCount(), sort);
            if (CollectionUtils.isEmpty(paging.getData())) {
                return;
            }

            List<Map> list = (List<Map>) paging.getData();
            int size = list.size();
            List<Message> messageList = new ArrayList<>();
            List<Document> docs = new ArrayList<>();
            List<String> deleteIds = new ArrayList<>();
            long now = Instant.now().toEpochMilli();
            Map row = null;
            for (int i = 0; i < size; i++) {
                try {
                    row = list.get(i);
                    String id = (String) row.get(BinlogConstant.BINLOG_ID);
                    Integer status = (Integer) row.get(BinlogConstant.BINLOG_STATUS);
                    BytesRef ref = (BytesRef) row.get(BinlogConstant.BINLOG_CONTENT);
                    Message message = deserialize(id, BinlogMessage.parseFrom(ref.bytes));
                    if (null != message) {
                        messageList.add(message);
                    }
                    if(BinlogConstant.PROCESSING == status){
                        logger.warn("建议优化参数配置，当前存在超时未处理数据，正在重试.");
                        continue;
                    }
                    deleteIds.add(id);
                    docs.add(ParamsUtil.convertBinlog2Doc(String.valueOf(snowflakeIdWorker.nextId()), BinlogConstant.PROCESSING, ref, now));
                } catch (InvalidProtocolBufferException e) {
                    logger.error(e.getMessage());
                }
            }

            // 如果在更新消息状态的过程中服务被中止，为保证数据的安全性，重启后消息可能会同步2次）
            shard.insertBatch(docs);
            int deleteSize = deleteIds.size();
            Term[] terms = new Term[deleteSize];
            for (int i = 0; i < deleteSize; i++) {
                terms[i] = new Term(BinlogConstant.BINLOG_ID, deleteIds.get(i));
            }
            shard.deleteBatch(terms);

            getQueue().addAll(messageList);
        }
    }

}