package org.elasticsearch.river.nsq;

import ly.bit.nsq.ConnectionUtils;
import ly.bit.nsq.Message;
import ly.bit.nsq.exceptions.NSQException;
import ly.bit.nsq.exceptions.RequeueWithoutBackoff;
import ly.bit.nsq.sync.BatchReader;
import ly.bit.nsq.sync.SyncHandler;
import org.apache.lucene.util.ThreadInterruptedException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.indices.InvalidIndexNameException;
import org.elasticsearch.river.AbstractRiverComponent;
import org.elasticsearch.river.River;
import org.elasticsearch.river.RiverName;
import org.elasticsearch.river.RiverSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 *
 */
public class NsqBatchRiver extends AbstractRiverComponent implements River {

    private static final String DEFAULT_TOPIC = "elasticsearch";
    private static final String DEFAULT_CHANNEL = "elasticsearch";

    private static final int DEFAULT_BULKSIZE = 200;
    private static final int DEFAULT_BULKTIMEOUT = 500;
    private static final int DEFAULT_WORKERS = 1;
    private static final boolean DEFAULT_ORDERED  = false;

    private static final int DEFAULT_REQUEUE_DELAY = 50;
    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final int DEFAULT_MAX_INFLIGHT = 10;

    private final Client client;

    private final String[] nsqAddresses;

    private final String nsqTopic;
    private final String nsqChannel;

    private final int workers;
    private final int bulkSize;
    private final TimeValue bulkTimeout;
    private final boolean ordered;

    private int requeueDelay = DEFAULT_REQUEUE_DELAY;
    private int maxRetries = DEFAULT_MAX_RETRIES;
    private int maxInFlight = DEFAULT_MAX_INFLIGHT;

    private volatile boolean closed = false;
    private volatile Thread[] thread;

    private ConcurrentLinkedQueue<Message> messages = new ConcurrentLinkedQueue<Message>();
    private ScheduledExecutorService timer;

    @SuppressWarnings({"unchecked"})
    @Inject
    public NsqBatchRiver(RiverName riverName, RiverSettings settings, Client client) {
        super(riverName, settings);
        this.client = client;

        if (settings.settings().containsKey("nsq")) {
            Map<String, Object> nsqSettings = (Map<String, Object>) settings.settings().get("nsq");

            if (nsqSettings.containsKey("addresses")) {
                List<String> addresses = new ArrayList<String>();
                for (Map<String, Object> address : (List<Map<String, Object>>) nsqSettings.get("addresses")) {
                    addresses.add(XContentMapValues.nodeStringValue(address.get("address"), "http://localhost:4161"));
                }
                nsqAddresses = addresses.toArray(new String[addresses.size()]);
            } else {
                String nsqHost = XContentMapValues.nodeStringValue(nsqSettings.get("address"), "http://localhost:4161");
                nsqAddresses = new String[]{nsqHost};
            }

            nsqTopic = XContentMapValues.nodeStringValue(nsqSettings.get("topic"), DEFAULT_TOPIC);
            nsqChannel = XContentMapValues.nodeStringValue(nsqSettings.get("channel"), DEFAULT_CHANNEL);
            maxInFlight = XContentMapValues.nodeIntegerValue(nsqSettings.get("max_inflight"), DEFAULT_MAX_INFLIGHT);
            maxRetries = XContentMapValues.nodeIntegerValue(nsqSettings.get("max_retries"), DEFAULT_MAX_RETRIES);
            requeueDelay = XContentMapValues.nodeIntegerValue(nsqSettings.get("requeue_delay"), DEFAULT_REQUEUE_DELAY);
        } else {
            nsqAddresses = new String[]{"http://localhost:4161"};

            nsqTopic = DEFAULT_TOPIC;
            nsqChannel = DEFAULT_CHANNEL;
            maxInFlight = DEFAULT_MAX_INFLIGHT;
            maxRetries = DEFAULT_MAX_RETRIES;
            requeueDelay = DEFAULT_REQUEUE_DELAY;
        }

        if (settings.settings().containsKey("index")) {
            Map<String, Object> indexSettings = (Map<String, Object>) settings.settings().get("index");
            bulkSize = XContentMapValues.nodeIntegerValue(indexSettings.get("bulk_size"), DEFAULT_BULKSIZE);
            if (indexSettings.containsKey("bulk_timeout")) {
                bulkTimeout = TimeValue.parseTimeValue(XContentMapValues.nodeStringValue(indexSettings.get("bulk_timeout"), DEFAULT_BULKTIMEOUT + "ms"), TimeValue.timeValueMillis(DEFAULT_BULKTIMEOUT));
            } else {
                bulkTimeout = TimeValue.timeValueMillis(DEFAULT_BULKTIMEOUT);
            }
            workers = XContentMapValues.nodeIntegerValue(indexSettings.get("workers"), DEFAULT_WORKERS);
            ordered = XContentMapValues.nodeBooleanValue(indexSettings.get("ordered"), DEFAULT_ORDERED);
        } else {
            bulkSize = DEFAULT_BULKSIZE;
            bulkTimeout = TimeValue.timeValueMillis(DEFAULT_BULKTIMEOUT);
            workers = DEFAULT_WORKERS;
            ordered = DEFAULT_ORDERED;
        }
    }

