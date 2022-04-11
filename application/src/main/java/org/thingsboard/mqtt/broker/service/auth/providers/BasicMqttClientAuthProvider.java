/**
 * Copyright © 2016-2020 The Thingsboard Authors
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.thingsboard.mqtt.broker.common.data.client.credentials.BasicMqttCredentials;
import org.thingsboard.mqtt.broker.common.data.security.MqttClientCredentials;
import org.thingsboard.mqtt.broker.common.util.JacksonUtil;
import org.thingsboard.mqtt.broker.dao.client.MqttClientCredentialsService;
import org.thingsboard.mqtt.broker.dao.util.protocol.ProtocolUtil;
import org.thingsboard.mqtt.broker.exception.AuthenticationException;
import org.thingsboard.mqtt.broker.service.auth.AuthorizationRuleService;
import org.thingsboard.mqtt.broker.service.security.authorization.AuthorizationRule;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BasicMqttClientAuthProvider implements MqttClientAuthProvider {

    private final AuthorizationRuleService authorizationRuleService;
    private final MqttClientCredentialsService clientCredentialsService;
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    public BasicMqttClientAuthProvider(AuthorizationRuleService authorizationRuleService,
                                       MqttClientCredentialsService clientCredentialsService,
                                       @Lazy BCryptPasswordEncoder passwordEncoder) {
        this.authorizationRuleService = authorizationRuleService;
        this.clientCredentialsService = clientCredentialsService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public AuthResponse authorize(AuthContext authContext) throws AuthenticationException {
        log.trace("[{}] Authenticating client with basic credentials", authContext.getClientId());
        MqttClientCredentials basicCredentials = authWithBasicCredentials(authContext.getClientId(), authContext.getUsername(), authContext.getPasswordBytes());
        if (basicCredentials == null) {
            return new AuthResponse(false, null, null);
        }
        log.trace("[{}] Authenticated with username {}", authContext.getClientId(), authContext.getUsername());
        BasicMqttCredentials credentials = JacksonUtil.fromString(basicCredentials.getCredentialsValue(), BasicMqttCredentials.class);
        AuthorizationRule authorizationRule = authorizationRuleService.parseBasicAuthorizationRule(credentials);
        return new AuthResponse(true, basicCredentials.getClientType(),
                authorizationRule == null ? Collections.emptyList() : Collections.singletonList(authorizationRule));
    }

    private MqttClientCredentials authWithBasicCredentials(String clientId, String username, byte[] passwordBytes) {
        List<String> credentialIds = new ArrayList<>();
        if (!StringUtils.isEmpty(username)) {
            credentialIds.add(ProtocolUtil.usernameCredentialsId(username));
        }
        if (!StringUtils.isEmpty(clientId)) {
            credentialIds.add(ProtocolUtil.clientIdCredentialsId(clientId));
        }
        if (!StringUtils.isEmpty(username) && !StringUtils.isEmpty(clientId)) {
            credentialIds.add(ProtocolUtil.mixedCredentialsId(username, clientId));
        }
        List<MqttClientCredentials> matchingCredentials = clientCredentialsService.findMatchingCredentials(credentialIds);
        String password = passwordBytes != null ?
                new String(passwordBytes, StandardCharsets.UTF_8) : null;

        for (MqttClientCredentials matchingCredential : matchingCredentials) {
            BasicMqttCredentials basicMqttCredentials = JacksonUtil.fromString(matchingCredential.getCredentialsValue(), BasicMqttCredentials.class);
            if (basicMqttCredentials != null && isMatchingPassword(password, basicMqttCredentials)) {
                return matchingCredential;
            }
        }
        return null;
    }

    private boolean isMatchingPassword(String password, BasicMqttCredentials basicMqttCredentials) {
        return basicMqttCredentials.getPassword() == null
                || (password != null && passwordEncoder.matches(password, basicMqttCredentials.getPassword()));
    }
}
