package org.dbsyncer.parser.flush;

import org.dbsyncer.common.scheduled.ScheduledTaskJob;
import org.dbsyncer.common.scheduled.ScheduledTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.PostConstruct;
import java.lang.reflect.ParameterizedType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 任务缓存执行器
 * <p>1. 任务优先进入缓存队列
 * <p>2. 将任务分区合并，批量执行
 *
 * @author AE86
 * @version 1.0.0
 * @date 2022/3/27 17:36
 */
public abstract class AbstractBufferActuator<Request, Response> implements BufferActuator, ScheduledTaskJob {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    private ScheduledTaskService scheduledTaskService;

    private static final int CAPACITY = 10_0000;

    private static final double BUFFER_THRESHOLD = 0.8;

    private static final int MAX_BATCH_COUNT = 3000;

    private static final int PERIOD = 300;

    private Queue<Request> buffer;

    private final Lock lock = new ReentrantLock(true);

    private volatile boolean running;

    private Class<Response> responseClazz;

    @PostConstruct
    private void init() {
        responseClazz = (Class<Response>) ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[1];
        buffer = new LinkedBlockingQueue(getQueueCapacity());
        scheduledTaskService.start(PERIOD, this);
    }

    /**
     * 生成分区key
     *
     * @param request
     * @return
     */
    protected abstract String getPartitionKey(Request request);

    /**
     * 分区
     *
     * @param request
     * @param response
     */
    protected abstract void partition(Request request, Response response);

    /**
     * 批处理
     *
     * @param response
     */
    protected abstract void pull(Response response);

    @Override
    public Queue getQueue() {
        return buffer;
    }

    @Override
    public int getQueueCapacity() {
        return CAPACITY;
    }

    @Override
    public void offer(BufferRequest request) {
        buffer.offer((Request) request);

        // TODO 临时解决方案：生产大于消费问题，限制生产速度
        int size = buffer.size();
        if (size >= (CAPACITY * BUFFER_THRESHOLD)) {
            try {
                TimeUnit.SECONDS.sleep(30);
                logger.warn("当前任务队列大小{}已达上限{}，请稍等{}秒", size, CAPACITY * BUFFER_THRESHOLD, 30);
            } catch (InterruptedException e) {
                logger.error(e.getMessage());
            }
        }
    }

    @Override
    public void run() {
        if (running) {
            return;
        }

        final Lock bufferLock = lock;
        boolean locked = false;
        try {
            locked = bufferLock.tryLock();
            if (locked) {
                running = true;
                flush(buffer);
            }
        } catch (Exception e) {
            logger.error(e.getMessage());
        } finally {
            if (locked) {
                running = false;
                bufferLock.unlock();
            }
        }
    }

    private void flush(Queue<Request> queue) throws IllegalAccessException, InstantiationException {
        if (!queue.isEmpty()) {
            AtomicLong batchCounter = new AtomicLong();
            final Map<String, BufferResponse> map = new LinkedHashMap<>();
            while (!queue.isEmpty() && batchCounter.get() < MAX_BATCH_COUNT) {
                Request poll = queue.poll();
                String key = getPartitionKey(poll);
                if (!map.containsKey(key)) {
                    map.putIfAbsent(key, (BufferResponse) responseClazz.newInstance());
                }
                partition(poll, (Response) map.get(key));
                batchCounter.incrementAndGet();
            }

            map.forEach((key, flushTask) -> {
                long now = Instant.now().toEpochMilli();
                try {
                    pull((Response) flushTask);
                } catch (Exception e) {
                    logger.error("[{}]异常{}", key);
                }
                logger.info("[{}]{}条，耗时{}毫秒", key, flushTask.getTaskSize(), (Instant.now().toEpochMilli() - now));
            });
            map.clear();
        }
    }

}