    @Override
    public void start() {
        ThreadFactory factory = EsExecutors.daemonThreadFactory(settings.globalSettings(), "nsq_river");

        logger.info("creating nsq river, addresses [{}] => [{}]", nsqAddresses, this.workers);
        this.thread = new Thread[this.workers];
        for (int count = 0; count < this.thread.length; count++) {
            this.thread[count] = factory.newThread(new Consumer());
            this.thread[count].start();
        }

        logger.info("creating nsq river executor => [{}] ms", bulkTimeout.getMillis());
        this.timer = Executors.newSingleThreadScheduledExecutor(factory);
        this.timer.scheduleAtFixedRate(new MessageBatchTimerTask(), 0, bulkTimeout.getMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        logger.info("closing nsq river => [{}]", this.workers);

        if (this.thread != null) {
            for (Thread aThread : this.thread) {
                aThread.interrupt();
            }
        }
        this.thread = null;

        logger.info("creating nsq river executor");
        if (this.timer != null) {
            this.timer.shutdownNow();
        }
        this.timer = null;
    }

    class MessageBatchTimerTask implements Runnable {

        public void requeueMessage(Message msg, boolean doDelay) {
            if (msg.getAttempts() > maxRetries) {
                this.finishMessage(msg);
            } else {
                int newDelay = doDelay ? 0 : requeueDelay * msg.getAttempts();
                try {
                    msg.getConn().send(ConnectionUtils.requeue(msg.getId(), newDelay));
                } catch (NSQException e) {
                    msg.getConn().close();
                }
            }
        }

        public void finishMessage(Message msg) {
            try {
                msg.getConn().send(ConnectionUtils.finish(msg.getId()));
            } catch (NSQException e) {
                msg.getConn().close();
            }
        }

        protected void process(BulkRequestBuilder bulkRequestBuilder, final List<Message> messages_to_execute) {
            if (logger.isDebugEnabled()) {
                logger.debug("processing tasks " + bulkRequestBuilder.numberOfActions());
            }

            if (ordered) {
                try {
                    BulkResponse bulk_response = bulkRequestBuilder.execute().actionGet();
                    if (bulk_response.hasFailures()) {
                        logger.warn("failed to execute" + bulk_response.buildFailureMessage());
                    }

                    for (Message message : messages_to_execute) {
                        finishMessage(message);
                    }
                } catch (Exception e) {
                    logger.warn("failed to execute bulk", e);
                }
            } else {
                bulkRequestBuilder.execute(new ActionListener<BulkResponse>() {
                    @Override
                    public void onResponse(BulkResponse bulk_response) {
                        if (bulk_response.hasFailures()) {
                            logger.warn("failed to execute" + bulk_response.buildFailureMessage());
                        }

                        for (Message message : messages_to_execute) {
                            finishMessage(message);
                        }
                    }

                    @Override
                    public void onFailure(Throwable e) {
                        if (e instanceof InvalidIndexNameException) {
                            logger.warn("failed to execute bulk, dropping message", e);

                            for (Message message : messages_to_execute) {
                                finishMessage(message);
                            }
                        } else {
                            logger.warn("failed to execute bulk, requeuing message", e);

                            for (Message message : messages_to_execute) {
                                requeueMessage(message, true);
                            }
                        }
                    }
                });
            }
        }

        public void run() {
            if (logger.isDebugEnabled()) {
                logger.debug("Running executor...");
            }

            List<Message> worklist = new ArrayList<Message>();
            while (messages.peek() != null) {
                Message message = messages.poll();
                worklist.add(message);
            }

            if (!worklist.isEmpty()) {
                BulkRequestBuilder bulkRequestBuilder = client.prepareBulk();

                for (Message message : worklist) {
                    try {
                        bulkRequestBuilder.add(message.getBody(), 0, message.getBody().length, true);

                        if (bulkRequestBuilder.numberOfActions() >= bulkSize) {
                            break;
                        }
                    } catch (RequeueWithoutBackoff e) {
                        requeueMessage(message, false);
                    } catch (Exception e) {
                        logger.error("failed to add a message", e);
                    }
                }

                process(bulkRequestBuilder, worklist);
            }
        }
    }

    private class Consumer implements Runnable, SyncHandler {

        @Override
        public boolean handleMessage(Message msg) throws NSQException {
            messages.add(msg);
            return true;
        }

        @Override
        public void run() {
            BatchReader batchReader = null;

            while (true) {
                if (closed) {
                    break;
                }
                try {
                    batchReader = new BatchReader(nsqTopic, nsqChannel, this, maxRetries, maxInFlight);

                    for (String nsqAddress : nsqAddresses) {
                        logger.info("performing lookupd [{}] => [{}] : [{}]", nsqAddress, nsqTopic, nsqChannel);
                        batchReader.addLookupd(nsqAddress);
                    }
                } catch (Exception e) {
                    if (!closed) {
                        logger.error("failed to created a connection / channel", e);
                    } else {
                        continue;
                    }

                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e1) {
                        // ignore, if we are closing, we will exit later
                    }

                    break;
                }

                // now use the queue to listen for messages
                while (true) {
                    if (closed || Thread.interrupted()) {
                        break;
                    }

                    try {
                        Thread.sleep(Long.MAX_VALUE);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }

            if (batchReader != null) {
                logger.info("closing lookupd [{}] : [{}]", nsqTopic, nsqChannel);
                batchReader.shutdown();
            }
        }
    }
}
