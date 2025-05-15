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
package org.thingsboard.mqtt.broker.service.notification;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.queue.TbQueueAdmin;
import org.thingsboard.mqtt.broker.queue.kafka.settings.InternodeNotificationsKafkaSettings;

import java.util.List;

import static org.thingsboard.mqtt.broker.common.data.BrokerConstants.DOT;

@Service
@RequiredArgsConstructor
public class InternodeNotificationsHelperImpl implements InternodeNotificationsHelper {

    private final InternodeNotificationsKafkaSettings internodeNotificationsKafkaSettings;
    private final TbQueueAdmin queueAdmin;

    @Value("${queue.kafka.kafka-prefix:}")
    private String kafkaPrefix;

    private String topicPrefix;

    @PostConstruct
    public void init() {
        topicPrefix = kafkaPrefix + internodeNotificationsKafkaSettings.getTopicPrefix() + DOT;
    }

    @Override
    public String getServiceTopic(String serviceId) {
        return topicPrefix + serviceId;
    }

    @Override
    public List<String> getServiceIds() {
        return queueAdmin.getBrokerServiceIds();
    }

}
