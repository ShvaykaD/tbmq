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
package org.thingsboard.mqtt.broker.server.udp;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.mqtt.broker.server.udp.msg.MqttSnMessage;
import org.thingsboard.mqtt.broker.session.SessionContext;

import java.net.InetSocketAddress;
import java.util.UUID;

@Getter
@Slf4j
public class MqttSnSessionHandler extends ChannelInboundHandlerAdapter implements GenericFutureListener<Future<? super Void>>, SessionContext {

    private static final AttributeKey<InetSocketAddress> ADDRESS = AttributeKey.newInstance("SRC_ADDRESS");

    private final UUID sessionId = UUID.randomUUID();

    private String clientId;
    private InetSocketAddress address;

    public MqttSnSessionHandler(MqttSnHandlerCtx mqttSnHandlerCtx, String initializerName) {
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (address == null) {
            address = getAddress(ctx);
//            clientSessionCtx.setAddress(address);
        }
        if (log.isTraceEnabled()) {
            log.trace("[{}][{}][{}] Processing msg: {}", address, clientId, sessionId, msg);
        }
//        clientSessionCtx.setChannel(ctx);
        try {
            if (!(msg instanceof MqttSnMessage message)) {
                log.warn("[{}][{}] Received unknown message", clientId, sessionId);
//                disconnect(new DisconnectReason(DisconnectReasonType.ON_PROTOCOL_ERROR, "Received unknown message"));
                return;
            }
            log.info("LALALALALLALA");

//            if (!message.decoderResult().isSuccess()) {
//                log.warn("[{}][{}] Message decoding failed: {}", clientId, sessionId, message.decoderResult().cause().getMessage());
//                if (message.decoderResult().cause() instanceof TooLongFrameException) {
//                    disconnect(new DisconnectReason(DisconnectReasonType.ON_PACKET_TOO_LARGE));
//                } else {
//                    disconnect(new DisconnectReason(DisconnectReasonType.ON_MALFORMED_PACKET, "Message decoding failed"));
//                }
//                return;
//            }
//
//            processMqttMsg(message);
        } finally {
            ReferenceCountUtil.safeRelease(msg);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    @Override
    public void operationComplete(Future<? super Void> future) throws Exception {
        log.warn("Operation completed, sessionId: {}, {}", sessionId, future);

    }

    @Override
    public UUID getSessionId() {
        return null;
    }

    InetSocketAddress getAddress(ChannelHandlerContext ctx) {
        var address = ctx.channel().attr(ADDRESS).get();
        if (address == null) {
            if (log.isTraceEnabled()) {
                log.trace("[{}] Received empty address.", ctx.channel().id());
            }
            InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
            if (log.isTraceEnabled()) {
                log.trace("[{}] Going to use address: {}", ctx.channel().id(), remoteAddress);
            }
            return remoteAddress;
        } else {
            if (log.isTraceEnabled()) {
                log.trace("[{}] Received address: {}", ctx.channel().id(), address);
            }
        }
        return address;
    }

}
