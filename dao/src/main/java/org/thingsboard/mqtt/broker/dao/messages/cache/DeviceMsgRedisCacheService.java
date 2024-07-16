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
package org.thingsboard.mqtt.broker.dao.messages.cache;

import com.google.common.util.concurrent.ListenableFuture;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.jedis.JedisClusterConnection;
import org.springframework.data.redis.connection.jedis.JedisConnection;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.common.data.DevicePublishMsg;
import org.thingsboard.mqtt.broker.common.util.JacksonUtil;
import org.thingsboard.mqtt.broker.dao.messages.DeviceMsgCacheService;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.util.JedisClusterCRC16;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "cache", value = "type", havingValue = "redis")
@RequiredArgsConstructor
// TODO: fix this depends on and lazy.
@DependsOn("redisConnectionFactory")
@Lazy
public class DeviceMsgRedisCacheService implements DeviceMsgCacheService {

    @Value("${mqtt.persistent-session.device.persisted-messages.ttl}")
    private long defaultTtl;

    @Value("${mqtt.persistent-session.device.persisted-messages.limit}")
    private int messagesLimit;

    private final JedisConnectionFactory connectionFactory;
    private final RedisSerializer<String> keySerializer = StringRedisSerializer.UTF_8;
//    private final TbRedisSerializer<ClientIdMessageCacheKey, DevicePublishMsg> valueSerializer = new TbJsonRedisSerializer<>(DevicePublishMsg.class);

    private static final JedisPool MOCK_POOL = new JedisPool(); //non-null pool required for JedisConnection to trigger closing jedis connection
    private static final byte[] ADD_MESSAGES_SCRIPT_SHA = StringRedisSerializer.UTF_8.serialize("23181cc863a84f5f79a97ec62a62dbfb0bd2b383");
    private static final byte[] GET_MESSAGES_WITH_LIMIT_SHA = StringRedisSerializer.UTF_8.serialize("bddb58430b9f88b5abd7bace19e03c6c513eb389");
    private static final byte[] GET_MESSAGES_IN_RANGE_SCRIPT_SHA = StringRedisSerializer.UTF_8.serialize("e5725b9abdc4492698326c5889af794452fcae1c");
    private static final byte[] REMOVE_MESSAGES_SCRIPT_SHA = StringRedisSerializer.UTF_8.serialize("e51149bafd39f274e1d827dabd3aa685e1c39a6e");
    private static final byte[] REMOVE_MESSAGE_SCRIPT_SHA = StringRedisSerializer.UTF_8.serialize("7c8f317ec74ce98def9983d46b74b6d39ce8f8ed");

    @PostConstruct
    public void init() {
        // TODO: load lua scripts. Consider if the connection should be the same for all scripts. If so what we should use as a key?
        try (var connection = getConnection(ADD_MESSAGES_SCRIPT_SHA)) {
            loadAddMessagesScript(connection);
            loadGetMessagesWithLimitScript(connection);
            loadGetMessagesInRangeScript(connection);
            loadDeleteMessagesScript(connection);
            loadDeleteMessageScript(connection);
        } catch (Throwable t) {
            log.error("Failed to init persisted device messages cache service!", t);
            // TODO: consider to throw an exception
        }
    }

    @Override
    public void save(List<DevicePublishMsg> devicePublishMessages, boolean failOnConflict) {
        var clientIdToMsgList = new HashMap<String, List<DevicePublishMsgRedisEntity>>();
        for (DevicePublishMsg devicePublishMessage : devicePublishMessages) {
            var redisEntity = new DevicePublishMsgRedisEntity(devicePublishMessage, defaultTtl);
            clientIdToMsgList.computeIfAbsent(devicePublishMessage.getClientId(), k -> new ArrayList<>()).add(redisEntity);
        }
        clientIdToMsgList.forEach((clientId, messages) -> {
            byte[] rawLastPacketIdKey = toLastPacketIdKey(clientId);
            byte[] rawMessagesKey = toMessagesCacheKey(clientId);
            byte[] messagesBytes = JacksonUtil.writeValueAsBytes(messages);
            try (var connection = getConnection(rawMessagesKey)) {
                try {
                    connection.scriptingCommands().evalSha(
                            Objects.requireNonNull(ADD_MESSAGES_SCRIPT_SHA),
                            ReturnType.VALUE,
                            2,
                            rawMessagesKey,
                            rawLastPacketIdKey,
                            messagesBytes
                    );
                } catch (Exception e) {
                    // TODO throw and exception
                    log.error("error while saving device publish messages!", e);
                }
            }
        });
    }

