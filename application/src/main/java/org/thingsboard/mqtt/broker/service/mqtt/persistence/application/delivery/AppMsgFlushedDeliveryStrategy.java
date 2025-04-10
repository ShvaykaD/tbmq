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
package org.thingsboard.mqtt.broker.service.mqtt.persistence.application.delivery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.thingsboard.mqtt.broker.service.analysis.ClientLogger;
import org.thingsboard.mqtt.broker.service.mqtt.PublishMsgDeliveryService;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.application.processing.ApplicationSubmitStrategy;
import org.thingsboard.mqtt.broker.session.ClientSessionCtx;

/**
 * AppMsgDeliveryStrategy implementation that flushes the Netty channel
 * immediately after sending each message to the Application client.
 * <p>
 * This strategy prioritizes low-latency delivery over throughput, ensuring
 * that each message is pushed out as soon as it's written.
 */
@Component
@ConditionalOnProperty(prefix = "mqtt.persistent-session.app.persisted-messages", name = "write-and-flush", havingValue = "true", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class AppMsgFlushedDeliveryStrategy implements AppMsgDeliveryStrategy {

    private final PublishMsgDeliveryService publishMsgDeliveryService;
    private final ClientLogger clientLogger;

    @Override
    public void process(ApplicationSubmitStrategy submitStrategy, ClientSessionCtx clientSessionCtx) {
        if (log.isDebugEnabled()) {
            log.debug("[{}] Flushed delivery of messages from processing ctx: {}", clientSessionCtx.getClientId(), submitStrategy.getOrderedMessages());
        }
        submitStrategy.process(msg -> {
            switch (msg.getPacketType()) {
                case PUBLISH ->
                        publishMsgDeliveryService.sendPublishMsgToClientWithoutFlush(clientSessionCtx, msg.getPublishMsg());
                case PUBREL ->
                        publishMsgDeliveryService.sendPubRelMsgToClientWithoutFlush(clientSessionCtx, msg.getPacketId());
            }
            clientSessionCtx.getChannel().flush();
            clientLogger.logEvent(clientSessionCtx.getClientId(), this.getClass(), "Delivered msg to application client");
        });
    }

}
