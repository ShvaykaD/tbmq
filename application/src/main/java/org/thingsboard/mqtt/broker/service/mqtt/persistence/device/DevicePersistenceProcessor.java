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
package org.thingsboard.mqtt.broker.service.mqtt.persistence.device;

import org.thingsboard.mqtt.broker.service.subscription.shared.TopicSharedSubscription;
import org.thingsboard.mqtt.broker.session.ClientSessionCtx;

import java.util.Set;

public interface DevicePersistenceProcessor {

    void startProcessingPersistedMessages(ClientSessionCtx clientSessionCtx);

    void startProcessingSharedSubscriptions(ClientSessionCtx clientSessionCtx, Set<TopicSharedSubscription> subscriptions);

    void stopProcessingPersistedMessages(String clientId);

    void clearPersistedMsgs(String clientId);

    void processPubAck(String clientId, int packetId);

    void processPubRec(String clientId, int packetId);

    void processPubRecNoPubRelDelivery(String clientId, int packetId);

    void processPubComp(String clientId, int packetId);

    void processChannelWritable(String clientId);

    void processChannelNonWritable(String clientId);
}
