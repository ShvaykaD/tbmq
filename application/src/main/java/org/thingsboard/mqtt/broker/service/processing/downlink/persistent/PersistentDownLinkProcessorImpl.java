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
package org.thingsboard.mqtt.broker.service.processing.downlink.persistent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.common.data.DevicePublishMsg;
import org.thingsboard.mqtt.broker.service.analysis.ClientLogger;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.DeviceActorManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class PersistentDownLinkProcessorImpl implements PersistentDownLinkProcessor {

    private final DeviceActorManager deviceActorManager;
    private final ClientLogger clientLogger;

    @Override
    public void process(String clientId, DevicePublishMsg msg) {
        deviceActorManager.notifyPublishMsg(clientId, msg);
        clientLogger.logEvent(clientId, this.getClass(), "Sent msg to device client actor");
    }
}
