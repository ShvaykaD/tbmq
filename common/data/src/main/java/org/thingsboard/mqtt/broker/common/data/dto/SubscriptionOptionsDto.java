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
package org.thingsboard.mqtt.broker.common.data.dto;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.thingsboard.mqtt.broker.common.data.subscription.SubscriptionOptions;

@Data
@RequiredArgsConstructor
public final class SubscriptionOptionsDto {

    private final boolean noLocal;
    private final boolean retainAsPublish;
    private final int retainHandling;

    public SubscriptionOptionsDto() {
        this.noLocal = false;
        this.retainAsPublish = false;
        this.retainHandling = 0;
    }

    public static SubscriptionOptionsDto newInstance() {
        return new SubscriptionOptionsDto();
    }

    public static SubscriptionOptionsDto fromSubscriptionOptions(SubscriptionOptions options) {
        if (options == null) {
            return newInstance();
        }
        return new SubscriptionOptionsDto(options.isNoLocal(), options.isRetainAsPublish(), options.getRetainHandling().value());
    }

}
