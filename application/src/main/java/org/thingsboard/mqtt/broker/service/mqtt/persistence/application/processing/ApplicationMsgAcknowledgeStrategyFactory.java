/**
 * Copyright © 2016-2025 The Thingsboard Authors
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
package org.thingsboard.mqtt.broker.service.mqtt.persistence.application.processing;

import com.google.common.collect.Maps;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApplicationMsgAcknowledgeStrategyFactory {

    private final ApplicationAckStrategyConfiguration ackStrategyConfiguration;

    public ApplicationAckStrategy newInstance(String clientId) {
        return switch (ackStrategyConfiguration.getType()) {
            case SKIP_ALL -> new SkipStrategy(clientId);
            case RETRY_ALL -> new RetryStrategy(clientId, ackStrategyConfiguration.getRetries());
        };
    }

    @RequiredArgsConstructor
    private static class SkipStrategy implements ApplicationAckStrategy {

        private final String clientId;

        @Override
        public ApplicationProcessingDecision analyze(ApplicationPackProcessingResult result) {
            var publishPendingMsgMap = result.getPublishPendingMap();
            var pubRelPendingMsgMap = result.getPubRelPendingMap();
            if (!publishPendingMsgMap.isEmpty() || !pubRelPendingMsgMap.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Skip reprocess for {} PUBLISH and {} PUBREL timeout messages.", clientId, publishPendingMsgMap.size(), pubRelPendingMsgMap);
                }
            }
            if (log.isTraceEnabled()) {
                publishPendingMsgMap.forEach((packetId, msg) ->
                        log.trace("[{}] Timeout PUBLISH message: topic - {}, packetId - {}.",
                                clientId, msg.getPublishMsg().getTopicName(), msg.getPacketId())
                );
                pubRelPendingMsgMap.forEach((packetId, msg) ->
                        log.trace("[{}] Timeout PUBREL message: packetId - {}.",
                                clientId, msg.getPacketId())
                );
            }
            return new ApplicationProcessingDecision(true, Collections.emptyMap());
        }
    }

    @RequiredArgsConstructor
    private static class RetryStrategy implements ApplicationAckStrategy {

        private final String clientId;
        private final int maxRetries;

        private int retryCount;

        @Override
        public ApplicationProcessingDecision analyze(ApplicationPackProcessingResult result) {
            var publishPendingMsgMap = result.getPublishPendingMap();
            var pubRelPendingMsgMap = result.getPubRelPendingMap();
            if (publishPendingMsgMap.isEmpty() && pubRelPendingMsgMap.isEmpty()) {
                return new ApplicationProcessingDecision(true, Collections.emptyMap());
            }
            if (maxRetries != 0 && ++retryCount > maxRetries) {
                log.debug("[{}] Skip reprocess due to max retries.", clientId);
                return new ApplicationProcessingDecision(true, Collections.emptyMap());
            }
            if (log.isDebugEnabled()) {
                log.debug("[{}] Going to reprocess {} PUBLISH and {} PUBREL messages", clientId, publishPendingMsgMap.size(), pubRelPendingMsgMap.size());
            }
            if (log.isTraceEnabled()) {
                publishPendingMsgMap.forEach((packetId, msg) ->
                        log.trace("[{}] Going to reprocess PUBLISH message: topic - {}, packetId - {}.",
                                clientId, msg.getPublishMsg().getTopicName(), msg.getPacketId())
                );
                pubRelPendingMsgMap.forEach((packetId, msg) ->
                        log.trace("[{}] Going to reprocess PUBREL message: packetId - {}.",
                                clientId, msg.getPacketId())
                );
            }
            var dupPublishPendingMap = publishPendingMsgMap
                    .values().stream()
                    .map(ApplicationMsgAcknowledgeStrategyFactory::getDupMsg)
                    .collect(Collectors.toMap(PersistedPublishMsg::getPacketId, Function.identity()));

            Map<Integer, PersistedMsg> pendingMsgMap = Maps.newHashMapWithExpectedSize(dupPublishPendingMap.size() + pubRelPendingMsgMap.size());

            pendingMsgMap.putAll(dupPublishPendingMap);
            pendingMsgMap.putAll(pubRelPendingMsgMap);
            return new ApplicationProcessingDecision(false, pendingMsgMap);
        }
    }

    private static PersistedPublishMsg getDupMsg(PersistedPublishMsg persistedPublishMsg) {
        return persistedPublishMsg
                .toBuilder()
                .publishMsg(persistedPublishMsg
                        .getPublishMsg()
                        .toBuilder()
                        .isDup(true)
                        .build())
                .build();
    }
}
