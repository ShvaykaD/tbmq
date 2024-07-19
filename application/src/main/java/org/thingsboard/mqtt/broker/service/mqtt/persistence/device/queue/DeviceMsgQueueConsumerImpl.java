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
import org.thingsboard.mqtt.broker.cluster.ServiceInfoProvider;
import org.thingsboard.mqtt.broker.common.util.ThingsBoardExecutors;
import org.thingsboard.mqtt.broker.gen.queue.QueueProtos;
import org.thingsboard.mqtt.broker.queue.TbQueueConsumer;
import org.thingsboard.mqtt.broker.queue.common.TbProtoQueueMsg;
import org.thingsboard.mqtt.broker.queue.provider.DevicePersistenceMsgQueueFactory;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.newprocessing.ClientIdMessagesPack;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.newprocessing.DefaultClientIdPersistedMsgsCallback;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.newprocessing.NewDeviceAckStrategy;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.newprocessing.NewDeviceMsgPersistenceAckStrategyFactory;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.newprocessing.NewDeviceMsgPersistenceSubmitStrategyFactory;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.newprocessing.NewDevicePackProcessingContext;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.newprocessing.NewDevicePackProcessingResult;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.newprocessing.NewDeviceProcessingDecision;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.newprocessing.NewDeviceSubmitStrategy;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.processing.DeviceMsgProcessor;
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
    private final NewDeviceMsgPersistenceAckStrategyFactory ackStrategyFactory;
    private final NewDeviceMsgPersistenceSubmitStrategyFactory submitStrategyFactory;
    private final DeviceMsgProcessor deviceMsgProcessor;
    private final StatsManager statsManager;
    private final ServiceInfoProvider serviceInfoProvider;

    @Value("${queue.device-persisted-msg.consumers-count}")
    private int consumersCount;
    @Value("${queue.device-persisted-msg.poll-interval}")
    private long pollDuration;
    @Value("${queue.device-persisted-msg.threads-count}")
    private int threadsCount;

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


                    NewDeviceAckStrategy ackStrategy = ackStrategyFactory.newInstance(consumerId);
                    NewDeviceSubmitStrategy submitStrategy = submitStrategyFactory.newInstance(consumerId);

                    var clientIdToMsgsMap = toClientIdMsgsMap(msgs);
                    submitStrategy.init(clientIdToMsgsMap);

                    long packProcessingStart = System.nanoTime();
                    while (!stopped) {
                        var ctx = new NewDevicePackProcessingContext(submitStrategy.getPendingMap());
                        // TODO: for statistics we probably need to capture total messages count instead of clientId packs
                        int totalPacksCount = ctx.getPendingMap().size();
                        submitStrategy.process(clientIdMessagesPack -> {
                            long clientIdMessagesPackProcessingStart = System.nanoTime();
                            var callback = new DefaultClientIdPersistedMsgsCallback(clientIdMessagesPack.clientId(), ctx);
                            deviceMsgProcessor.persistAndDeliverClientDeviceMessages(clientIdMessagesPack, callback);
                            // TODO: no such method in the DeviceProcessorStats.
//                            stats.logMsgProcessingTime(System.nanoTime() - msgProcessingStart, TimeUnit.NANOSECONDS);
                        });

                        if (!stopped) {
                            // TODO: update yml configuration to define packProcessingTimeout
                            ctx.await(2000, TimeUnit.MILLISECONDS);
                        }
                        var result = new NewDevicePackProcessingResult(ctx);
                        ctx.cleanup();
                        NewDeviceProcessingDecision decision = ackStrategy.analyze(result);
                        // TODO: update with correct parameters
                        stats.log(totalPacksCount, true, decision.isCommit());

                        if (decision.isCommit()) {
                            consumer.commitSync();
                            break;
                        } else {
                            submitStrategy.update(decision.getReprocessMap());
                        }
                    }
                    // TODO: no such method in the DeviceProcessorStats.
                    // stats.logPackProcessingTime(msgs.size(), System.nanoTime() - packProcessingStart, TimeUnit.NANOSECONDS);

//                    try {
//                        consumer.commitSync();
//                    } catch (Exception e) {
//                        log.warn("[{}] Failed to commit polled messages.", consumerId, e);
//                    }
                    // TODO: We need to deliver messages that was sucessfully persisted to db.
                    // deviceMsgProcessor.deliverMessages(devicePublishMessages);
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
                    .messages().add(msg);
        }
        return clientIdMessagesPackMap;
    }

}
