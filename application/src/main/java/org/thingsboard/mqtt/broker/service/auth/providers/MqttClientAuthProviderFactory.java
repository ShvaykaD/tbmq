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
package org.thingsboard.mqtt.broker.service.auth.providers;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.thingsboard.mqtt.broker.cache.CacheNameResolver;
import org.thingsboard.mqtt.broker.common.data.security.MqttClientAuthProviderDto;
import org.thingsboard.mqtt.broker.dao.client.MqttClientCredentialsService;
import org.thingsboard.mqtt.broker.service.auth.AuthorizationRuleService;

@Component
@RequiredArgsConstructor
public class MqttClientAuthProviderFactory {

    private final AuthorizationRuleService authorizationRuleService;
    private final MqttClientCredentialsService credentialsService;
    private final CacheNameResolver cacheNameResolver;
    private final @Lazy BCryptPasswordEncoder passwordEncoder;

    public MqttClientAuthProvider createProvider(MqttClientAuthProviderDto dto) {
        return switch (dto.getType()) {
            case BASIC -> new BasicMqttClientAuthProvider(authorizationRuleService, credentialsService, cacheNameResolver, passwordEncoder, dto);
            case SSL -> new SslMqttClientAuthProvider(authorizationRuleService, credentialsService, cacheNameResolver, dto);
            // TODO: Not fully implemented yet.
            case JWT -> new JwtMqttClientAuthProvider(dto);
        };
    }

}