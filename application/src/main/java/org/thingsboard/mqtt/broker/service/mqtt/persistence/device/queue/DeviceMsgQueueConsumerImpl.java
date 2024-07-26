/**
 * Copyright © 2016-2024 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.mqtt.broker.service.mqtt.persistence.device.queue;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.adaptor.ProtoConverter;
import org.thingsboard.mqtt.broker.cluster.ServiceInfoProvider;
import org.thingsboard.mqtt.broker.common.data.DevicePublishMsg;
import org.thingsboard.mqtt.broker.common.util.ThingsBoardExecutors;
import org.thingsboard.mqtt.broker.gen.queue.QueueProtos;
import org.thingsboard.mqtt.broker.queue.TbQueueConsumer;
import org.thingsboard.mqtt.broker.queue.common.TbProtoQueueMsg;
import org.thingsboard.mqtt.broker.queue.provider.DevicePersistenceMsgQueueFactory;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.processing.ClientIdMessagesPack;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.processing.DefaultClientIdPersistedMsgsCallback;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.processing.DeviceAckStrategy;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.processing.DeviceMsgAcknowledgeStrategyFactory;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.processing.DeviceMsgPersistenceSubmitStrategyFactory;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.processing.DeviceMsgProcessor;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.processing.DevicePackProcessingContext;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.processing.DevicePackProcessingResult;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.processing.DeviceProcessingDecision;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.processing.DeviceSubmitStrategy;
import org.thingsboard.mqtt.broker.service.stats.DeviceProcessorStats;
import org.thingsboard.mqtt.broker.service.stats.StatsManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceMsgQueueConsumerImpl implements DeviceMsgQueueConsumer {

    private final List<TbQueueConsumer<TbProtoQueueMsg<QueueProtos.PublishMsgProto>>> consumers = new ArrayList<>();

    private final DevicePersistenceMsgQueueFactory devicePersistenceMsgQueueFactory;
    private final DeviceMsgAcknowledgeStrategyFactory ackStrategyFactory;
    private final DeviceMsgPersistenceSubmitStrategyFactory submitStrategyFactory;
    private final DeviceMsgProcessor deviceMsgProcessor;
    private final StatsManager statsManager;
    private final ServiceInfoProvider serviceInfoProvider;

    @Value("${queue.device-persisted-msg.consumers-count}")
    private int consumersCount;
    @Value("${queue.device-persisted-msg.poll-interval}")
    private long pollDuration;
    @Value("${queue.device-persisted-msg.threads-count}")
    private int threadsCount;
    @Value("${queue.device-persisted-msg.pack-processing-timeout}")
    private long packProcessingTimeout;

    private volatile boolean stopped = false;
    private ExecutorService consumersExecutor;

    @PostConstruct
    public void init() {
        this.consumersExecutor = ThingsBoardExecutors.initExecutorService(threadsCount, "device-persisted-msg-consumer");
    }

    @Override
    public void startConsuming() {
        for (int i = 0; i < consumersCount; i++) {
            String consumerId = serviceInfoProvider.getServiceId() + "-" + i;
            TbQueueConsumer<TbProtoQueueMsg<QueueProtos.PublishMsgProto>> consumer = devicePersistenceMsgQueueFactory.createConsumer(consumerId);
            consumers.add(consumer);
            consumer.subscribe();
            launchConsumer(consumerId, consumer);
        }
    }

    private void launchConsumer(String consumerId, TbQueueConsumer<TbProtoQueueMsg<QueueProtos.PublishMsgProto>> consumer) {
        DeviceProcessorStats stats = statsManager.createDeviceProcessorStats(consumerId);
        consumersExecutor.submit(() -> {
            while (!stopped) {
                try {
                    // TODO: corner case: if Kafka rebalances partitions while node is processing - multiple nodes can persist same msg multiple times
                    List<TbProtoQueueMsg<QueueProtos.PublishMsgProto>> msgs = consumer.poll(pollDuration);
                    if (msgs.isEmpty()) {
                        continue;
                    }

                    Map<String, List<DevicePublishMsg>> deliveryMap = new HashMap<>();

                    DeviceAckStrategy ackStrategy = ackStrategyFactory.newInstance(consumerId);
                    DeviceSubmitStrategy submitStrategy = submitStrategyFactory.newInstance(consumerId);

                    var clientIdToMsgsMap = toClientIdMsgsMap(msgs);
                    submitStrategy.init(clientIdToMsgsMap);

                    long packProcessingStart = System.nanoTime();
                    while (!stopped) {
                        var ctx = new DevicePackProcessingContext(submitStrategy.getPendingMap());
                        int totalMessagesCount = 0;
                        if (statsManager.isEnabled()) {
                            totalMessagesCount = ctx.getPendingMap().values().stream()
                                    .mapToInt(pack -> pack.messages().size())
                                    .sum();
                        }
                        submitStrategy.process(clientIdMessagesPack -> {
                            long clientIdPackProcessingStart = System.nanoTime();
                            var callback = new DefaultClientIdPersistedMsgsCallback(clientIdMessagesPack.clientId(), ctx);
                            deviceMsgProcessor.persistClientDeviceMessages(clientIdMessagesPack, callback);
                            stats.logClientIdPackProcessingTime(System.nanoTime() - clientIdPackProcessingStart, TimeUnit.NANOSECONDS);
                        });

                        if (!stopped) {
                            ctx.await(packProcessingTimeout, TimeUnit.MILLISECONDS);
                        }
                        var result = new DevicePackProcessingResult(ctx);
                        deliveryMap.putAll(result.getSuccessMap());
                        ctx.cleanup();
                        DeviceProcessingDecision decision = ackStrategy.analyze(result);
                        stats.log(totalMessagesCount, result, decision.commit());

                        if (decision.commit()) {
                            consumer.commitSync();
                            break;
                        } else {
                            submitStrategy.update(decision.reprocessMap());
                        }
                    }
                    stats.logClientIdPacksProcessingTime(msgs.size(), System.nanoTime() - packProcessingStart, TimeUnit.NANOSECONDS);

                    deliveryMap.forEach(deviceMsgProcessor::deliverClientDeviceMessages);
                } catch (Exception e) {
                    if (!stopped) {
                        log.error("[{}] Failed to process messages from queue.", consumerId, e);
                        try {
                            Thread.sleep(pollDuration);
                        } catch (InterruptedException e2) {
                            log.trace("[{}] Failed to wait until the server has capacity to handle new requests", consumerId, e2);
                        }
                    }
                }
            }
            log.info("[{}] Device Persisted Msg Consumer stopped.", consumerId);
        });
    }

    @PreDestroy
    public void destroy() {
        stopped = true;
        consumers.forEach(TbQueueConsumer::unsubscribeAndClose);
        if (consumersExecutor != null) {
            consumersExecutor.shutdownNow();
        }
    }

    private Map<String, ClientIdMessagesPack> toClientIdMsgsMap(List<TbProtoQueueMsg<QueueProtos.PublishMsgProto>> msgs) {
        var clientIdMessagesPackMap = new HashMap<String, ClientIdMessagesPack>();
        for (var msg : msgs) {
            String clientId = msg.getKey();
            clientIdMessagesPackMap
                    .computeIfAbsent(clientId, k -> new ClientIdMessagesPack(clientId, new ArrayList<>()))
                    .messages().add(ProtoConverter.protoToDevicePublishMsg(msg.getKey(), msg.getValue(), msg.getHeaders()));
        }
        return clientIdMessagesPackMap;
    }

}
