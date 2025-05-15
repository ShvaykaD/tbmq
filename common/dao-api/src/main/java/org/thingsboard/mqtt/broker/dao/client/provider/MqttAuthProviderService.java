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
package org.thingsboard.mqtt.broker.dao.client.provider;

import org.thingsboard.mqtt.broker.common.data.dto.ShortMqttAuthProvider;
import org.thingsboard.mqtt.broker.common.data.page.PageData;
import org.thingsboard.mqtt.broker.common.data.page.PageLink;
import org.thingsboard.mqtt.broker.common.data.security.MqttAuthProvider;

import java.util.Optional;
import java.util.UUID;

public interface MqttAuthProviderService {

    MqttAuthProvider saveAuthProvider(MqttAuthProvider authProvider);

    Optional<MqttAuthProvider> getAuthProviderById(UUID id);

    boolean deleteAuthProvider(UUID id);

    PageData<ShortMqttAuthProvider> getAuthProviders(PageLink pageLink);

    PageData<MqttAuthProvider> getEnabledAuthProviders(PageLink pageLink);

    boolean enableAuthProvider(UUID id);

    boolean disableAuthProvider(UUID id);

}