    @Override
    public List<DevicePublishMsg> findPersistedMessages(String clientId) {
        byte[] rawMessagesKey = toMessagesCacheKey(clientId);

        try (var connection = getConnection(rawMessagesKey)) {
            try {
                List<byte[]> messagesBytes = connection.scriptingCommands().evalSha(
                        Objects.requireNonNull(GET_MESSAGES_WITH_LIMIT_SHA),
                        ReturnType.MULTI,
                        1,
                        rawMessagesKey
                );
                return Objects.requireNonNull(messagesBytes)
                        .stream().map(DeviceMsgRedisCacheService::toData)
                        .toList();
            } catch (Exception e) {
                // TODO throw and exception
                log.error("Error while search for device publish messages with limit!", e);
                return Collections.emptyList();
            }
        }
    }

    @Override
    public List<DevicePublishMsg> findPersistedMessages(String clientId, long fromPacketId, long toPacketId) {
        byte[] rawMessagesKey = toMessagesCacheKey(clientId);
        byte[] fromPacketIdBytes = keySerializer.serialize(Long.toString(fromPacketId));
        byte[] toPacketIdBytes = keySerializer.serialize(Long.toString(toPacketId));

        try (var connection = getConnection(rawMessagesKey)) {
            try {
                List<byte[]> messagesBytes = connection.scriptingCommands().evalSha(
                        Objects.requireNonNull(GET_MESSAGES_IN_RANGE_SCRIPT_SHA),
                        ReturnType.MULTI,
                        1,
                        rawMessagesKey,
                        fromPacketIdBytes,
                        toPacketIdBytes
                );
                return Objects.requireNonNull(messagesBytes)
                        .stream().map(DeviceMsgRedisCacheService::toData)
                        .toList();
            } catch (Exception e) {
                // TODO throw and exception
                log.error("Error while search for device publish messages in range!", e);
                return Collections.emptyList();
            }
        }
    }

    @Override
    public void removePersistedMessages(String clientId) {
        byte[] rawMessagesKey = toMessagesCacheKey(clientId);

        try (var connection = getConnection(rawMessagesKey)) {
            try {
                connection.scriptingCommands().evalSha(
                        Objects.requireNonNull(REMOVE_MESSAGES_SCRIPT_SHA),
                        ReturnType.VALUE,
                        1,
                        rawMessagesKey
                );
            } catch (Exception e) {
                // TODO throw and exception
                log.error("Error while remove of device publish messages!", e);
            }
        }
    }

    @Override
    public void removePersistedMessage(String clientId, int packetId) {
        byte[] rawMessagesKey = toMessagesCacheKey(clientId);
        byte[] packetIdBytes = keySerializer.serialize(Long.toString(packetId));

        try (var connection = getConnection(rawMessagesKey)) {
            try {
                connection.scriptingCommands().evalSha(
                        Objects.requireNonNull(REMOVE_MESSAGES_SCRIPT_SHA),
                        ReturnType.VALUE,
                        1,
                        rawMessagesKey,
                        packetIdBytes
                );
            } catch (Exception e) {
                // TODO throw and exception
                log.error("Error while remove of device publish message!", e);
            }
        }
    }

    @Override
    public ListenableFuture<Void> tryUpdatePacketReceived(String clientId, int packetId) {
        return null;
    }

    private static DevicePublishMsg toData(byte[] bytes) {
        return Objects.requireNonNull(JacksonUtil.fromBytes(bytes, DevicePublishMsgRedisEntity.class)).toData();
    }

