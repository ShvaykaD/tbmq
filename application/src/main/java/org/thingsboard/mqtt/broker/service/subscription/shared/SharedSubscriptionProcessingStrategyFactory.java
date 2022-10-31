/**
 * Copyright © 2016-2022 The Thingsboard Authors
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
package org.thingsboard.mqtt.broker.service.subscription.shared;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SharedSubscriptionProcessingStrategyFactory {

    @Value("${mqtt.shared-subscriptions.processing-type:ROUND_ROBIN}")
    private SharedSubscriptionProcessingType type;

    private final SharedSubscriptionProcessor sharedSubscriptionProcessor;

    public SharedSubscriptionProcessingStrategy newInstance() {
        if (SharedSubscriptionProcessingType.ROUND_ROBIN == type) {
            return new RoundRobinStrategy(sharedSubscriptionProcessor);
        }
        throw new RuntimeException("SharedSubscriptionProcessingType " + type + " is not supported!");
    }

}