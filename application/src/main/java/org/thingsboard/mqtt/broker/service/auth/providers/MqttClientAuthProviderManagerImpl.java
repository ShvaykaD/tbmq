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

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.common.data.page.PageData;
import org.thingsboard.mqtt.broker.common.data.page.PageLink;
import org.thingsboard.mqtt.broker.common.data.security.MqttClientAuthProviderDto;
import org.thingsboard.mqtt.broker.common.data.security.jwt.JwtAuthProviderConfiguration;
import org.thingsboard.mqtt.broker.dao.client.provider.MqttClientAuthProviderService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MqttClientAuthProviderManagerImpl implements MqttClientAuthProviderManager {

    // TODO: we have only 3 possible providers at the current stage. No need to try fetch more
    private static final PageLink DEFAULT_PAGE_LINK = new PageLink(3, 0);

    private final MqttClientAuthProviderService mqttClientAuthProviderService;
    private final MqttClientAuthProviderFactory mqttClientAuthProviderFactory;

    private volatile boolean authEnabled;

    private volatile BasicMqttClientAuthProvider basicProvider;
    private volatile SslMqttClientAuthProvider sslProvider;
    private volatile JwtMqttClientAuthProvider jwtProvider;

    // TODO: improve logging.
    @PostConstruct
    public void init() {
        PageData<MqttClientAuthProviderDto> enabledAuthProvidersDto = mqttClientAuthProviderService.getEnabledAuthProviders(DEFAULT_PAGE_LINK);
        if (enabledAuthProvidersDto == null || enabledAuthProvidersDto.getData().isEmpty()) {
            log.debug("MQTT client auth providers are disabled!");
            authEnabled = false;
            return;
        }
        for (var dto : enabledAuthProvidersDto.getData()) {
            try {
                switch (dto.getType()) {
                    case BASIC -> basicProvider = (BasicMqttClientAuthProvider) mqttClientAuthProviderFactory.createProvider(dto);
                    case SSL -> sslProvider = (SslMqttClientAuthProvider) mqttClientAuthProviderFactory.createProvider(dto);
                    case JWT -> jwtProvider = (JwtMqttClientAuthProvider) mqttClientAuthProviderFactory.createProvider(dto);
                    default -> throw new IllegalStateException("Unexpected MQTT client auth provider type: " + dto.getType());
                }
            } catch (Exception ex) {
                log.error("[{}][{}] Failed to initialize provider: ", dto.getId(), dto.getType(), ex);
            }
        }
    }


    @Override
    public List<MqttClientAuthProvider> getOrderedActiveProviders() {
        List<MqttClientAuthProvider> ordered = new ArrayList<>(3);

        boolean jwtProviderEnabled = jwtProvider != null && jwtProvider.isEnabled();

        if (jwtProviderEnabled) {
            JwtAuthProviderConfiguration configuration
                    = (JwtAuthProviderConfiguration) jwtProvider.getConfiguration();
            if (configuration.isVerifyJwtFirst()) {
                ordered.add(jwtProvider);
            }
        }

        if (sslProvider != null && sslProvider.isEnabled()) {
            ordered.add(sslProvider);
        }
        if (basicProvider != null && basicProvider.isEnabled()) {
            ordered.add(basicProvider);
        }

        if (jwtProviderEnabled) {
            JwtAuthProviderConfiguration configuration
                    = (JwtAuthProviderConfiguration) jwtProvider.getConfiguration();
            if (!configuration.isVerifyJwtFirst()) {
                ordered.add(jwtProvider);
            }
        }

        return List.copyOf(ordered);
    }

    @Override
    public boolean isAuthEnabled() {
        return authEnabled;
    }

    @Override
    public boolean isJwtEnabled() {
        return jwtProvider != null && jwtProvider.isEnabled();
    }

    @Override
    public boolean isBasicEnabled() {
        return basicProvider != null && basicProvider.isEnabled();
    }

    @Override
    public boolean isSslEnabled() {
        return sslProvider != null && sslProvider.isEnabled();
    }

    @Override
    public boolean isVerifyJwtFirst() {
        return isJwtEnabled() && ((JwtAuthProviderConfiguration) jwtProvider.getConfiguration()).isVerifyJwtFirst();
    }

    @Override
    public JwtMqttClientAuthProvider getJwtMqttClientAuthProvider() {
        return jwtProvider;
    }

    @Override
    public BasicMqttClientAuthProvider getBasicMqttClientAuthProvider() {
        return basicProvider;
    }

    @Override
    public SslMqttClientAuthProvider getSslMqttClientAuthProvider() {
        return sslProvider;
    }

    @Override
    public void handleProviderUpdate(MqttClientAuthProviderDto dto) {
    }

    @Override
    public void handleProviderDelete(UUID id) {

    }

    @Override
    public void handleProviderEnable(UUID id) {

    }

    @Override
    public void handleProviderDisable(UUID id) {

    }

}
