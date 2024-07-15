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
package org.thingsboard.mqtt.broker.service.mqtt.persistence.device.processing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.adaptor.ProtoConverter;
import org.thingsboard.mqtt.broker.common.data.ClientSessionInfo;
import org.thingsboard.mqtt.broker.common.data.DevicePublishMsg;
import org.thingsboard.mqtt.broker.common.util.BrokerConstants;
import org.thingsboard.mqtt.broker.dao.messages.DeviceMsgCacheService;
import org.thingsboard.mqtt.broker.gen.queue.QueueProtos.PublishMsgProto;
import org.thingsboard.mqtt.broker.queue.common.TbProtoQueueMsg;
import org.thingsboard.mqtt.broker.service.analysis.ClientLogger;
import org.thingsboard.mqtt.broker.service.mqtt.client.session.ClientSessionCache;
import org.thingsboard.mqtt.broker.service.processing.downlink.DownLinkProxy;
import org.thingsboard.mqtt.broker.service.stats.DeviceProcessorStats;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "device.persistence.messages.storage", value = "type", havingValue = "redis")
@RequiredArgsConstructor
public class RedisDeviceMsgProcessorImpl implements DeviceMsgProcessor {

    private final ClientSessionCache clientSessionCache;
    private final ClientLogger clientLogger;
    private final DownLinkProxy downLinkProxy;
    private final DeviceMsgAcknowledgeStrategyFactory ackStrategyFactory;
    private final DeviceMsgCacheService deviceMsgCacheService;

    @Override
    public List<DevicePublishMsg> persistMessages(List<TbProtoQueueMsg<PublishMsgProto>> messages, DeviceProcessorStats stats, String consumerId) {
        Set<String> clientIds = messages.stream().map(TbProtoQueueMsg::getKey).collect(Collectors.toSet());
        for (String clientId : clientIds) {
            clientLogger.logEvent(clientId, this.getClass(), "[Redis] Start persisting DEVICE msg");
        }

        List<DevicePublishMsg> devicePublishMessages = toDevicePublishMsgs(messages);

        persistDeviceMsgs(devicePublishMessages, consumerId, stats);

        for (String clientId : clientIds) {
            clientLogger.logEvent(clientId, this.getClass(), "Finished persisting DEVICE msg");
        }
        return devicePublishMessages;
    }

    @Override
    public void deliverMessages(List<DevicePublishMsg> devicePublishMessages) {
        for (DevicePublishMsg devicePublishMsg : devicePublishMessages) {
            ClientSessionInfo clientSessionInfo = clientSessionCache.getClientSessionInfo(devicePublishMsg.getClientId());
            if (clientSessionInfo == null) {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Client session is not found for persisted messages.", devicePublishMsg.getClientId());
                }
            } else if (!clientSessionInfo.isConnected()) {
                if (log.isTraceEnabled()) {
                    log.trace("[{}] Client session is disconnected.", devicePublishMsg.getClientId());
                }
            } else {
                String targetServiceId = clientSessionInfo.getServiceId();
                if (messageWasPersisted(devicePublishMsg)) {
                    downLinkProxy.sendPersistentMsg(
                            targetServiceId,
                            devicePublishMsg.getClientId(),
                            devicePublishMsg);
                } else {
                    downLinkProxy.sendBasicMsg(
                            targetServiceId,
                            devicePublishMsg.getClientId(),
                            ProtoConverter.convertToPublishMsgProto(devicePublishMsg));
                }
            }
        }
    }

    private boolean messageWasPersisted(DevicePublishMsg devicePublishMsg) {
        return !devicePublishMsg.getPacketId().equals(BrokerConstants.BLANK_PACKET_ID) && !devicePublishMsg.getSerialNumber().equals(BrokerConstants.BLANK_SERIAL_NUMBER);
    }

    private void persistDeviceMsgs(List<DevicePublishMsg> devicePublishMessages,
                                   String consumerId, DeviceProcessorStats stats) {
        DeviceAckStrategy ackStrategy = ackStrategyFactory.newInstance(consumerId);
        DevicePackProcessingContext ctx = new DevicePackProcessingContext(devicePublishMessages);
        while (!Thread.interrupted()) {
            try {
                deviceMsgCacheService.save(devicePublishMessages, ctx.detectMsgDuplication());
                ctx.onSuccess();
            } catch (DuplicateKeyException e) {
                log.warn("[{}] Duplicate serial number detected, will save with rewrite", consumerId, e);
                ctx.disableMsgDuplicationDetection();
            } catch (Exception e) {
                log.warn("[{}] Failed to save device messages", consumerId, e);
            }

            DeviceProcessingDecision decision = ackStrategy.analyze(ctx);

            stats.log(devicePublishMessages.size(), ctx.isSuccessful(), decision.isCommit());

            if (decision.isCommit()) {
                break;
            }
        }
    }

    private List<DevicePublishMsg> toDevicePublishMsgs(List<TbProtoQueueMsg<PublishMsgProto>> msgs) {
        return msgs.stream()
                .map(protoMsg -> ProtoConverter.protoToDevicePublishMsg(protoMsg.getKey(), protoMsg.getValue(), protoMsg.getHeaders()))
                .collect(Collectors.toList());
    }
}