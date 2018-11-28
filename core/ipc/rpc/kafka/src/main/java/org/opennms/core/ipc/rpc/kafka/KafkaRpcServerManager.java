/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2018 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2018 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.core.ipc.rpc.kafka;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.joda.time.Duration;
import org.opennms.core.camel.JmsQueueNameFactory;
import org.opennms.core.ipc.common.kafka.KafkaConfigProvider;
import org.opennms.core.ipc.common.kafka.Utils;
import org.opennms.core.ipc.rpc.kafka.model.RpcMessageProtos;
import org.opennms.core.rpc.api.RpcModule;
import org.opennms.core.rpc.api.RpcRequest;
import org.opennms.core.rpc.api.RpcResponse;
import org.opennms.distributed.core.api.MinionIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.swrve.ratelimitedlogger.RateLimitedLog;

public class KafkaRpcServerManager {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaRpcServerManager.class);
    private static final RateLimitedLog RATE_LIMITED_LOG = RateLimitedLog
            .withRateLimit(LOG)
            .maxRate(5).every(Duration.standardSeconds(30))
            .build();
    private static final String MAX_BUFFER_SIZE = "921600";
    private static final String MESSAGE_CACHE_CONFIG = "maximumSize=100,expireAfterWrite=1m";
    private final Map<String, RpcModule<RpcRequest, RpcResponse>> registerdModules = new ConcurrentHashMap<>();
    private final Properties kafkaConfig = new Properties();
    private final KafkaConfigProvider kafkaConfigProvider;
    private KafkaProducer<String, byte[]> producer;
    private MinionIdentity minionIdentity;
    private Integer maxBufferSize;
    private final ThreadFactory threadFactory = new ThreadFactoryBuilder()
                                                       .setNameFormat("rpc-server-kafka-consumer-%d")
                                                       .build();
    private final ExecutorService executor = Executors.newCachedThreadPool(threadFactory);
    private Map<RpcModule<RpcRequest, RpcResponse>, KafkaConsumerRunner> rpcModuleConsumers = new ConcurrentHashMap<>();
    // cache to hold rpcId for directed RPCs and expire them
    private Cache<String, Long>  rpcIdCache;

    public KafkaRpcServerManager(KafkaConfigProvider configProvider, MinionIdentity minionIdentity) {
        this.kafkaConfigProvider = configProvider;
        this.minionIdentity = minionIdentity;
    }

    public void init() throws IOException {
        // group.id is mapped to minion location, so one of the minion executes the request.
        kafkaConfig.put(ConsumerConfig.GROUP_ID_CONFIG, minionIdentity.getLocation());
        kafkaConfig.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        kafkaConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getCanonicalName());
        kafkaConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getCanonicalName());
        kafkaConfig.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000");
        kafkaConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        kafkaConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getCanonicalName());
        kafkaConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getCanonicalName());
        // Retrieve all of the properties from org.opennms.core.ipc.rpc.kafka.cfg
        kafkaConfig.putAll(kafkaConfigProvider.getProperties());
        LOG.info("initializing the Kafka producer with: {}", kafkaConfig);
        producer = Utils.runWithGivenClassLoader(() -> new KafkaProducer<String, byte[]>(kafkaConfig), KafkaProducer.class.getClassLoader());
        // Configurable cache config.
        String cacheConfig = kafkaConfig.getProperty("rpcid.cache.config", "maximumSize=1000,expireAfterWrite=10m");
        try {
            maxBufferSize = Integer.parseInt(kafkaConfig.getProperty("max.buffer.size", MAX_BUFFER_SIZE));
        } catch (NumberFormatException e) {
            maxBufferSize = Integer.parseInt(MAX_BUFFER_SIZE);
        }
        rpcIdCache = CacheBuilder.from(cacheConfig).build();
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void bind(RpcModule module) throws Exception {
        if (module != null) {
            final RpcModule<RpcRequest, RpcResponse> rpcModule = (RpcModule<RpcRequest, RpcResponse>) module;
            if (registerdModules.containsKey(rpcModule.getId())) {
                LOG.warn(" {} module is already registered", rpcModule.getId());
            } else {
                registerdModules.put(rpcModule.getId(), rpcModule);
                startConsumerForModule(rpcModule);
            }
        }
    }

    private void startConsumerForModule(RpcModule<RpcRequest, RpcResponse> rpcModule) {
        final JmsQueueNameFactory topicNameFactory = new JmsQueueNameFactory(KafkaRpcConstants.RPC_REQUEST_TOPIC_NAME, rpcModule.getId(),
                minionIdentity.getLocation());
        KafkaConsumer<String, byte[]> consumer = Utils.runWithGivenClassLoader(() -> new KafkaConsumer<>(kafkaConfig), KafkaConsumer.class.getClassLoader());
        KafkaConsumerRunner kafkaConsumerRunner = new KafkaConsumerRunner(rpcModule, consumer, topicNameFactory.getName());
        executor.execute(kafkaConsumerRunner);
        LOG.info("started kafka consumer for module : {}", rpcModule.getId());
        rpcModuleConsumers.put(rpcModule, kafkaConsumerRunner);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void unbind(RpcModule module) throws Exception {
        if (module != null) {
            final RpcModule<RpcRequest, RpcResponse> rpcModule = (RpcModule<RpcRequest, RpcResponse>) module;
            registerdModules.remove(rpcModule.getId());
            stopConsumerForModule(rpcModule);
        }
    }

    private void stopConsumerForModule(RpcModule<RpcRequest, RpcResponse> rpcModule) {
        KafkaConsumerRunner kafkaConsumerRunner  = rpcModuleConsumers.remove(rpcModule);
        LOG.info("stopped kafka consumer for module : {}", rpcModule.getId());
        kafkaConsumerRunner.shutdown();
    }

    public void destroy() {

    }


    private class KafkaConsumerRunner implements Runnable {

        private final KafkaConsumer<String, byte[]> consumer;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private String topic;
        private RpcModule<RpcRequest, RpcResponse> module;
        private Cache<String, ByteString> messageCache = CacheBuilder.from(MESSAGE_CACHE_CONFIG).build();

        public KafkaConsumerRunner(RpcModule<RpcRequest, RpcResponse> rpcModule, KafkaConsumer<String, byte[]> consumer, String topic) {
            this.consumer = consumer;
            this.topic = topic;
            this.module = rpcModule;
        }

        public void shutdown() {
            closed.set(true);
            consumer.wakeup();
        }

        @Override
        public void run() {
            try {
                consumer.subscribe(Arrays.asList(topic));
                LOG.info("subscribed to topic {}", topic);
                while (!closed.get()) {
                    ConsumerRecords<String, byte[]> records = consumer.poll(Long.MAX_VALUE);
                    for (ConsumerRecord<String, byte[]> record : records) {  
                        try {
                            RpcMessageProtos.RpcMessage rpcMessage = RpcMessageProtos.RpcMessage
                                                                          .parseFrom(record.value());
                            String rpcId = rpcMessage.getRpcId();
                            long expirationTime = rpcMessage.getExpirationTime();
                            if (expirationTime < System.currentTimeMillis()) {
                                LOG.debug("ttl already expired for the request id = {}, won't process.", rpcMessage.getRpcId());
                                continue;
                            }
                            boolean hasSystemId = rpcMessage.hasSystemId();
                            String minionId = getMinionIdentity().getId();
                            if (hasSystemId && !(minionId.equals(rpcMessage.getSystemId()))) {
                                // directed RPC and not directed at this minion
                                LOG.debug("MinionIdentity {} doesn't match with systemId {}, ignore the request", minionId, rpcMessage.getSystemId());
                                continue;
                            }
                            if (hasSystemId) {
                                String messageId = rpcId;
                                if(rpcMessage.getTotalChunks() > 1) {
                                    messageId = messageId + rpcMessage.getCurrentChunkNumber();
                                }
                                // directed RPC, there may be more than one request with same request Id, cache and allow only one.
                                Long cachedTime = rpcIdCache.getIfPresent(messageId);
                                if (cachedTime == null) {
                                    rpcIdCache.put(messageId, System.currentTimeMillis());
                                } else {
                                    continue;
                                }
                            }
                            ByteString rpcContent = rpcMessage.getRpcContent();
                            // For bigger messages which expand into multiple messages, total chunks would be defined.
                            if (rpcMessage.getTotalChunks() > 1) {
                                if (messageCache.getIfPresent(rpcId) != null) {
                                    ByteString concatedByteString = messageCache.getIfPresent(rpcId).concat(rpcMessage.getRpcContent());
                                    messageCache.put(rpcId, concatedByteString);
                                } else {
                                    messageCache.put(rpcId, rpcMessage.getRpcContent());
                                }
                                if (rpcMessage.getTotalChunks() != rpcMessage.getCurrentChunkNumber() + 1) {
                                    continue;
                                }
                                rpcContent = messageCache.getIfPresent(rpcId);
                                messageCache.invalidate(rpcId);
                            }
                            RpcRequest request = module.unmarshalRequest(rpcContent.toStringUtf8());
                            CompletableFuture<RpcResponse> future = module.execute(request);
                            future.whenComplete((res, ex) -> {
                                final RpcResponse response;
                                if (ex != null) {
                                    // An exception occurred, store the exception in a new response
                                    LOG.warn("An error occured while executing a call in {}.", module.getId(), ex);
                                    response = module.createResponseWithException(ex);
                                } else {
                                    // No exception occurred, use the given response
                                    response = res;
                                }

                                try {
                                    final JmsQueueNameFactory topicNameFactory = new JmsQueueNameFactory(KafkaRpcConstants.RPC_RESPONSE_TOPIC_NAME,
                                            module.getId());
                                    final String responseAsString = module.marshalResponse(response);
                                    final byte[] messageInBytes =  responseAsString.getBytes();
                                    if (messageInBytes.length > maxBufferSize) {
                                        int totalChunks = messageInBytes.length / maxBufferSize + (messageInBytes.length % maxBufferSize == 0 ? 0 : 1);
                                        int bytesSent = 0;
                                        // Divide the message in chunks and send each chunk as a different message with the same key.
                                        for (int chunk = 0; chunk < totalChunks; chunk++) {
                                            // buffer size would be equal to max buffer size except for the last chunk.
                                            int bufferSize = (messageInBytes.length - bytesSent > maxBufferSize) ? maxBufferSize : messageInBytes.length - bytesSent;
                                            ByteString byteString = ByteString.copyFrom(messageInBytes, chunk * maxBufferSize, bufferSize);
                                            RpcMessageProtos.RpcMessage rpcResponse = RpcMessageProtos.RpcMessage.newBuilder()
                                                    .setRpcId(rpcId)
                                                    .setTotalChunks(totalChunks)
                                                    .setCurrentChunkNumber(chunk)
                                                    .setRpcContent(byteString)
                                                    .build();
                                            final ProducerRecord<String, byte[]> producerRecord = new ProducerRecord<>(
                                                    topicNameFactory.getName(), rpcId, rpcResponse.toByteArray());
                                            int chunkNum = chunk;
                                            producer.send(producerRecord, (recordMetadata, e) -> {
                                                if (e != null) {
                                                    RATE_LIMITED_LOG.error(" RPC response {} with id {} couldn't be sent to Kafka", rpcResponse, rpcId, e);
                                                } else {
                                                    LOG.debug("request with id {} executed, sending response {} chunk num {} ", rpcId, chunkNum, responseAsString);
                                                }
                                            });
                                            bytesSent += maxBufferSize;

                                        }
                                    } else {
                                        RpcMessageProtos.RpcMessage rpcResponse = RpcMessageProtos.RpcMessage.newBuilder()
                                                .setRpcId(rpcId)
                                                .setRpcContent(ByteString.copyFromUtf8(responseAsString))
                                                .build();
                                        final ProducerRecord<String, byte[]> producerRecord = new ProducerRecord<>(
                                                topicNameFactory.getName(), rpcId, rpcResponse.toByteArray());
                                        producer.send(producerRecord, (recordMetadata, e) -> {
                                            if (e != null) {
                                                RATE_LIMITED_LOG.error(" RPC response {} with id {} couldn't be sent to Kafka", rpcResponse, rpcId, e);
                                            } else {
                                                LOG.debug("request with id {} executed, sending response {} ", rpcId,
                                                        responseAsString);
                                            }
                                        });
                                    }

                                } catch (Throwable t) {
                                    LOG.error("Marshalling response in RPC module {} failed.", module, t);
                                }
                            });
                        } catch (InvalidProtocolBufferException e) {
                             LOG.error("error while parsing the request", e);
                        }
                    }
                }
            } catch (WakeupException e) {
                // Ignore exception if closing
                if (!closed.get()) {
                    throw e;
                }
            } finally {
                consumer.close();
            }
        }

    }

    public MinionIdentity getMinionIdentity() {
        return minionIdentity;
    }

    public Cache<String, Long> getRpcIdCache() {
        return rpcIdCache;
    }
}
;