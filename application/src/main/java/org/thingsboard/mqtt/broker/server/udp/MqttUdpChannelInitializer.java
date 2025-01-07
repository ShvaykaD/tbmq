package org.thingsboard.mqtt.broker.server.udp;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.mqtt.MqttDecoder;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MqttUdpChannelInitializer extends ChannelInitializer<DatagramChannel> {

    @Value("${mqtt.sn.max_payload_size:512}")
    private int maxPayloadSize;
    
    @Value("${mqtt.sn.max_client_id_length:23}")
    private int maxClientIdLength;

    @Value("${mqtt.sn.historical_data_report:false}")
    private boolean historicalDataReportEnabled;

//    private final MqttSnHandlerFactory handlerFactory;

    @Override
    protected void initChannel(DatagramChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // Optional: Monitor traffic if enabled
//        if (historicalDataReportEnabled) {
//            pipeline.addLast(new UdpTrafficMonitor(handlerFactory.getTbMessageStatsReportClient()));
//        }

        // Add MQTT-SN decoder and encoder to handle incoming/outgoing packets
        pipeline.addLast("decoder", new MqttDecoder(maxPayloadSize, maxClientIdLength));
        pipeline.addLast("encoder", MqttSnEncoder.INSTANCE);
        
        // Add session/message handler to manage client sessions
        MqttSnSessionHandler handler = handlerFactory.create();
        pipeline.addLast(handler);

        // Handle channel close events
        ch.closeFuture().addListener(handler);

        log.info("UDP channel initialized for MQTT-SN (maxPayloadSize={}, maxClientIdLength={})",
                 maxPayloadSize, maxClientIdLength);
    }
}