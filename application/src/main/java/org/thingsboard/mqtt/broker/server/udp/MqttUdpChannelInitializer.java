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

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.DatagramChannel;
import io.netty.handler.codec.DatagramPacketDecoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thingsboard.mqtt.broker.common.data.BrokerConstants;
import org.thingsboard.mqtt.broker.server.udp.msg.MqttSnMessageDecoder;
import org.thingsboard.mqtt.broker.server.udp.msg.MqttSnMessageEncoder;

@Slf4j
@Component
@RequiredArgsConstructor
public class MqttUdpChannelInitializer extends ChannelInitializer<DatagramChannel> {

    @Value("${mqtt.sn.max_payload_size:512}")
    private int maxPayloadSize;
    
    @Value("${mqtt.sn.max_client_id_length:23}")
    private int maxClientIdLength;

//    @Value("${mqtt.sn.historical_data_report:false}")
//    private boolean historicalDataReportEnabled;

    private final MqttSnHandlerFactory handlerFactory;

    @Override
    protected void initChannel(DatagramChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

//        if (historicalDataReportEnabled) {
//            pipeline.addLast(new UdpTrafficMonitor(handlerFactory.getTbMessageStatsReportClient()));
//        }

        // Add MQTT-SN decoder and encoder to handle incoming/outgoing packets
        pipeline.addLast("decoder", new DatagramPacketDecoder(new MqttSnMessageDecoder()));
        pipeline.addLast("encoder", new MqttSnMessageEncoder());

        // Add session/message handler to manage client sessions
        MqttSnSessionHandler handler = handlerFactory.create(BrokerConstants.UDP);
        pipeline.addLast(handler);

        // Handle channel close events
        ch.closeFuture().addListener(handler);

        log.info("UDP channel initialized for MQTT-SN (maxPayloadSize={}, maxClientIdLength={})",
                 maxPayloadSize, maxClientIdLength);
    }
}