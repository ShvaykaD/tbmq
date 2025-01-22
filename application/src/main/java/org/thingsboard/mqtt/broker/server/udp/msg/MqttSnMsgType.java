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
package org.thingsboard.mqtt.broker.server.udp.msg;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MqttSnMsgType {

    ADVERTISE(0x00),
    SEARCHGW(0x01),
    GWINFO(0x02),
    CONNECT(0x04),
    CONNACK(0x05),
    WILL_TOPIC_REQ(0x06),
    WILL_TOPIC(0x07),
    WILL_MSG_REQ(0x08),
    WILL_MSG(0x09),
    REGISTER(0x0A),
    REGACK(0x0B),
    PUBLISH(0x0C),
    PUBACK(0x0D),
    PUBCOMP(0x0E),
    PUBREC(0x0F),
    PUBREL(0x10),
    SUBSCRIBE(0x12),
    SUBACK(0x13),
    UNSUBSCRIBE(0x14),
    UNSUBACK(0x15),
    PINGREQ(0x16),
    PINGRESP(0x17),
    DISCONNECT(0x18),
    WILL_TOPIC_UPD(0x1A),
    WILL_TOPIC_RESP(0x1B),
    WILL_MSG_UPD(0x1C),
    WILL_MSG_RESP(0x1D),
    ENCAPSULATED(0xFE);

    private final int ordinal;

    public static MqttSnMsgType parse(int ordinal) {
        for (MqttSnMsgType value : MqttSnMsgType.values()) {
            if (value.ordinal == ordinal) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown message type: " + ordinal);
    }
}
