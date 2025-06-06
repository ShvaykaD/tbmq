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
package org.thingsboard.mqtt.broker.queue.provider;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.thingsboard.mqtt.broker.gen.queue.PublishMsgProto;
import org.thingsboard.mqtt.broker.queue.TbQueueControlledOffsetConsumer;
import org.thingsboard.mqtt.broker.queue.TbQueueProducer;
import org.thingsboard.mqtt.broker.queue.common.TbProtoQueueMsg;
import org.thingsboard.mqtt.broker.queue.kafka.TbKafkaConsumerTemplate;
import org.thingsboard.mqtt.broker.queue.kafka.TbKafkaProducerTemplate;
import org.thingsboard.mqtt.broker.queue.kafka.settings.DevicePersistenceMsgKafkaSettings;
import org.thingsboard.mqtt.broker.queue.util.QueueUtil;

import java.util.Map;
import java.util.Properties;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaDevicePersistenceMsgQueueFactory extends AbstractQueueFactory implements DevicePersistenceMsgQueueFactory {

    private final DevicePersistenceMsgKafkaSettings devicePersistenceMsgSettings;

    private Map<String, String> topicConfigs;

    @PostConstruct
    public void init() {
        this.topicConfigs = QueueUtil.getConfigs(devicePersistenceMsgSettings.getTopicProperties());
    }

    @Override
    public TbQueueProducer<TbProtoQueueMsg<PublishMsgProto>> createProducer() {
        TbKafkaProducerTemplate.TbKafkaProducerTemplateBuilder<TbProtoQueueMsg<PublishMsgProto>> producerBuilder = TbKafkaProducerTemplate.builder();
        producerBuilder.properties(producerSettings.toProps(devicePersistenceMsgSettings.getAdditionalProducerConfig()));
        producerBuilder.clientId(kafkaPrefix + "device-persisted-msg-producer");
        producerBuilder.defaultTopic(devicePersistenceMsgSettings.getKafkaTopic());
        producerBuilder.topicConfigs(topicConfigs);
        producerBuilder.admin(queueAdmin);
        producerBuilder.statsManager(producerStatsManager);
        return producerBuilder.build();
    }

    @Override
    public TbQueueControlledOffsetConsumer<TbProtoQueueMsg<PublishMsgProto>> createConsumer(String id) {
        TbKafkaConsumerTemplate.TbKafkaConsumerTemplateBuilder<TbProtoQueueMsg<PublishMsgProto>> consumerBuilder = TbKafkaConsumerTemplate.builder();

        Properties props = consumerSettings.toProps(devicePersistenceMsgSettings.getKafkaTopic(), devicePersistenceMsgSettings.getAdditionalConsumerConfig());
        QueueUtil.overrideProperties("DeviceMsgQueue-" + id, props, requiredConsumerProperties);
        consumerBuilder.properties(props);

        consumerBuilder.topic(devicePersistenceMsgSettings.getKafkaTopic());
        consumerBuilder.topicConfigs(topicConfigs);
        consumerBuilder.clientId(kafkaPrefix + "device-persisted-msg-consumer-" + id);
        consumerBuilder.groupId(kafkaPrefix + "device-persisted-msg-consumer-group");
        consumerBuilder.decoder(msg -> new TbProtoQueueMsg<>(msg.getKey(), PublishMsgProto.parseFrom(msg.getData()), msg.getHeaders()));
        consumerBuilder.admin(queueAdmin);
        consumerBuilder.autoCommit(false);
        consumerBuilder.statsService(consumerStatsService);
        consumerBuilder.statsManager(consumerStatsManager);
        return consumerBuilder.build();
    }
}
