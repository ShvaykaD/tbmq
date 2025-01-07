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
package org.thingsboard.mqtt.broker.server.udp;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.concurrent.Future;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Getter
@Slf4j
@ConditionalOnProperty(prefix = "listener.udp", value = "enabled", havingValue = "true", matchIfMissing = true)
public class MqttUdpServerBootstrap {

    private static final String SERVER_NAME = "UDP Server";

    @Value("${listener.udp.bind_address}")
    private String host;
    @Value("${listener.udp.bind_port}")
    private int port;

    @Value("${listener.udp.netty.leak_detector_level}")
    private String leakDetectorLevel;
    @Value("${listener.udp.netty.worker_group_thread_count}")
    private int workerGroupThreadCount;
    @Value("${listener.udp.netty.shutdown_quiet_period:0}")
    private int shutdownQuietPeriod;
    @Value("${listener.udp.netty.shutdown_timeout:5}")
    private int shutdownTimeout;

    private final MqttUdpChannelInitializer mqttUdpChannelInitializer;

    private EventLoopGroup workerGroup;
    private Channel channel;

    public void init() throws InterruptedException {
        log.info("[{}] Setting resource leak detector level to {}", SERVER_NAME, getLeakDetectorLevel());
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.valueOf(getLeakDetectorLevel().toUpperCase()));

        log.info("[{}] Starting MQTT-SN server...", SERVER_NAME);
        workerGroup = new NioEventLoopGroup(workerGroupThreadCount);
        Bootstrap b = new Bootstrap();
        b.group(workerGroup).channel(getChannelClazz()).handler(mqttUdpChannelInitializer);

        channel = b.bind(new InetSocketAddress(host, port)).sync().channel();
        log.info("[{}] MQTT-SN server started on port {}!", SERVER_NAME, getPort());
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(value = 100)
    public void onApplicationEvent(ApplicationReadyEvent applicationReadyEvent) throws Exception {
        init();
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        log.info("Stopping MQTT-SN server...");
        if (channel != null) {
            channel.close().sync();
        }
        if (workerGroup != null) {
            Future<?> workerFuture = workerGroup.shutdownGracefully(shutdownQuietPeriod, shutdownTimeout, TimeUnit.SECONDS).sync();
            log.info("[{}] Awaiting shutdown gracefully worker groups...", SERVER_NAME);
            workerFuture.sync();
        }
        log.info("MQTT-SN UDP server stopped.");
    }

    private Class<? extends DatagramChannel> getChannelClazz() {
        if (Epoll.isAvailable()) {
            log.info("Using EpollDatagramChannel for UDP communication.");
            return EpollDatagramChannel.class;
        }
        log.info("Using NioDatagramChannel (default) for UDP communication.");
        return NioDatagramChannel.class;
    }
}
