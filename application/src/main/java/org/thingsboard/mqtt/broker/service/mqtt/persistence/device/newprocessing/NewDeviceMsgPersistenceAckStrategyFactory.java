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
package org.thingsboard.mqtt.broker.service.mqtt.persistence.device.newprocessing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Slf4j
@Component
@RequiredArgsConstructor
public class NewDeviceMsgPersistenceAckStrategyFactory {

    private final NewDeviceAckStrategyConfiguration ackStrategyConfiguration;

    public NewDeviceAckStrategy newInstance(String consumerId) {
        return switch (ackStrategyConfiguration.getType()) {
            case SKIP_ALL -> new NewSkipStrategy(consumerId);
            case RETRY_ALL -> new NewRetryStrategy(consumerId, ackStrategyConfiguration);
            default ->
                    throw new RuntimeException("AckStrategy with type " + ackStrategyConfiguration.getType() + " is not supported!");
        };
    }

    @RequiredArgsConstructor
    private static class NewSkipStrategy implements NewDeviceAckStrategy {
        private final String consumerId;

        @Override
        public NewDeviceProcessingDecision analyze(NewDevicePackProcessingResult processingResult) {
            // TODO: implement
            return new NewDeviceProcessingDecision(true, Collections.emptyMap());
        }
    }

    private static class NewRetryStrategy implements NewDeviceAckStrategy {
        private final String consumerId;
        private final int maxRetries;
        private final int pauseBetweenRetries;

        public NewRetryStrategy(String consumerId, NewDeviceAckStrategyConfiguration configuration) {
            this.consumerId = consumerId;
            this.maxRetries = configuration.getRetries();
            this.pauseBetweenRetries = configuration.getPauseBetweenRetries();
        }

        private int retryCount;

        @Override
        public NewDeviceProcessingDecision analyze(NewDevicePackProcessingResult processingResult) {
            // TODO: implement
            return new NewDeviceProcessingDecision(true, Collections.emptyMap());
        }
    }

}
