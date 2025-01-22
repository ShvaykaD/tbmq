/**
 * Copyright © 2016-2025 The Thingsboard Authors
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.mqtt.broker.server.udp.msg;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class MqttSnMessageDecoder extends MessageToMessageDecoder<ByteBuf> {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        MqttSnMessage message = decodeMessage(msg);
        out.add(message);
    }

    private MqttSnMessage decodeMessage(ByteBuf buf) {
        if (buf.readableBytes() < 2) {
            throw new IllegalArgumentException("Invalid MQTT-SN packet length!");
        }

        int length = buf.readUnsignedByte();  // Read the length of the message
        int messageType = buf.readUnsignedByte();  // Read the message type

        MqttSnMsgType type = MqttSnMsgType.parse(messageType);

        // Switch based on a message type to decode the specific message
        return switch (type) {
            case ADVERTISE, WILL_MSG_RESP, WILL_MSG_UPD, WILL_TOPIC_RESP, WILL_TOPIC_UPD, DISCONNECT, PINGRESP, PINGREQ,
                 UNSUBACK, UNSUBSCRIBE, SUBACK, SEARCHGW, GWINFO, CONNACK, WILL_TOPIC_REQ, WILL_TOPIC, WILL_MSG_REQ,
                 WILL_MSG, REGISTER, REGACK, PUBACK, PUBCOMP, PUBREC, PUBREL, ENCAPSULATED -> {
                log.info("Unsupported MQTT-SN packet type: {}", type);
                yield null;
            }
            case CONNECT -> decodeConnect(buf);
            case PUBLISH -> decodePublish(buf);
            case SUBSCRIBE -> decodeSubscribe(buf);
        };
    }

    private MqttSnConnectMessage decodeConnect(ByteBuf buf) {
        byte flags = buf.readByte();
        byte protocolId = buf.readByte();
        int keepAlive = buf.readUnsignedShort();
        String clientId = readString(buf);
        return new MqttSnConnectMessage(flags, protocolId, keepAlive, clientId);
    }

    private MqttSnSubscribeMessage decodeSubscribe(ByteBuf buf) {
        byte flags = buf.readByte();
        int messageId = buf.readUnsignedShort();
        String topicName = readString(buf);
        return new MqttSnSubscribeMessage(flags, messageId, topicName);
    }

    private MqttSnPublishMessage decodePublish(ByteBuf buf) {
        int topicId = buf.readUnsignedShort();
        byte qos = buf.readByte();
        int msgId = buf.readUnsignedShort();
        byte[] payload = new byte[buf.readableBytes()];
        buf.readBytes(payload);
        return new MqttSnPublishMessage(topicId, msgId, qos, payload);
    }

    private String readString(ByteBuf buf) {
        StringBuilder sb = new StringBuilder();
        while (buf.isReadable()) {
            byte b = buf.readByte();
            if (b == 0) { // Null-terminated
                break;
            }
            sb.append((char) b);
        }
        return sb.toString();
    }

}