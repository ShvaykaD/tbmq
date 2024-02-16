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
package org.thingsboard.mqtt.broker.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.mqtt.broker.common.data.ClientSessionQuery;
import org.thingsboard.mqtt.broker.common.data.ClientType;
import org.thingsboard.mqtt.broker.common.data.ConnectionState;
import org.thingsboard.mqtt.broker.common.data.StringUtils;
import org.thingsboard.mqtt.broker.common.data.exception.ThingsboardException;
import org.thingsboard.mqtt.broker.common.data.page.PageData;
import org.thingsboard.mqtt.broker.common.data.page.PageLink;
import org.thingsboard.mqtt.broker.common.data.page.TimePageLink;
import org.thingsboard.mqtt.broker.dto.ClientSessionStatsInfoDto;
import org.thingsboard.mqtt.broker.dto.DetailedClientSessionInfoDto;
import org.thingsboard.mqtt.broker.dto.ShortClientSessionInfoDto;
import org.thingsboard.mqtt.broker.service.mqtt.client.session.ClientSessionPageInfos;
import org.thingsboard.mqtt.broker.service.mqtt.client.session.SessionSubscriptionService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ClientSessionController extends BaseController {

    private final SessionSubscriptionService sessionSubscriptionService;
    private final ClientSessionPageInfos clientSessionPageInfos;

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN')")
    @RequestMapping(value = "/client-session/remove", params = {"clientId", "sessionId"}, method = RequestMethod.DELETE)
    @ResponseBody
    public void removeClientSession(@RequestParam String clientId,
                                    @RequestParam("sessionId") String sessionIdStr) throws ThingsboardException {
        try {
            clientSessionCleanUpService.removeClientSession(clientId, toUUID(sessionIdStr));
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN')")
    @RequestMapping(value = "/client-session/disconnect", params = {"clientId", "sessionId"}, method = RequestMethod.DELETE)
    @ResponseBody
    public void disconnectClientSession(@RequestParam String clientId,
                                        @RequestParam("sessionId") String sessionIdStr) throws ThingsboardException {
        try {
            clientSessionCleanUpService.disconnectClientSession(clientId, toUUID(sessionIdStr));
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN')")
    @RequestMapping(value = "/client-session", params = {"clientId"}, method = RequestMethod.GET)
    @ResponseBody
    public DetailedClientSessionInfoDto getDetailedClientSessionInfo(@RequestParam String clientId) throws ThingsboardException {
        try {
            return checkNotNull(sessionSubscriptionService.getDetailedClientSessionInfo(clientId));
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN')")
    @RequestMapping(value = "/client-session", params = {"pageSize", "page"}, method = RequestMethod.GET)
    @ResponseBody
    public PageData<ShortClientSessionInfoDto> getShortClientSessionInfos(@RequestParam int pageSize,
                                                                          @RequestParam int page,
                                                                          @RequestParam(required = false) String textSearch,
                                                                          @RequestParam(required = false) String sortProperty,
                                                                          @RequestParam(required = false) String sortOrder) throws ThingsboardException {
        try {
            PageLink pageLink = createPageLink(pageSize, page, textSearch, sortProperty, sortOrder);
            return checkNotNull(clientSessionPageInfos.getClientSessionInfos(pageLink));
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN')")
    @RequestMapping(value = "/v2/client-session", params = {"pageSize", "page"}, method = RequestMethod.GET)
    @ResponseBody
    public PageData<ShortClientSessionInfoDto> getShortClientSessionInfosV2(@RequestParam int pageSize,
                                                                            @RequestParam int page,
                                                                            @RequestParam(required = false) String textSearch,
                                                                            @RequestParam(required = false) String sortProperty,
                                                                            @RequestParam(required = false) String sortOrder,
                                                                            @RequestParam(required = false) Long startTime,
                                                                            @RequestParam(required = false) Long endTime,
                                                                            @RequestParam(required = false) String[] connectedStatusList,
                                                                            @RequestParam(required = false) String[] clientTypeList,
                                                                            @RequestParam(required = false) String[] cleanStartList,
                                                                            @RequestParam(required = false) String[] nodeIdList,
                                                                            @RequestParam(required = false) Integer subscriptions) throws ThingsboardException {
        try {
            List<ConnectionState> connectedStatuses = new ArrayList<>();
            if (connectedStatusList != null) {
                for (String strStatus : connectedStatusList) {
                    if (!StringUtils.isEmpty(strStatus)) {
                        connectedStatuses.add(ConnectionState.valueOf(strStatus));
                    }
                }
            }
            List<ClientType> clientTypes = new ArrayList<>();
            if (clientTypeList != null) {
                for (String strType : clientTypeList) {
                    if (!StringUtils.isEmpty(strType)) {
                        clientTypes.add(ClientType.valueOf(strType));
                    }
                }
            }
            List<Boolean> cleanStarts = new ArrayList<>();
            if (cleanStartList != null) {
                for (String strCleanStart : cleanStartList) {
                    if (!StringUtils.isEmpty(strCleanStart)) {
                        cleanStarts.add(Boolean.valueOf(strCleanStart));
                    }
                }
            }

            List<String> brokerNodeIdList = nodeIdList != null ? Arrays.asList(nodeIdList) : Collections.emptyList();

            TimePageLink pageLink = createTimePageLink(pageSize, page, textSearch, sortProperty, sortOrder, startTime, endTime);

            return checkNotNull(clientSessionPageInfos.getClientSessionInfos(
                    new ClientSessionQuery(pageLink, connectedStatuses, clientTypes, cleanStarts, brokerNodeIdList, subscriptions)
            ));
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN')")
    @RequestMapping(value = "/client-session/info", method = RequestMethod.GET)
    @ResponseBody
    public ClientSessionStatsInfoDto getClientSessionsStatsInfo() throws ThingsboardException {
        try {
            return checkNotNull(clientSessionPageInfos.getClientSessionStatsInfo());
        } catch (Exception e) {
            throw handleException(e);
        }
    }

}
