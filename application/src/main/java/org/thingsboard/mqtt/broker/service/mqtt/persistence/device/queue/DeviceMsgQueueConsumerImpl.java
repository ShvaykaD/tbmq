/**
 * Copyright © 2016-2020 The Thingsboard Authors
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

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.adaptor.ProtoConverter;
import org.thingsboard.mqtt.broker.cluster.ServiceInfoProvider;
import org.thingsboard.mqtt.broker.common.data.DevicePublishMsg;
import org.thingsboard.mqtt.broker.common.data.PersistedPacketType;
import org.thingsboard.mqtt.broker.common.util.ThingsBoardThreadFactory;
import org.thingsboard.mqtt.broker.dao.DbConnectionChecker;
import org.thingsboard.mqtt.broker.dao.messages.DeviceMsgService;
import org.thingsboard.mqtt.broker.gen.queue.QueueProtos;
import org.thingsboard.mqtt.broker.queue.TbQueueConsumer;
import org.thingsboard.mqtt.broker.queue.common.TbProtoQueueMsg;
import org.thingsboard.mqtt.broker.queue.provider.DevicePersistenceMsgQueueFactory;
import org.thingsboard.mqtt.broker.service.analysis.ClientLogger;
import org.thingsboard.mqtt.broker.service.mqtt.ClientSession;
import org.thingsboard.mqtt.broker.service.mqtt.client.session.ClientSessionReader;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.processing.DeviceAckStrategy;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.processing.DeviceMsgAcknowledgeStrategyFactory;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.processing.DevicePackProcessingContext;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.processing.DevicePacketIdAndSerialNumberService;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.processing.DeviceProcessingDecision;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.processing.PacketIdAndSerialNumber;
import org.thingsboard.mqtt.broker.service.processing.downlink.DownLinkProxy;
import org.thingsboard.mqtt.broker.service.stats.DeviceProcessorStats;
import org.thingsboard.mqtt.broker.service.stats.StatsManager;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceMsgQueueConsumerImpl implements DeviceMsgQueueConsumer {
    private final ExecutorService consumersExecutor = Executors.newCachedThreadPool(ThingsBoardThreadFactory.forName("device-persisted-msg-consumer"));
    private final List<TbQueueConsumer<TbProtoQueueMsg<QueueProtos.PublishMsgProto>>> consumers = new ArrayList<>();

    private volatile boolean stopped = false;

    @Value("${queue.device-persisted-msg.consumers-count}")
    private int consumersCount;
    @Value("${queue.device-persisted-msg.poll-interval}")
    private long pollDuration;
    @Value("${queue.device-persisted-msg.detect-msg-duplication:false}")
    private boolean detectMsgDuplication;

    private final DevicePersistenceMsgQueueFactory devicePersistenceMsgQueueFactory;
    private final DeviceMsgAcknowledgeStrategyFactory ackStrategyFactory;
    private final DeviceMsgService deviceMsgService;
    private final DevicePacketIdAndSerialNumberService serialNumberService;
    private final DownLinkProxy downLinkProxy;
    private final StatsManager statsManager;
    private final ServiceInfoProvider serviceInfoProvider;
    private final ClientSessionReader clientSessionReader;
    private final ClientLogger clientLogger;
    private final DbConnectionChecker dbConnectionChecker;


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

                    Set<String> clientIds = msgs.stream().map(TbProtoQueueMsg::getKey).collect(Collectors.toSet());
                    for (String clientId : clientIds) {
                        clientLogger.logEvent(clientId, "Start persisting DEVICE msg");
                    }

                    List<DevicePublishMsg> devicePublishMessages = toDevicePublishMsgs(msgs);
                    Map<String, PacketIdAndSerialNumber> lastPacketIdAndSerialNumbers = null;
                    boolean isDbConnected = dbConnectionChecker.isDbConnected()
                            && (lastPacketIdAndSerialNumbers = tryGetLastPacketIdAndSerialNumber(consumerId, clientIds)) != null;
                    if (isDbConnected) {
                        persistDeviceMsgs(devicePublishMessages, lastPacketIdAndSerialNumbers, consumerId, stats);;
                    }
                    try {
                        consumer.commitSync();
                    } catch (Exception e) {
                        log.warn("[{}] Failed to commit polled messages.", consumerId, e);
                    }
                    for (String clientId : clientIds) {
                        clientLogger.logEvent(clientId, "Finished persisting DEVICE msg");
                    }

                    for (DevicePublishMsg devicePublishMsg : devicePublishMessages) {
                        ClientSession clientSession = clientSessionReader.getClientSession(devicePublishMsg.getClientId());
                        if (clientSession == null) {
                            log.debug("[{}] Client session not found for persisted msg.", devicePublishMsg.getClientId());
                        } else if (!clientSession.isConnected()) {
                            // TODO: think if it's OK to ignore msg if session is 'disconnected'
                            log.trace("[{}] Client session is disconnected.", devicePublishMsg.getClientId());
                        } else {
                            String targetServiceId = clientSession.getSessionInfo().getServiceId();
                            if (isDbConnected) {
                                downLinkProxy.sendPersistentMsg(targetServiceId, devicePublishMsg.getClientId(), ProtoConverter.toDevicePublishMsgProto(devicePublishMsg));
                            } else {
                                downLinkProxy.sendBasicMsg(targetServiceId, devicePublishMsg.getClientId(), ProtoConverter.convertToPublishProtoMessage(devicePublishMsg));
                            }
                        }
                    }
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

    private void persistDeviceMsgs(List<DevicePublishMsg> devicePublishMessages, Map<String, PacketIdAndSerialNumber> lastPacketIdAndSerialNumbers, String consumerId, DeviceProcessorStats stats) {
        setPacketIdAndSerialNumber(devicePublishMessages, lastPacketIdAndSerialNumbers);

        DeviceAckStrategy ackStrategy = ackStrategyFactory.newInstance(consumerId);
        DevicePackProcessingContext ctx = new DevicePackProcessingContext(devicePublishMessages, detectMsgDuplication);
        while (!stopped) {
            try {
                // TODO: think if we need transaction here
                // TODO: think about case when client is 'clearing session' at this moment
                serialNumberService.saveLastSerialNumbers(lastPacketIdAndSerialNumbers);
                deviceMsgService.save(devicePublishMessages, ctx.detectMsgDuplication());
                ctx.onSuccess();
            } catch (DuplicateKeyException e) {
                log.warn("[{}] Duplicate serial number detected, will save with rewrite, detailed error - {}", consumerId, e.getMessage());
                ctx.disableMsgDuplicationDetection();
            } catch (Exception e) {
                log.warn("[{}] Failed to save device messages. Exception - {}, reason - {}.", consumerId, e.getClass().getSimpleName(), e.getMessage());
            }

            DeviceProcessingDecision decision = ackStrategy.analyze(ctx);

            stats.log(devicePublishMessages.size(), ctx.isSuccessful(), decision.isCommit());

            if (decision.isCommit()) {
                break;
            }
        }
    }

    private void setPacketIdAndSerialNumber(List<DevicePublishMsg> devicePublishMessages, Map<String, PacketIdAndSerialNumber> lastPacketIdAndSerialNumbers) {
        for (DevicePublishMsg devicePublishMessage : devicePublishMessages) {
            PacketIdAndSerialNumberDto packetIdAndSerialNumberDto = getAndIncrementPacketIdAndSerialNumberDto(lastPacketIdAndSerialNumbers, devicePublishMessage.getClientId());
            devicePublishMessage.setPacketId(packetIdAndSerialNumberDto.getPacketId());
            devicePublishMessage.setSerialNumber(packetIdAndSerialNumberDto.getSerialNumber());
        }
    }

    private Map<String, PacketIdAndSerialNumber> tryGetLastPacketIdAndSerialNumber(String consumerId, Set<String> clientIds) {
        try {
            return serialNumberService.getLastPacketIdAndSerialNumber(clientIds);
        } catch (DataAccessResourceFailureException e) {
            log.warn("[{}] Failed to connect to database", consumerId);
            return null;
        }
    }

    private PacketIdAndSerialNumberDto getAndIncrementPacketIdAndSerialNumberDto(Map<String, PacketIdAndSerialNumber> lastPacketIdAndSerialNumbers, String clientId) {
        PacketIdAndSerialNumber packetIdAndSerialNumber = lastPacketIdAndSerialNumbers.computeIfAbsent(clientId, id ->
                new PacketIdAndSerialNumber(new AtomicInteger(1), new AtomicLong(0)));
        AtomicInteger packetIdAtomic = packetIdAndSerialNumber.getPacketId();
        packetIdAtomic.incrementAndGet();
        packetIdAtomic.compareAndSet(0xffff, 1);
        return new PacketIdAndSerialNumberDto(packetIdAtomic.get(), packetIdAndSerialNumber.getSerialNumber().incrementAndGet());
    }

    @Getter
    @AllArgsConstructor
    private static class PacketIdAndSerialNumberDto {
        private final int packetId;
        private final long serialNumber;
    }
    private List<DevicePublishMsg> toDevicePublishMsgs(List<TbProtoQueueMsg<QueueProtos.PublishMsgProto>> msgs) {
        return msgs.stream()
                .map(protoMsg -> ProtoConverter.toDevicePublishMsg(protoMsg.getKey(), protoMsg.getValue()))
                .map(devicePublishMsg -> devicePublishMsg.toBuilder()
                        .packetId(-1)
                        .serialNumber(-1L)
                        .packetType(PersistedPacketType.PUBLISH)
                        .time(System.currentTimeMillis())
                        .build())
                .collect(Collectors.toList());
    }

    @PreDestroy
    public void destroy() {
        stopped = true;
        consumers.forEach(TbQueueConsumer::unsubscribeAndClose);
        consumersExecutor.shutdownNow();
    }

}