    private byte[] toMessagesCacheKey(String clientId) {
        ClientIdMessagesCacheKey clientIdMessagesCacheKey = new ClientIdMessagesCacheKey(clientId);
        String stringValue = clientIdMessagesCacheKey.toString();
        byte[] rawKey;
        try {
            rawKey = keySerializer.serialize(stringValue);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (rawKey == null) {
            throw new IllegalArgumentException("[" + clientId + "] Failed to serialize the messages cache key!");
        }
        return rawKey;
    }

    private byte[] toLastPacketIdKey(String clientId) {
        ClientIdLastPacketIdCacheKey clientIdLastPacketIdCacheKey = new ClientIdLastPacketIdCacheKey(clientId);
        String stringValue = clientIdLastPacketIdCacheKey.toString();
        byte[] rawKey;
        try {
            rawKey = keySerializer.serialize(stringValue);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (rawKey == null) {
            throw new IllegalArgumentException("[" + clientId + "] Failed to serialize the last info cache key!");
        }
        return rawKey;
    }

    private RedisConnection getConnection(byte[] rawKey) {
        if (!connectionFactory.isRedisClusterAware()) {
            return connectionFactory.getConnection();
        }
        RedisConnection connection = connectionFactory.getClusterConnection();

        int slotNum = JedisClusterCRC16.getSlot(rawKey);
        Jedis jedis = new Jedis((((JedisClusterConnection) connection).getNativeConnection().getConnectionFromSlot(slotNum)));

        JedisConnection jedisConnection = new JedisConnection(jedis, MOCK_POOL, jedis.getDB());
        jedisConnection.setConvertPipelineAndTxResults(connectionFactory.getConvertPipelineAndTxResults());

        return jedisConnection;
    }

    private void loadGetMessagesInRangeScript(RedisConnection connection) {
        String scriptShaStr = new String(Objects.requireNonNull(GET_MESSAGES_IN_RANGE_SCRIPT_SHA));
        log.debug("Loading LUA with expected SHA [{}], connection [{}]", scriptShaStr, connection.getNativeConnection());
        byte[] scriptBytes = StringRedisSerializer.UTF_8.serialize("""
                local messagesKey = KEYS[1]
                local fromPacketId = ARGV[1]
                local toPacketId = ARGV[2]
                
                -- Get the range of elements from the sorted set
                local elements = redis.call('ZRANGEBYSCORE', messagesKey, fromPacketId, '(' .. toPacketId)
                local messages = {}
                
                for _, key in ipairs(elements) do
                    -- Check if the key still exists
                    if redis.call('EXISTS', key) == 1 then
                        local msgJson = redis.call('GET', key)
                        table.insert(messages, msgJson)
                    else
                        -- If the key does not exist, remove it from the sorted set
                        redis.call('ZREM', messagesKey, key)
                    end
                end
                
                return messages
                """);
        String actualScriptSha = connection.scriptingCommands().scriptLoad(Objects.requireNonNull(scriptBytes));
        validateSha(GET_MESSAGES_IN_RANGE_SCRIPT_SHA, scriptShaStr, actualScriptSha, connection);
    }

    private void loadAddMessagesScript(RedisConnection connection) {
        String scriptShaStr = new String(Objects.requireNonNull(ADD_MESSAGES_SCRIPT_SHA));
        log.debug("Loading LUA with expected SHA [{}], connection [{}]", scriptShaStr, connection.getNativeConnection());
        byte[] scriptBytes = StringRedisSerializer.UTF_8.serialize("""
                local messagesKey = KEYS[1]
                local lastPacketIdKey = KEYS[2]
                local maxMessagesSize = %d  -- This will be set from the Java
                local messages = cjson.decode(ARGV[1])
                
                -- Fetch the last packetId from the key-value store
                local lastPacketId = tonumber(redis.call('GET', lastPacketIdKey)) or 0
                
                -- Add each message to the sorted set and as a separate key
                for _, msg in ipairs(messages) do
                    lastPacketId = lastPacketId + 1
                
                    if lastPacketId > 0xffff then
                        lastPacketId = 1
                    end
                
                    msg.packetId = lastPacketId
                
                    local msgKey = messagesKey .. "_" .. lastPacketId
                    local msgJson = cjson.encode(msg)
                
                    -- Store the message as a separate key with TTL
                    redis.call('SET', msgKey, msgJson, 'EX', msg.msgExpiryInterval)
                
                    -- Add the key to the sorted set using packetId as the score
                    redis.call('ZADD', messagesKey, lastPacketId, msgKey)
                end
                
                -- Update the last packetId in the key-value store
                redis.call('SET', lastPacketIdKey, lastPacketId)
                
                -- Get the elements to be trimmed
                local numElementsToRemove = redis.call('ZCARD', messagesKey) - maxMessagesSize
                if numElementsToRemove > 0 then
                    local trimmedElements = redis.call('ZRANGE', messagesKey, 0, numElementsToRemove - 1)
                    for _, key in ipairs(trimmedElements) do
                        redis.call('DEL', key)
                        redis.call('ZREM', messagesKey, key)
                    end
                end
                
                return nil
                """.formatted(messagesLimit));
        String actualScriptSha = connection.scriptingCommands().scriptLoad(Objects.requireNonNull(scriptBytes));
        validateSha(ADD_MESSAGES_SCRIPT_SHA, scriptShaStr, actualScriptSha, connection);
    }

    private void loadGetMessagesWithLimitScript(RedisConnection connection) {
        String scriptShaStr = new String(Objects.requireNonNull(GET_MESSAGES_WITH_LIMIT_SHA));
        log.debug("Loading LUA with expected SHA [{}], connection [{}]", scriptShaStr, connection.getNativeConnection());
        byte[] scriptBytes = StringRedisSerializer.UTF_8.serialize("""
                local messagesKey = KEYS[1]
                local maxMessagesSize = %d  -- This will be set from the Java
                
                -- Get the range of elements from the sorted set
                local elements = redis.call('ZRANGE', messagesKey, 0, maxMessagesSize - 1)
                local messages = {}
                
                for _, key in ipairs(elements) do
                    -- Check if the key still exists
                    if redis.call('EXISTS', key) == 1 then
                        local msgJson = redis.call('GET', key)
                        table.insert(messages, msgJson)
                    else
                        -- If the key does not exist, remove it from the sorted set
                        redis.call('ZREM', messagesKey, key)
                    end
                end
                
                return messages
                """.formatted(messagesLimit));
        String actualScriptSha = connection.scriptingCommands().scriptLoad(Objects.requireNonNull(scriptBytes));
        validateSha(GET_MESSAGES_WITH_LIMIT_SHA, scriptShaStr, actualScriptSha, connection);
    }

    private void loadDeleteMessagesScript(RedisConnection connection) {
        String scriptShaStr = new String(Objects.requireNonNull(REMOVE_MESSAGES_SCRIPT_SHA));
        log.debug("Loading LUA with expected SHA [{}], connection [{}]", scriptShaStr, connection.getNativeConnection());
        byte[] scriptBytes = StringRedisSerializer.UTF_8.serialize("""
                local messagesKey = KEYS[1]
                
                -- Get all elements from the sorted set
                local elements = redis.call('ZRANGE', messagesKey, 0, -1)
                
                -- Delete each associated hash entry
                for _, key in ipairs(elements) do
                    redis.call('DEL', key)
                end
                
                -- Delete the sorted set
                redis.call('DEL', messagesKey)
                
                return nil
                """);
        String actualScriptSha = connection.scriptingCommands().scriptLoad(Objects.requireNonNull(scriptBytes));
        validateSha(REMOVE_MESSAGES_SCRIPT_SHA, scriptShaStr, actualScriptSha, connection);
    }

    private void loadDeleteMessageScript(RedisConnection connection) {
        String scriptShaStr = new String(Objects.requireNonNull(REMOVE_MESSAGE_SCRIPT_SHA));
        log.debug("Loading LUA with expected SHA [{}], connection [{}]", scriptShaStr, connection.getNativeConnection());
        byte[] scriptBytes = StringRedisSerializer.UTF_8.serialize("""
                local messagesKey = KEYS[1]
                local packetId = ARGV[1]
                
                -- Construct the message key
                local msgKey = messagesKey .. "_" .. packetId
                
                -- Remove the message from the sorted set
                redis.call('ZREM', messagesKey, msgKey)
                
                -- Delete the message key
                redis.call('DEL', msgKey)
                
                return nil
                """);
        String actualScriptSha = connection.scriptingCommands().scriptLoad(Objects.requireNonNull(scriptBytes));
        validateSha(REMOVE_MESSAGE_SCRIPT_SHA, scriptShaStr, actualScriptSha, connection);
    }

    private static void validateSha(byte[] expectedSha, String expectedShaStr, String actualAddMessagesScriptSha, RedisConnection connection) {
        if (!Arrays.equals(expectedSha, StringRedisSerializer.UTF_8.serialize(actualAddMessagesScriptSha))) {
            log.error("SHA for SCRIPT wrong! Expected [{}], but actual [{}], connection [{}]", expectedShaStr, actualAddMessagesScriptSha, connection.getNativeConnection());
            // TODO: consider to throw an exception
        }
    }

}
