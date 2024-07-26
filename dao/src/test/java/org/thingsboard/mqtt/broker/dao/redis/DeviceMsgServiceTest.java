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
package org.thingsboard.mqtt.broker.dao.redis;

import io.netty.handler.codec.mqtt.MqttProperties;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.thingsboard.mqtt.broker.common.data.DevicePublishMsg;
import org.thingsboard.mqtt.broker.common.data.PersistedPacketType;
import org.thingsboard.mqtt.broker.dao.DaoSqlTest;
import org.thingsboard.mqtt.broker.dao.messages.DeviceMsgService;
import org.thingsboard.mqtt.broker.dao.service.AbstractServiceTest;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@DaoSqlTest
public class DeviceMsgServiceTest extends AbstractServiceTest {

    @Autowired
    private DeviceMsgService deviceMsgService;

    private final String TEST_CLIENT_ID = "testClientId";
    private final byte[] TEST_PAYLOAD = "testPayload".getBytes();
    private final List<DevicePublishMsg> TEST_MESSAGES = Arrays.asList(
            newDevicePublishMsg(1),
            newDevicePublishMsg(2),
            newDevicePublishMsg(3),
            newDevicePublishMsg(4),
            newDevicePublishMsg(5)
    );

    private DevicePublishMsg newDevicePublishMsg(int packetId) {
        return new DevicePublishMsg(TEST_CLIENT_ID, UUID.randomUUID().toString(), 0L, 0, packetId,
                PersistedPacketType.PUBLISH, TEST_PAYLOAD, new MqttProperties(), false);
    }

    @After
    public void clearState() {
        deviceMsgService.removePersistedMessages(TEST_CLIENT_ID);
    }

    @Test
    public void testCRUDLastPacketId() {
        deviceMsgService.saveLastPacketId(TEST_CLIENT_ID, 5);
        Assert.assertEquals(5, deviceMsgService.getLastPacketId(TEST_CLIENT_ID));
        deviceMsgService.saveLastPacketId(TEST_CLIENT_ID, 6);
        Assert.assertEquals(6, deviceMsgService.getLastPacketId(TEST_CLIENT_ID));
        deviceMsgService.removeLastPacketId(TEST_CLIENT_ID);
        Assert.assertEquals(0, deviceMsgService.getLastPacketId(TEST_CLIENT_ID));
    }

    // TODO: add tests for other methods.

}
