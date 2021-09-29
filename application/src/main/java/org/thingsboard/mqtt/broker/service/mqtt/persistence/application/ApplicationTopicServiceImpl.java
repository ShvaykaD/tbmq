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
package org.thingsboard.mqtt.broker.service.mqtt.persistence.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.queue.TbQueueAdmin;
import org.thingsboard.mqtt.broker.queue.provider.ApplicationPersistenceMsgQueueFactory;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.application.util.MqttApplicationClientUtil;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationTopicServiceImpl implements ApplicationTopicService {
    private final TbQueueAdmin queueAdmin;
    private final ApplicationPersistenceMsgQueueFactory applicationPersistenceMsgQueueFactory;

    @Override
    public void createTopic(String clientId) {
        log.debug("[{}] Creating APPLICATION topic", clientId);
        String clientTopic = MqttApplicationClientUtil.getTopic(clientId);
        queueAdmin.createTopic(clientTopic, applicationPersistenceMsgQueueFactory.getTopicConfigs());
    }

    @Override
    public void deleteTopic(String clientId) {
        log.debug("[{}] Deleting APPLICATION topic", clientId);
        // TODO: delete consumer group as well
        String clientTopic = MqttApplicationClientUtil.getTopic(clientId);
        // TODO: add callback
        queueAdmin.deleteTopic(clientTopic);
    }
}