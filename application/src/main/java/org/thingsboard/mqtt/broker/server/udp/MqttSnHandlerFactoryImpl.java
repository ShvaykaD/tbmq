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
package org.thingsboard.mqtt.broker.server.udp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.service.historical.stats.TbMessageStatsReportClient;

@Service
@RequiredArgsConstructor
public class MqttSnHandlerFactoryImpl implements MqttSnHandlerFactory {

    private final MqttSnHandlerCtx mqttSnHandlerCtx;

    @Override
    public MqttSnSessionHandler create(String initializerName) {
        return new MqttSnSessionHandler(mqttSnHandlerCtx, initializerName);
    }

    @Override
    public TbMessageStatsReportClient getTbMessageStatsReportClient() {
        return null;
    }
}
