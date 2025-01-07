package org.thingsboard.mqtt.broker.server.udp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

public class MqttSnDecoder extends MessageToMessageDecoder<DatagramPacket> {

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket packet, List<Object> out) {
        ByteBuf content = packet.content();
        MqttSnMessage msg = MqttSnMessageDecoder.decode(content);
        out.add(msg);
    }
}