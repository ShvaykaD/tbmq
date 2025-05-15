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
package org.thingsboard.mqtt.broker.dao.client;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.common.data.dto.ShortMqttAuthProvider;
import org.thingsboard.mqtt.broker.common.data.page.PageData;
import org.thingsboard.mqtt.broker.common.data.page.PageLink;
import org.thingsboard.mqtt.broker.common.data.security.MqttAuthProvider;
import org.thingsboard.mqtt.broker.common.util.MqttAuthProviderUtil;
import org.thingsboard.mqtt.broker.dao.client.provider.MqttAuthProviderService;
import org.thingsboard.mqtt.broker.dao.service.DataValidator;
import org.thingsboard.mqtt.broker.dao.util.exception.DbExceptionUtil;
import org.thingsboard.mqtt.broker.exception.DataValidationException;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.thingsboard.mqtt.broker.dao.service.Validator.validatePageLink;

@Service
@Slf4j
@RequiredArgsConstructor
public class MqttAuthProviderServiceImpl implements MqttAuthProviderService {

    private final MqttAuthProviderDao mqttAuthProviderDao;

    @Override
    public MqttAuthProvider saveAuthProvider(MqttAuthProvider authProvider) {
        log.trace("Executing saveAuthProvider [{}]", authProvider);
        authProviderValidator.validate(authProvider);
        try {
            return mqttAuthProviderDao.save(authProvider);
        } catch (Exception e) {
            ConstraintViolationException ex = DbExceptionUtil.extractConstraintViolationException(e).orElse(null);
            if (ex != null && ex.getConstraintName() != null
                && ex.getConstraintName().equalsIgnoreCase("mqtt_auth_provider_type_key")) {
                throw new DataValidationException("MQTT client auth provider with such type already registered!");
            }
            throw e;
        }
    }

    @Override
    public Optional<MqttAuthProvider> getAuthProviderById(UUID id) {
        log.trace("Executing getAuthProviderById [{}]", id);
        return Optional.ofNullable(mqttAuthProviderDao.findById(id));
    }

    @Override
    public boolean deleteAuthProvider(UUID id) {
        log.trace("Executing deleteAuthProvider [{}]", id);
        var authProvider = mqttAuthProviderDao.findById(id);
        if (authProvider == null) {
            return false;
        }
        return mqttAuthProviderDao.removeById(id);
    }

    @Override
    public PageData<ShortMqttAuthProvider> getAuthProviders(PageLink pageLink) {
        log.trace("Executing getAuthProviders, pageLink [{}]", pageLink);
        validatePageLink(pageLink);
        PageData<MqttAuthProvider> pageData = mqttAuthProviderDao.findAll(pageLink);
        var shortMqttAuthProviders = pageData.getData().stream()
                .map(MqttAuthProviderUtil::toShortMqttAuthProvider).collect(Collectors.toList());
        return new PageData<>(shortMqttAuthProviders, pageData.getTotalPages(), pageData.getTotalElements(), pageData.hasNext());
    }

    @Override
    public PageData<MqttAuthProvider> getEnabledAuthProviders(PageLink pageLink) {
        log.trace("Executing getEnabledAuthProviders, pageLink [{}]", pageLink);
        validatePageLink(pageLink);
        return mqttAuthProviderDao.findAllEnabled(pageLink);
    }

    @Override
    @Transactional
    public boolean enableAuthProvider(UUID id) {
        log.trace("Executing enableAuthProvider [{}]", id);
        var authProvider = mqttAuthProviderDao.findById(id);
        if (authProvider == null) {
            throw new DataValidationException("Unable to enable non-existent MQTT client auth provider!");
        }
        if (authProvider.isEnabled()) {
            log.debug("[{}][{}] Auth provider is already enabled!", id, authProvider.getType());
            return false;
        }
        return mqttAuthProviderDao.enableById(id);
    }

    @Override
    @Transactional
    public boolean disableAuthProvider(UUID id) {
        log.trace("Executing disableAuthProvider [{}]", id);
        var authProvider = mqttAuthProviderDao.findById(id);
        if (authProvider == null) {
            throw new DataValidationException("Unable to disable non-existent MQTT client auth provider!");
        }
        if (!authProvider.isEnabled()) {
            log.debug("[{}][{}] Auth provider is already disabled!", id, authProvider.getType());
            return false;
        }
        return mqttAuthProviderDao.disableById(id);
    }

    private final DataValidator<MqttAuthProvider> authProviderValidator =
            new DataValidator<>() {

                @Override
                protected void validateUpdate(MqttAuthProvider updated) {
                    MqttAuthProvider existing = mqttAuthProviderDao.findById(updated.getId());
                    if (existing == null) {
                        throw new DataValidationException("Unable to update non-existent MQTT client auth provider!");
                    }
                    if (existing.getType() != updated.getType()) {
                        throw new DataValidationException("MQTT client auth provider type can't be changed!");
                    }
                }

                @Override
                protected void validateDataImpl(MqttAuthProvider authProvider) {
                    if (authProvider.getType() == null) {
                        throw new DataValidationException("MQTT client auth provider type should be specified!");
                    }
                    if (authProvider.getConfiguration() == null) {
                        throw new DataValidationException("MQTT client auth provider configuration should be specified!");
                    }
                    authProvider.getConfiguration().validate();
                }
            };

}
