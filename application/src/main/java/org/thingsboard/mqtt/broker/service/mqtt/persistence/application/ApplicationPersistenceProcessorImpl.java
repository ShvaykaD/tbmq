/**
 * Copyright © 2016-2022 The Thingsboard Authors
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
package org.thingsboard.mqtt.broker.service.mqtt.persistence.application;

import com.google.common.collect.Sets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.thingsboard.mqtt.broker.actors.client.messages.DisconnectMsg;
import org.thingsboard.mqtt.broker.actors.client.state.ClientActorStateInfo;
import org.thingsboard.mqtt.broker.actors.client.state.SessionState;
import org.thingsboard.mqtt.broker.adaptor.ProtoConverter;
import org.thingsboard.mqtt.broker.cluster.ServiceInfoProvider;
import org.thingsboard.mqtt.broker.common.util.ThingsBoardThreadFactory;
import org.thingsboard.mqtt.broker.gen.queue.QueueProtos.PublishMsgProto;
import org.thingsboard.mqtt.broker.queue.TbQueueAdmin;
import org.thingsboard.mqtt.broker.queue.TbQueueControlledOffsetConsumer;
import org.thingsboard.mqtt.broker.queue.common.TbProtoQueueMsg;
import org.thingsboard.mqtt.broker.queue.provider.ApplicationPersistenceMsgQueueFactory;
import org.thingsboard.mqtt.broker.service.analysis.ClientLogger;
import org.thingsboard.mqtt.broker.service.mqtt.PublishMsg;
import org.thingsboard.mqtt.broker.service.mqtt.PublishMsgDeliveryService;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.application.processing.ApplicationAckStrategy;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.application.processing.ApplicationMsgAcknowledgeStrategyFactory;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.application.processing.ApplicationPackProcessingCtx;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.application.processing.ApplicationPackProcessingResult;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.application.processing.ApplicationPersistedMsgCtx;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.application.processing.ApplicationPersistedMsgCtxService;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.application.processing.ApplicationProcessingDecision;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.application.processing.ApplicationPubRelMsgCtx;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.application.processing.ApplicationSubmitStrategy;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.application.processing.ApplicationSubmitStrategyFactory;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.application.processing.PersistedMsg;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.application.processing.PersistedPubRelMsg;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.application.processing.PersistedPublishMsg;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.application.topic.ApplicationTopicService;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.application.util.MqttApplicationClientUtil;
import org.thingsboard.mqtt.broker.service.stats.ApplicationProcessorStats;
import org.thingsboard.mqtt.broker.service.stats.StatsManager;
import org.thingsboard.mqtt.broker.service.subscription.shared.SharedSubscriptionTopicFilter;
import org.thingsboard.mqtt.broker.session.ClientMqttActorManager;
import org.thingsboard.mqtt.broker.session.ClientSessionCtx;
import org.thingsboard.mqtt.broker.session.DisconnectReason;
import org.thingsboard.mqtt.broker.session.DisconnectReasonType;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ApplicationPersistenceProcessorImpl implements ApplicationPersistenceProcessor {

    private final ConcurrentMap<String, ApplicationPackProcessingCtx> packProcessingCtxMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Future<?>> processingFutures = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, ApplicationPackProcessingCtx> sharedTopicPackProcessingCtxMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<Future<?>>> sharedSubscriptionsProcessingFutures = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ApplicationPersistedMsgCtx> persistedMsgCtxMap = new ConcurrentHashMap<>();

    private final ApplicationMsgAcknowledgeStrategyFactory acknowledgeStrategyFactory;
    private final ApplicationSubmitStrategyFactory submitStrategyFactory;
    private final ApplicationPersistenceMsgQueueFactory applicationPersistenceMsgQueueFactory;
    private final PublishMsgDeliveryService publishMsgDeliveryService;
    private final TbQueueAdmin queueAdmin;
    private final StatsManager statsManager;
    private final ApplicationPersistedMsgCtxService unacknowledgedPersistedMsgCtxService;
    private final ClientMqttActorManager clientMqttActorManager;
    private final ServiceInfoProvider serviceInfoProvider;
    private final ClientLogger clientLogger;
    private final ApplicationTopicService applicationTopicService;

    private final ExecutorService persistedMsgsConsumerExecutor = Executors.newCachedThreadPool(ThingsBoardThreadFactory.forName("application-persisted-msg-consumers"));

    @Value("${queue.application-persisted-msg.poll-interval}")
    private long pollDuration;
    @Value("${queue.application-persisted-msg.pack-processing-timeout}")
    private long packProcessingTimeout;

    private volatile boolean stopped = false;

    @PostConstruct
    public void init() {
        statsManager.registerActiveApplicationProcessorsStats(processingFutures);
        statsManager.registerActiveSharedApplicationProcessorsStats(sharedSubscriptionsProcessingFutures);
    }

    @Override
    public void processPubAck(String clientId, int packetId) {
        log.trace("[{}] Acknowledged packet {}", clientId, packetId);
        ApplicationPackProcessingCtx processingContext = packProcessingCtxMap.get(clientId);
        if (processingContext == null) {
            log.warn("[{}] Cannot find processing context for client. PacketId - {}.", clientId, packetId);
        } else {
            processingContext.onPubAck(packetId);
        }
    }

    @Override
    public void processPubRec(ClientSessionCtx clientSessionCtx, int packetId) {
        String clientId = clientSessionCtx.getClientId();
        log.trace("[{}] Received packet {}", clientId, packetId);
        ApplicationPackProcessingCtx processingContext = packProcessingCtxMap.get(clientId);
        if (processingContext == null) {
            log.warn("[{}] Cannot find processing context for client. PacketId - {}.", clientId, packetId);
            publishMsgDeliveryService.sendPubRelMsgToClient(clientSessionCtx, packetId);
        } else {
            processingContext.onPubRec(packetId);
        }
    }

    @Override
    public void processPubComp(String clientId, int packetId) {
        log.trace("[{}] Completed packet {}", clientId, packetId);
        ApplicationPackProcessingCtx processingContext = packProcessingCtxMap.get(clientId);
        if (processingContext == null) {
            log.warn("[{}] Cannot find processing context for client. PacketId - {}.", clientId, packetId);
        } else {
            processingContext.onPubComp(packetId);
        }
    }

    // TODO: 16/08/2022 implement
    @Override
    public void startProcessingSharedSubscriptions(ClientSessionCtx clientSessionCtx, Set<SharedSubscriptionTopicFilter> subscriptions) {
        String clientId = clientSessionCtx.getClientId();
        clientLogger.logEvent(clientId, this.getClass(), "Starting processing shared subscriptions persisted messages");
        log.debug("[{}][{}] Starting persisted shared subscriptions processing.", clientId, subscriptions);


        ApplicationPersistedMsgCtx persistedMsgCtx = persistedMsgCtxMap.get(clientId);

        for (SharedSubscriptionTopicFilter subscription : subscriptions) {

            TbQueueControlledOffsetConsumer<TbProtoQueueMsg<PublishMsgProto>> consumer = applicationPersistenceMsgQueueFactory
                    .createConsumerForSharedTopic(
                            MqttApplicationClientUtil.getKafkaTopic(subscription.getTopicFilter()),
                            MqttApplicationClientUtil.getConsumerGroup(subscription),
                            serviceInfoProvider.getServiceId() + "-" + clientId);
            consumer.subscribe();

            Future<?> future = persistedMsgsConsumerExecutor.submit(() -> {
                try {
                    ApplicationProcessorStats stats = statsManager.createSharedApplicationProcessorStats(clientId, subscription);

                    ApplicationPubRelMsgCtx applicationPubRelMsgCtx = new ApplicationPubRelMsgCtx(Sets.newConcurrentHashSet());
                    while (isProcessorActive()) {
                        try {
                            List<TbProtoQueueMsg<PublishMsgProto>> publishProtoMessages = consumer.poll(pollDuration);
                            if (publishProtoMessages.isEmpty() && applicationPubRelMsgCtx.nothingToDeliver()) {
                                continue;
                            }

                            long packProcessingStart = System.nanoTime();
                            ApplicationAckStrategy ackStrategy = acknowledgeStrategyFactory.newInstance(clientId);
                            ApplicationSubmitStrategy submitStrategy = submitStrategyFactory.newInstance(clientId);

                            List<PersistedPubRelMsg> pubRelMessagesToDeliver = applicationPubRelMsgCtx.toSortedPubRelMessagesToDeliver();
                            List<PersistedPublishMsg> publishMessagesToDeliver = toSortedPublishMessagesToDeliver(
                                    clientSessionCtx,
                                    persistedMsgCtx,
                                    publishProtoMessages
                            );

                            List<PersistedMsg> messagesToDeliver = collectMessagesToDeliver(pubRelMessagesToDeliver, publishMessagesToDeliver);
                            submitStrategy.init(messagesToDeliver);

                            applicationPubRelMsgCtx = new ApplicationPubRelMsgCtx(Sets.newConcurrentHashSet());
                            while (isProcessorActive()) {
                                ApplicationPackProcessingCtx ctx = new ApplicationPackProcessingCtx(submitStrategy, applicationPubRelMsgCtx, stats);
                                sharedTopicPackProcessingCtxMap.put(clientId, ctx);

                                process(submitStrategy, clientSessionCtx, clientId);

                                if (isProcessorActive()) {
                                    ctx.await(packProcessingTimeout, TimeUnit.MILLISECONDS);
                                }

                                ApplicationPackProcessingResult result = new ApplicationPackProcessingResult(ctx);
                                ApplicationProcessingDecision decision = ackStrategy.analyze(result);

                                stats.log(ctx.getPublishPendingMsgMap().size(), ctx.getPubRelPendingMsgMap().size(), result, decision.isCommit());

                                if (decision.isCommit()) {
                                    ctx.clear();
                                    consumer.commitSync();
                                    break;
                                } else {
                                    submitStrategy.update(decision.getReprocessMap());
                                }
                            }
                            log.trace("[{}] Pack processing took {} ms, pack size - {}",
                                    clientId, (double) (System.nanoTime() - packProcessingStart) / 1_000_000, messagesToDeliver.size());
                        } catch (Exception e) {
                            if (isProcessorActive()) {
                                log.warn("[{}] Failed to process messages from queue.", clientId, e);
                                try {
                                    Thread.sleep(pollDuration);
                                } catch (InterruptedException e2) {
                                    log.trace("Failed to wait until the server has capacity to handle new requests", e2);
                                }
                            }
                        }
                    }
                    log.debug("[{}] Shared subs Application persisted messages consumer stopped.", clientId);
                } catch (Exception e) {
                    log.warn("[{}][{}] Failed to start processing shared subs messages.", clientId, subscription, e);
                    disconnectClient(clientId, clientSessionCtx.getSessionId());
                } finally {
                    consumer.unsubscribeAndClose();
                }
            });
            List<Future<?>> futures = sharedSubscriptionsProcessingFutures.computeIfAbsent(clientId, s -> new ArrayList<>());
            futures.add(future);
        }
    }

    private void disconnectClient(String clientId, UUID sessionId) {
        disconnectClient(clientId, sessionId, "Failed to start processing shared topic messages");
    }

    @Override
    public void startProcessingPersistedMessages(ClientActorStateInfo clientState) {
        String clientId = clientState.getClientId();
        applicationTopicService.createTopic(clientId);
        clientLogger.logEvent(clientId, this.getClass(), "Starting processing persisted messages");
        log.debug("[{}] Starting persisted messages processing.", clientId);
        TbQueueControlledOffsetConsumer<TbProtoQueueMsg<PublishMsgProto>> consumer = initConsumer(clientId);
        Future<?> future = persistedMsgsConsumerExecutor.submit(() -> {
            try {
                processPersistedMessages(consumer, clientState);
            } catch (Exception e) {
                log.warn("[{}] Failed to start processing persisted messages.", clientId, e);
                disconnectClient(clientId, clientState);
            } finally {
                consumer.unsubscribeAndClose();
            }
        });
        processingFutures.put(clientId, future);
    }

    private void disconnectClient(String clientId, ClientActorStateInfo clientState) {
        disconnectClient(clientId, clientState.getCurrentSessionCtx().getSessionId(), "Failed to start processing persisted messages");
    }

    private void disconnectClient(String clientId, UUID sessionId, String message) {
        clientMqttActorManager.disconnect(clientId, new DisconnectMsg(
                sessionId,
                new DisconnectReason(
                        DisconnectReasonType.ON_ERROR,
                        message)));
    }

    @Override
    public void stopProcessingPersistedMessages(String clientId) {
        log.debug("[{}] Stopping persisted messages processing.", clientId);
        clientLogger.logEvent(clientId, this.getClass(), "Stopping processing persisted messages");
        Future<?> processingFuture = processingFutures.remove(clientId);
        if (processingFuture == null) {
            log.warn("[{}] Cannot find processing future for client.", clientId);
        } else {
            try {
                processingFuture.cancel(true);
                statsManager.clearApplicationProcessorStats(clientId);
            } catch (Exception e) {
                log.warn("[{}] Exception stopping future for client. Exception - {}, reason - {}.", clientId, e.getClass().getSimpleName(), e.getMessage());
            }
        }

        List<Future<?>> futures = sharedSubscriptionsProcessingFutures.remove(clientId);
        if (CollectionUtils.isEmpty(futures)) {
            log.info("[{}] Cannot find shared processing futures for client.", clientId);
        } else {
            try {
                futures.forEach(future -> future.cancel(true));
                statsManager.clearSharedApplicationProcessorStats(clientId);
            } catch (Exception e) {
                log.warn("[{}] Exception stopping future for client. Exception - {}, reason - {}.", clientId, e.getClass().getSimpleName(), e.getMessage());
            }
        }

        // TODO: 01/09/2022 unsubscribe consumer for triggering rebalance...

        ApplicationPackProcessingCtx processingContext = packProcessingCtxMap.remove(clientId);
        ApplicationPackProcessingCtx sharedProcessingContext = sharedTopicPackProcessingCtxMap.remove(clientId);

        unacknowledgedPersistedMsgCtxService.saveContext(clientId, processingContext);
        persistedMsgCtxMap.remove(clientId);
    }

    @Override
    public void stopProcessingSharedSubscriptions(ClientSessionCtx clientSessionCtx, Set<SharedSubscriptionTopicFilter> subscriptions) {
        var clientId = clientSessionCtx.getClientId();
        log.debug("[{}] Stopping processing shared subscriptions.", clientId);
        clientLogger.logEvent(clientId, this.getClass(), "Stopping processing shared subscriptions");

        List<Future<?>> futures = sharedSubscriptionsProcessingFutures.remove(clientId);
        if (CollectionUtils.isEmpty(futures)) {
            log.info("[{}] Cannot find shared processing futures for client.", clientId);
        } else {
            try {
                futures.forEach(future -> future.cancel(true));
                statsManager.clearSharedApplicationProcessorStats(clientId);
            } catch (Exception e) {
                log.warn("[{}] Exception stopping future for client. Exception - {}, reason - {}.", clientId, e.getClass().getSimpleName(), e.getMessage());
            }
        }

        // TODO: 01/09/2022 unsubscribe consumer for triggering rebalance...

        ApplicationPackProcessingCtx processingContext = packProcessingCtxMap.remove(clientId);
        ApplicationPackProcessingCtx sharedProcessingContext = sharedTopicPackProcessingCtxMap.remove(clientId);

        unacknowledgedPersistedMsgCtxService.saveContext(clientId, processingContext);
        persistedMsgCtxMap.remove(clientId);
    }

    @Override
    public void clearPersistedMsgs(String clientId) {
        clientLogger.logEvent(clientId, this.getClass(), "Clearing persisted messages");
        String applicationConsumerGroup = MqttApplicationClientUtil.getConsumerGroup(clientId);
        log.debug("[{}] Clearing consumer group {} for application.", clientId, applicationConsumerGroup);
        // TODO: make async
        queueAdmin.deleteConsumerGroups(Collections.singleton(applicationConsumerGroup));
        log.debug("[{}] Clearing application session context.", clientId);
        unacknowledgedPersistedMsgCtxService.clearContext(clientId);
        persistedMsgCtxMap.remove(clientId);
    }

    // TODO: make async
    private TbQueueControlledOffsetConsumer<TbProtoQueueMsg<PublishMsgProto>> initConsumer(String clientId) {
        TbQueueControlledOffsetConsumer<TbProtoQueueMsg<PublishMsgProto>> consumer = createConsumer(clientId);
        try {
            consumer.assignPartition(0);

            Optional<Long> committedOffset = consumer.getCommittedOffset(consumer.getTopic(), 0);
            if (committedOffset.isEmpty()) {
                long endOffset = consumer.getEndOffset(consumer.getTopic(), 0);
                consumer.commit(0, endOffset);
            }
            return consumer;
        } catch (Exception e) {
            log.error("[{}] Failed to init application client consumer. Reason: {}", clientId, e.getMessage());
            consumer.unsubscribeAndClose();
            throw e;
        }
    }

    private TbQueueControlledOffsetConsumer<TbProtoQueueMsg<PublishMsgProto>> createConsumer(String clientId) {
        return applicationPersistenceMsgQueueFactory
                .createConsumer(
                        MqttApplicationClientUtil.getTopic(clientId),
                        MqttApplicationClientUtil.getConsumerGroup(clientId),
                        serviceInfoProvider.getServiceId() + "-" + clientId);
    }

    private void processPersistedMessages(TbQueueControlledOffsetConsumer<TbProtoQueueMsg<PublishMsgProto>> consumer,
                                          ClientActorStateInfo clientState) {
        ClientSessionCtx clientSessionCtx = clientState.getCurrentSessionCtx();
        String clientId = clientSessionCtx.getClientId();
        UUID sessionId = clientSessionCtx.getSessionId();

        ApplicationProcessorStats stats = statsManager.createApplicationProcessorStats(clientId);

        ApplicationPersistedMsgCtx persistedMsgCtx = unacknowledgedPersistedMsgCtxService.loadPersistedMsgCtx(clientId);
        persistedMsgCtxMap.put(clientId, persistedMsgCtx);

        // TODO: make consistent with logic for DEVICES
        clientSessionCtx.getMsgIdSeq().updateMsgIdSequence(persistedMsgCtx.getLastPacketId());

        ApplicationPubRelMsgCtx applicationPubRelMsgCtx = persistedMsgCtxToPubRelMsgCtx(persistedMsgCtx);

        while (isClientConnected(sessionId, clientState)) {
            try {
                List<TbProtoQueueMsg<PublishMsgProto>> publishProtoMessages = consumer.poll(pollDuration);
                if (publishProtoMessages.isEmpty() && applicationPubRelMsgCtx.nothingToDeliver()) {
                    continue;
                }

                long packProcessingStart = System.nanoTime();
                ApplicationAckStrategy ackStrategy = acknowledgeStrategyFactory.newInstance(clientId);
                ApplicationSubmitStrategy submitStrategy = submitStrategyFactory.newInstance(clientId);

                List<PersistedPubRelMsg> pubRelMessagesToDeliver = applicationPubRelMsgCtx.toSortedPubRelMessagesToDeliver();
                List<PersistedPublishMsg> publishMessagesToDeliver = toSortedPublishMessagesToDeliver(
                        clientSessionCtx,
                        persistedMsgCtx,
                        publishProtoMessages
                );

                List<PersistedMsg> messagesToDeliver = collectMessagesToDeliver(pubRelMessagesToDeliver, publishMessagesToDeliver);
                submitStrategy.init(messagesToDeliver);

                applicationPubRelMsgCtx = new ApplicationPubRelMsgCtx(Sets.newConcurrentHashSet());
                while (isClientConnected(sessionId, clientState)) {
                    ApplicationPackProcessingCtx ctx = new ApplicationPackProcessingCtx(submitStrategy, applicationPubRelMsgCtx, stats);
                    packProcessingCtxMap.put(clientId, ctx);

                    process(submitStrategy, clientSessionCtx, clientId);

                    if (isClientConnected(sessionId, clientState)) {
                        ctx.await(packProcessingTimeout, TimeUnit.MILLISECONDS);
                    }

                    ApplicationPackProcessingResult result = new ApplicationPackProcessingResult(ctx);
                    ApplicationProcessingDecision decision = ackStrategy.analyze(result);

                    stats.log(ctx.getPublishPendingMsgMap().size(), ctx.getPubRelPendingMsgMap().size(), result, decision.isCommit());

                    if (decision.isCommit()) {
                        ctx.clear();
                        consumer.commitSync();
                        break;
                    } else {
                        submitStrategy.update(decision.getReprocessMap());
                    }
                }
                log.trace("[{}] Pack processing took {} ms, pack size - {}",
                        clientId, (double) (System.nanoTime() - packProcessingStart) / 1_000_000, messagesToDeliver.size());
            } catch (Exception e) {
                if (isClientConnected(sessionId, clientState)) {
                    log.warn("[{}] Failed to process messages from queue.", clientId, e);
                    try {
                        Thread.sleep(pollDuration);
                    } catch (InterruptedException e2) {
                        log.trace("Failed to wait until the server has capacity to handle new requests", e2);
                    }
                }
            }
        }
        log.debug("[{}] Application persisted messages consumer stopped.", clientId);
    }

    private void process(ApplicationSubmitStrategy submitStrategy, ClientSessionCtx clientSessionCtx, String clientId) {
        submitStrategy.process(msg -> {
            switch (msg.getPacketType()) {
                case PUBLISH:
                    PublishMsg publishMsg = ((PersistedPublishMsg) msg).getPublishMsg();
                    publishMsgDeliveryService.sendPublishMsgToClient(clientSessionCtx, publishMsg);
                    break;
                case PUBREL:
                    publishMsgDeliveryService.sendPubRelMsgToClient(clientSessionCtx, msg.getPacketId());
                    break;
            }
            clientLogger.logEvent(clientId, this.getClass(), "Delivered msg to application client");
        });
    }

    private List<PersistedMsg> collectMessagesToDeliver(List<PersistedPubRelMsg> pubRelMessagesToDeliver,
                                                        List<PersistedPublishMsg> publishMessagesToDeliver) {
        List<PersistedMsg> messagesToDeliver = new ArrayList<>();
        messagesToDeliver.addAll(pubRelMessagesToDeliver);
        messagesToDeliver.addAll(publishMessagesToDeliver);
        return messagesToDeliver;
    }

    private ApplicationPubRelMsgCtx persistedMsgCtxToPubRelMsgCtx(ApplicationPersistedMsgCtx persistedMsgCtx) {
        return new ApplicationPubRelMsgCtx(
                persistedMsgCtx.getPubRelMsgIds().entrySet().stream()
                        .map(entry -> new PersistedPubRelMsg(entry.getValue(), entry.getKey()))
                        .collect(Collectors.toSet())
        );
    }

    private List<PersistedPublishMsg> toSortedPublishMessagesToDeliver(ClientSessionCtx clientSessionCtx,
                                                                       ApplicationPersistedMsgCtx persistedMsgCtx,
                                                                       List<TbProtoQueueMsg<PublishMsgProto>> publishProtoMessages) {
        return publishProtoMessages.stream()
                .map(msg -> {
                    Integer msgPacketId = persistedMsgCtx.getMsgPacketId(msg.getOffset());
                    int packetId = msgPacketId != null ? msgPacketId : clientSessionCtx.getMsgIdSeq().nextMsgId();
                    boolean isDup = msgPacketId != null;
                    PublishMsgProto persistedMsgProto = msg.getValue();
                    PublishMsg publishMsg = toPubMsg(persistedMsgProto, packetId, isDup);
                    return new PersistedPublishMsg(publishMsg, msg.getOffset());
                })
                .sorted(Comparator.comparingLong(PersistedPublishMsg::getPacketOffset))
                .collect(Collectors.toList());
    }

    private PublishMsg toPubMsg(PublishMsgProto persistedMsgProto, int packetId, boolean isDup) {
        return ProtoConverter.convertToPublishMsg(persistedMsgProto).toBuilder()
                .packetId(packetId)
                .isDup(isDup)
                .build();
    }

    private boolean isClientConnected(UUID sessionId, ClientActorStateInfo clientState) {
        return isProcessorActive()
                && clientState.getCurrentSessionId().equals(sessionId)
                && clientState.getCurrentSessionState() == SessionState.CONNECTED;
    }

    private boolean isProcessorActive() {
        return !stopped && !Thread.interrupted();
    }

    @PreDestroy
    public void destroy() {
        stopped = true;
        sharedSubscriptionsProcessingFutures.forEach((clientId, futures) -> futures.forEach(f -> f.cancel(true)));
        processingFutures.forEach((clientId, future) -> {
            future.cancel(true);
            log.info("[{}] Saving processing context before shutting down.", clientId);
            ApplicationPackProcessingCtx processingContext = packProcessingCtxMap.remove(clientId);
            ApplicationPackProcessingCtx sharedProcessingContext = sharedTopicPackProcessingCtxMap.remove(clientId);
            try {
                unacknowledgedPersistedMsgCtxService.saveContext(clientId, processingContext);
            } catch (Exception e) {
                log.warn("[{}] Failed to save APPLICATION context.", clientId);
            }
        });
        persistedMsgsConsumerExecutor.shutdown();
        try {
            boolean terminationSuccessful = persistedMsgsConsumerExecutor.awaitTermination(3, TimeUnit.SECONDS);
            log.info("Application persistence consumers' executor termination is: [{}]", terminationSuccessful ? "successful" : "failed");
        } catch (InterruptedException e) {
            log.warn("Failed to stop application persistence consumers' executor gracefully due to interruption!", e);
        }
    }
}
