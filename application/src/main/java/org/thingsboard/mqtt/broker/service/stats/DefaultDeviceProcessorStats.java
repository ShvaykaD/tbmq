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
package org.thingsboard.mqtt.broker.service.stats;

import lombok.extern.slf4j.Slf4j;
import org.thingsboard.mqtt.broker.common.stats.ResettableTimer;
import org.thingsboard.mqtt.broker.common.stats.StatsCounter;
import org.thingsboard.mqtt.broker.common.stats.StatsFactory;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.device.processing.DevicePackProcessingResult;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.thingsboard.mqtt.broker.common.stats.StatsConstantNames.CONSUMER_ID_TAG;
import static org.thingsboard.mqtt.broker.common.stats.StatsConstantNames.FAILED_ITERATIONS;
import static org.thingsboard.mqtt.broker.common.stats.StatsConstantNames.FAILED_MSGS;
import static org.thingsboard.mqtt.broker.common.stats.StatsConstantNames.SUCCESSFUL_ITERATIONS;
import static org.thingsboard.mqtt.broker.common.stats.StatsConstantNames.SUCCESSFUL_MSGS;
import static org.thingsboard.mqtt.broker.common.stats.StatsConstantNames.TIMEOUT_MSGS;
import static org.thingsboard.mqtt.broker.common.stats.StatsConstantNames.TMP_FAILED;
import static org.thingsboard.mqtt.broker.common.stats.StatsConstantNames.TMP_TIMEOUT;
import static org.thingsboard.mqtt.broker.common.stats.StatsConstantNames.TOTAL_MSGS;

@Slf4j
public class DefaultDeviceProcessorStats implements DeviceProcessorStats {
    private final String consumerId;

    private final List<StatsCounter> counters;

    private final StatsCounter totalMsgCounter;
    private final StatsCounter successMsgCounter;

    private final StatsCounter tmpTimeoutMsgCounter;
    private final StatsCounter tmpFailedMsgCounter;

    private final StatsCounter timeoutMsgCounter;
    private final StatsCounter failedMsgCounter;

    private final StatsCounter successIterationsCounter;
    private final StatsCounter failedIterationsCounter;

    private final ResettableTimer clientIdPackProcessingTimer;
    private final ResettableTimer packProcessingTimer;

    private final AtomicLong totalPackSize = new AtomicLong();

    public DefaultDeviceProcessorStats(String consumerId, StatsFactory statsFactory) {
        this.consumerId = consumerId;
        String statsKey = StatsType.DEVICE_PROCESSOR.getPrintName();
        this.totalMsgCounter = statsFactory.createStatsCounter(statsKey, TOTAL_MSGS, CONSUMER_ID_TAG, consumerId);
        this.successMsgCounter = statsFactory.createStatsCounter(statsKey, SUCCESSFUL_MSGS, CONSUMER_ID_TAG, consumerId);
        this.tmpTimeoutMsgCounter = statsFactory.createStatsCounter(statsKey, TMP_TIMEOUT, CONSUMER_ID_TAG, consumerId);
        this.tmpFailedMsgCounter = statsFactory.createStatsCounter(statsKey, TMP_FAILED, CONSUMER_ID_TAG, consumerId);
        this.timeoutMsgCounter = statsFactory.createStatsCounter(statsKey, TIMEOUT_MSGS, CONSUMER_ID_TAG, consumerId);
        this.failedMsgCounter = statsFactory.createStatsCounter(statsKey, FAILED_MSGS, CONSUMER_ID_TAG, consumerId);
        this.successIterationsCounter = statsFactory.createStatsCounter(statsKey, SUCCESSFUL_ITERATIONS, CONSUMER_ID_TAG, consumerId);
        this.failedIterationsCounter = statsFactory.createStatsCounter(statsKey, FAILED_ITERATIONS, CONSUMER_ID_TAG, consumerId);

        counters = List.of(totalMsgCounter, successMsgCounter, timeoutMsgCounter, failedMsgCounter, tmpTimeoutMsgCounter, tmpFailedMsgCounter,
                successIterationsCounter, failedIterationsCounter);

        this.clientIdPackProcessingTimer = new ResettableTimer(statsFactory.createTimer(statsKey + ".processing.time", CONSUMER_ID_TAG, consumerId));
        this.packProcessingTimer = new ResettableTimer(statsFactory.createTimer(statsKey + ".pack.processing.time", CONSUMER_ID_TAG, consumerId));
    }

    @Override
    public String getConsumerId() {
        return consumerId;
    }

    @Override
    public void log(int totalMessagesCount, DevicePackProcessingResult result, boolean finalIterationForPack) {
        int pending = result.getPendingMap().values().stream().mapToInt(pack -> pack.messages().size()).sum();
        int failed = result.getFailedMap().values().stream().mapToInt(pack -> pack.messages().size()).sum();
        int success = totalMessagesCount - (pending + failed);
        totalMsgCounter.add(totalMessagesCount);
        successMsgCounter.add(success);
        if (finalIterationForPack) {
            if (pending > 0 || failed > 0) {
                timeoutMsgCounter.add(pending);
                failedMsgCounter.add(failed);
                failedIterationsCounter.increment();
            } else {
                successIterationsCounter.increment();
            }
        } else {
            failedIterationsCounter.increment();
            tmpTimeoutMsgCounter.add(pending);
            tmpFailedMsgCounter.add(failed);
        }
    }

    @Override
    public void logClientIdPackProcessingTime(long amount, TimeUnit unit) {
        clientIdPackProcessingTimer.logTime(amount, unit);
    }

    @Override
    public void logClientIdPacksProcessingTime(int packSize, long amount, TimeUnit unit) {
        packProcessingTimer.logTime(amount, unit);
        totalPackSize.addAndGet(packSize);
    }

    @Override
    public List<StatsCounter> getStatsCounters() {
        return counters;
    }

    @Override
    public double getAvgClientIdPackProcessingTime() {
        return clientIdPackProcessingTimer.getAvg();
    }

    @Override
    public double getAvgClientIdPacksProcessingTime() {
        return packProcessingTimer.getAvg();
    }

    @Override
    public double getAvgPackSize() {
        return Math.ceil((double) totalPackSize.get() / packProcessingTimer.getCount());
    }

    @Override
    public void reset() {
        counters.forEach(StatsCounter::clear);
        clientIdPackProcessingTimer.reset();
        packProcessingTimer.reset();
        totalPackSize.getAndSet(0);
    }
}
