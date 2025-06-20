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
package org.thingsboard.mqtt.broker.common.data.security;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.thingsboard.mqtt.broker.common.data.BaseDataWithAdditionalInfo;
import org.thingsboard.mqtt.broker.common.data.BrokerConstants;
import org.thingsboard.mqtt.broker.common.data.security.basic.BasicMqttAuthProviderConfiguration;
import org.thingsboard.mqtt.broker.common.data.security.jwt.JwtMqttAuthProviderConfiguration;
import org.thingsboard.mqtt.broker.common.data.security.scram.ScramMqttAuthProviderConfiguration;
import org.thingsboard.mqtt.broker.common.data.security.ssl.SslMqttAuthProviderConfiguration;
import org.thingsboard.mqtt.broker.common.data.validation.NoXss;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
public class MqttAuthProvider extends BaseDataWithAdditionalInfo {

    @Serial
    private static final long serialVersionUID = 464223366680445871L;

    private boolean enabled;

    @NoXss
    private MqttAuthProviderType type;
    private MqttAuthProviderConfiguration configuration;

    public static MqttAuthProvider defaultBasicAuthProvider(boolean enabled) {
        return defaultAuthProvider(MqttAuthProviderType.MQTT_BASIC, enabled);
    }

    public static MqttAuthProvider defaultSslAuthProvider(boolean enabled) {
        return defaultAuthProvider(MqttAuthProviderType.X_509, enabled);
    }

    public static MqttAuthProvider defaultJwtAuthProvider(boolean enabled) {
        return defaultAuthProvider(MqttAuthProviderType.JWT, enabled);
    }

    public static MqttAuthProvider defaultScramAuthProvider(boolean enabled) {
        return defaultAuthProvider(MqttAuthProviderType.SCRAM, enabled);
    }

    public static MqttAuthProvider defaultAuthProvider(MqttAuthProviderType type, boolean enabled) {
        MqttAuthProvider mqttAuthProvider = new MqttAuthProvider();
        mqttAuthProvider.setEnabled(enabled);
        mqttAuthProvider.setType(type);
        mqttAuthProvider.setConfiguration(
                switch (type) {
                    case MQTT_BASIC -> new BasicMqttAuthProviderConfiguration();
                    case X_509 -> new SslMqttAuthProviderConfiguration();
                    case JWT -> JwtMqttAuthProviderConfiguration.defaultConfiguration();
                    case SCRAM -> new ScramMqttAuthProviderConfiguration();
                });
        mqttAuthProvider.setAdditionalInfo(getAdditionalInfo(type));
        return mqttAuthProvider;
    }

    private static ObjectNode getAdditionalInfo(MqttAuthProviderType type) {
        ObjectNode additionalInfo = mapper.createObjectNode();
        additionalInfo.put(BrokerConstants.DESCRIPTION, getDescription(type));
        return additionalInfo;
    }

    private static String getDescription(MqttAuthProviderType type) {
        return switch (type) {
            case MQTT_BASIC ->
                    "Authenticates clients using a clientId, username, and password sent in the CONNECT packet.";
            case X_509 -> "Uses the client’s X.509 certificate chain during TLS handshake for authentication.";
            case JWT -> "Verifies a signed JWT token passed in the password to authenticate the client.";
            case SCRAM ->
                    "Performs a secure challenge-response using hashed credentials to authenticate without sending the actual password.";
        };
    }

}
