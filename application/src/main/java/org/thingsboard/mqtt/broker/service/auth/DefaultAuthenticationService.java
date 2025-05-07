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
package org.thingsboard.mqtt.broker.service.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.exception.AuthenticationException;
import org.thingsboard.mqtt.broker.service.auth.providers.AuthContext;
import org.thingsboard.mqtt.broker.service.auth.providers.AuthResponse;
import org.thingsboard.mqtt.broker.service.auth.providers.MqttClientAuthProviderManager;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultAuthenticationService implements AuthenticationService {

    private final MqttClientAuthProviderManager authProviderManager;

    // TODO: implement with check of the old logic. Uncomment test: DefaultAuthenticationServiceTest
    @Override
    public AuthResponse authenticate(AuthContext authContext) throws AuthenticationException {
        logAuthenticationAttempt(authContext);

        if (!authProviderManager.isAuthEnabled()) {
            return AuthResponse.defaultAuthResponse();
        }

        List<String> failureReasons = new ArrayList<>();

        // JWT first
        if (authProviderManager.isJwtEnabled() && authProviderManager.isVerifyJwtFirst()) {
            AuthResponse jwtResponse = authProviderManager.getJwtMqttClientAuthProvider().authenticate(authContext);
            if (jwtResponse.isSuccess()) {
                return jwtResponse;
            }
            failureReasons.add("JWT: " + jwtResponse.getReason());
        }

        // BASIC
        if (authProviderManager.isBasicEnabled()) {
            AuthResponse basicResponse = authProviderManager.getBasicMqttClientAuthProvider().authenticate(authContext);
            if (basicResponse.isSuccess()) {
                return basicResponse;
            }
            failureReasons.add("BASIC: " + basicResponse.getReason());
        }

        // SSL
        if (authProviderManager.isSslEnabled() && authContext.isTlsEnabled()) {
            AuthResponse sslResponse = authProviderManager.getSslMqttClientAuthProvider().authenticate(authContext);
            if (sslResponse.isSuccess()) {
                return sslResponse;
            }
            failureReasons.add("SSL: " + sslResponse.getReason());
        }

        // JWT first
        if (authProviderManager.isJwtEnabled() && !authProviderManager.isVerifyJwtFirst()) {
            AuthResponse jwtResponse = authProviderManager.getJwtMqttClientAuthProvider().authenticate(authContext);
            if (jwtResponse.isSuccess()) {
                return jwtResponse;
            }
            failureReasons.add("JWT: " + jwtResponse.getReason());
        }

        // Everything failed
        String fullReason = String.join(" | ", failureReasons);
        throw newAuthenticationException(authContext, new RuntimeException("Authentication failed: " + fullReason));
    }

    private void logAuthenticationAttempt(AuthContext authContext) {
        if (log.isTraceEnabled()) {
            log.trace("[{}] Authenticating client", authContext.getClientId());
        }
    }

//    private AuthResponse authenticateWithBothStrategies(AuthContext authContext) throws AuthenticationException {
//        var basicAuthResponse = authenticate(AuthProviderType.BASIC, authContext);
//        if (isAuthSuccessful(basicAuthResponse)) {
//            return basicAuthResponse;
//        }
//
//        String basicAuthFailureReason = getBasicAuthFailureReason(basicAuthResponse);
//        if (authContext.isTlsDisabled()) {
//            return AuthResponse.failure(basicAuthFailureReason);
//        }
//
//        var sslAuthResponse = authenticate(AuthProviderType.X_509_CERTIFICATE_CHAIN, authContext);
//        return processSslAuthResponse(sslAuthResponse, basicAuthFailureReason);
//    }
//
//    private AuthResponse authenticateWithSingleStrategy(AuthContext authContext) throws AuthenticationException {
//        var authResponse = authenticateBySingleAuthProvider(authContext);
//
//        if (authResponse == null) {
//            throwAuthenticationExceptionForSingleStrategy(authContext);
//        }
//
//        return authResponse;
//    }
//
//    private AuthResponse authenticateBySingleAuthProvider(AuthContext authContext) throws AuthenticationException {
//        AuthProviderType providerType = getAuthProviderType(authContext);
//        return authenticate(providerType, authContext);
//    }
//
//    private void throwAuthenticationExceptionForSingleStrategy(AuthContext authContext) throws AuthenticationException {
//        String providerType = getAuthProviderType(authContext).name();
//        String errorMsg = String.format("Failed to authenticate client, %s authentication is disabled!", providerType);
//        throw new AuthenticationException(errorMsg);
//    }
//
//    private AuthResponse processSslAuthResponse(AuthResponse authResponse, String basicAuthFailureReason) throws AuthenticationException {
//        if (authResponse == null) {
//            throw new AuthenticationException(basicAuthFailureReason + ". X_509_CERTIFICATE_CHAIN authentication is disabled!");
//        }
//
//        if (authResponse.isFailure()) {
//            String errorMsg = basicAuthFailureReason + ". " + authResponse.getReason();
//            return authResponse.toBuilder().reason(errorMsg).build();
//        }
//
//        return authResponse;
//    }
//
//    private String getBasicAuthFailureReason(AuthResponse authResponse) {
//        return authResponse == null ? "BASIC authentication is disabled" : authResponse.getReason();
//    }
//
//    private AuthResponse authenticate(AuthProviderType type, AuthContext authContext) throws AuthenticationException {
//        var authProvider = authProviders.get(type);
//        return authProvider != null ? authProvider.authenticate(authContext) : null;
//    }
//
//    private AuthProviderType getAuthProviderType(AuthContext authContext) {
//        return authContext.isTlsEnabled() ? AuthProviderType.X_509_CERTIFICATE_CHAIN : AuthProviderType.BASIC;
//    }
//
//    private boolean isAuthSuccessful(AuthResponse authResponse) {
//        return authResponse != null && authResponse.isSuccess();
//    }

    private AuthenticationException newAuthenticationException(AuthContext authContext, Exception e) {
        log.warn("[{}] Failed to authenticate client", authContext.getClientId(), e);
        return new AuthenticationException("Exception on client authentication: " + e.getMessage());
    }

}
