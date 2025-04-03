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
package org.thingsboard.mqtt.broker.service.integration;

import org.thingsboard.mqtt.broker.common.data.exception.ThingsboardException;
import org.thingsboard.mqtt.broker.common.data.integration.Integration;
import org.thingsboard.mqtt.broker.gen.integration.IntegrationEventProto;
import org.thingsboard.mqtt.broker.gen.queue.ServiceInfo;

public interface PlatformIntegrationService {

    void processIntegrationUpdate(Integration integration, boolean created);

    void processIntegrationDelete(Integration integration, boolean removed);

    void processIntegrationRestart(Integration integration) throws ThingsboardException;

    void processUplinkData(IntegrationEventProto data, IntegrationApiCallback integrationApiCallback);

    void processServiceInfo(ServiceInfo serviceInfo);

    void updateSubscriptions(Integration integration);

    void removeSubscriptions(String integrationId);
}
