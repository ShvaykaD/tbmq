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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.util.List;

public class MqttSnMessageEncoder extends MessageToMessageEncoder<MqttSnMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, MqttSnMessage msg, List<Object> out) throws Exception {
        ByteBuf buf = ctx.alloc().buffer(); // Allocate a buffer for the encoded message

        // Serialize the message based on its type
        switch (msg.getType()) {
            case CONNECT -> encodeConnect((MqttSnConnectMessage) msg, buf);
            case PUBLISH -> encodePublish((MqttSnPublishMessage) msg, buf);
            case SUBSCRIBE -> encodeSubscribe((MqttSnSubscribeMessage) msg, buf);
            default -> throw new IllegalArgumentException("Unsupported MQTT-SN message type: " + msg.getType());
        }
        out.add(buf); // Add the encoded ByteBuf to the output list
    }

    private void encodeConnect(MqttSnConnectMessage message, ByteBuf buf) {
        buf.writeByte(6 + message.getClientId().length() + 1); // Length: Fixed fields + Client ID + null terminator
        buf.writeByte(MqttSnMsgType.CONNECT.getOrdinal()); // Message type
        buf.writeByte(message.getFlags()); // Flags
        buf.writeByte(message.getProtocolId()); // Protocol ID
        buf.writeShort(message.getKeepAlive()); // Keep-alive duration
        writeString(message.getClientId(), buf); // Client ID
    }

    private void encodePublish(MqttSnPublishMessage message, ByteBuf buf) {
        buf.writeByte(7 + message.getPayload().length); // Length: Fixed fields + payload
        buf.writeByte(MqttSnMsgType.PUBLISH.getOrdinal()); // Message type
        buf.writeShort(message.getTopicId()); // Topic ID
        buf.writeByte(message.getQos()); // QoS level
        buf.writeShort(message.getMessageId()); // Message ID
        buf.writeBytes(message.getPayload()); // Payload
    }

    private void encodeSubscribe(MqttSnSubscribeMessage message, ByteBuf buf) {
        buf.writeByte(5 + message.getTopicName().length() + 1); // Length: Fixed fields + Topic Name + null terminator
        buf.writeByte(MqttSnMsgType.SUBSCRIBE.getOrdinal()); // Message type
        buf.writeByte(message.getFlags()); // Flags
        buf.writeShort(message.getMessageId()); // Message ID
        writeString(message.getTopicName(), buf); // Topic Name
    }

    private void writeString(String str, ByteBuf buf) {
        buf.writeBytes(str.getBytes()); // Write the string as bytes
        buf.writeByte(0); // Null terminator
    }
}
