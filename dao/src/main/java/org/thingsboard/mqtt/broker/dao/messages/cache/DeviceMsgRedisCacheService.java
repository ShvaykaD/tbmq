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

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
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
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "cache", value = "type", havingValue = "redis")
public class DeviceMsgRedisCacheService implements DeviceMsgCacheService {

    private static final JedisPool MOCK_POOL = new JedisPool(); //non-null pool required for JedisConnection to trigger closing jedis connection

    private static final RedisSerializer<String> stringSerializer = StringRedisSerializer.UTF_8;

    private static final byte[] ADD_MESSAGES_SCRIPT_SHA = stringSerializer.serialize("1ef0ffe33f7a249800590b3b0e930ca904007e9e");
    private static final byte[] GET_MESSAGES_SCRIPT_SHA = stringSerializer.serialize("71f49c1fb159cd634e17f9a46e3d8f0a2fb51c1f");
    private static final byte[] REMOVE_MESSAGES_SCRIPT_SHA = stringSerializer.serialize("a619f42eb693ea732763d878dd59dff513a295c7");
    private static final byte[] REMOVE_MESSAGE_SCRIPT_SHA = stringSerializer.serialize("038e09c6e313eab0d5be4f31361250f4179bc38c");
    private static final byte[] UPDATE_PACKET_TYPE_SCRIPT_SHA = stringSerializer.serialize("958139aa4015911c82ddd423ff408b6638805081");

    private static final String ADD_MESSAGES_SCRIPT_WITHOUT_FORMAT = """
            local messagesKey = KEYS[1]
            local lastPacketIdKey = KEYS[2]
            local maxMessagesSize = %d  -- This will be set from the Java
            local messages = cjson.decode(ARGV[1])
            -- Fetch the last packetId from the key-value store
            local lastPacketId = tonumber(redis.call('GET', lastPacketIdKey)) or 0
            -- Initialize the score with the last packet ID value
            local score = lastPacketId
            -- Track the first packet ID
            local previousPacketId = lastPacketId
            -- Add each message to the sorted set and as a separate key
            for _, msg in ipairs(messages) do
                lastPacketId = lastPacketId + 1
                if lastPacketId > 0xffff then
                    lastPacketId = 1
                end
                msg.packetId = lastPacketId
                score = score + 1
                local msgKey = messagesKey .. "_" .. lastPacketId
                local msgJson = cjson.encode(msg)
                -- Store the message as a separate key with TTL
                redis.call('SET', msgKey, msgJson, 'EX', msg.msgExpiryInterval)
                -- Add the key to the sorted set using packetId as the score
                redis.call('ZADD', messagesKey, score, msgKey)
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
            return previousPacketId
            """;
    public static final String GET_MESSAGES_SCRIPT_WITHOUT_FORMAT = """
            local messagesKey = KEYS[1]
            local maxMessagesSize = %d  -- This will be set from the Java
            -- Get the range of elements from the sorted set
            local elements = redis.call('ZRANGE', messagesKey, 0, -1)
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
            """;

    private static final byte[] REMOVE_MESSAGES_SCRIPT = stringSerializer.serialize("""
            local messagesKey = KEYS[1]
            local lastPacketIdKey = KEYS[2]
            -- Get all elements from the sorted set
            local elements = redis.call('ZRANGE', messagesKey, 0, -1)
            -- Delete each associated key entry
            for _, key in ipairs(elements) do
                redis.call('DEL', key)
            end
            -- Delete the sorted set
            redis.call('DEL', messagesKey)
            -- Delete the last packet id key
            redis.call('DEL', lastPacketIdKey)
            return nil
            """);
    private static final byte[] REMOVE_MESSAGE_SCRIPT = stringSerializer.serialize("""
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
    private static final byte[] UPDATE_PACKET_TYPE_SCRIPT = stringSerializer.serialize("""
            local messagesKey = KEYS[1]
            local packetId = ARGV[1]
            -- Construct the message key
            local msgKey = messagesKey .. "_" .. packetId
            -- Fetch the message from the key-value store
            local msgJson = redis.call('GET', msgKey)
            if not msgJson then
                return nil -- Message not found
            end
            -- Decode the JSON message
            local msg = cjson.decode(msgJson)
            -- Update the packet type
            msg.packetType = "PUBREL"
            -- Encode the updated message back to JSON
            local updatedMsgJson = cjson.encode(msg)
            -- Save the updated message back to the key-value store
            redis.call('SET', msgKey, updatedMsgJson)
            return nil
            """);

    @Value("${mqtt.persistent-session.device.persisted-messages.ttl}")
    private long defaultTtl;

    @Value("${mqtt.persistent-session.device.persisted-messages.limit}")
    private int messagesLimit;

    private byte[] addMessagesScript;
    private byte[] getMessagesScript;

    private final JedisConnectionFactory connectionFactory;

    public DeviceMsgRedisCacheService(RedisConnectionFactory redisConnectionFactory) {
        this.connectionFactory = (JedisConnectionFactory) redisConnectionFactory;
    }

    @PostConstruct
    public void init() {
        if (messagesLimit > 0xffff) {
            throw new IllegalArgumentException("Persisted messages limit can't be greater than 65535!");
        }
        try (var connection = getNonClusterAwareConnection()) {
            loadAddMessagesScript(connection);
            loadGetMessagesScript(connection);
            loadDeleteMessagesScript(connection);
            loadDeleteMessageScript(connection);
            loadUpdatePacketTypeScript(connection);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to init persisted device messages cache service!", t);
        }
    }

    @Override
    public long saveAndReturnPreviousPacketId(String clientId, List<DevicePublishMsg> devicePublishMessages, boolean failOnConflict) {
        if (log.isTraceEnabled()) {
            log.trace("Save persisted messages, clientId - {}, devicePublishMessages size - {}", clientId, devicePublishMessages.size());
        }
        var messages = devicePublishMessages.stream()
                .map(devicePublishMsg -> new DevicePublishMsgRedisEntity(devicePublishMsg, defaultTtl))
                .collect(Collectors.toCollection(ArrayList::new));
        byte[] rawLastPacketIdKey = toLastPacketIdKey(clientId);
        byte[] rawMessagesKey = toMessagesCacheKey(clientId);
        byte[] messagesBytes = JacksonUtil.writeValueAsBytes(messages);
        try (var connection = getConnection(rawMessagesKey)) {
            try {
                return Objects.requireNonNull(connection.scriptingCommands().evalSha(
                        Objects.requireNonNull(ADD_MESSAGES_SCRIPT_SHA),
                        ReturnType.INTEGER,
                        2,
                        rawMessagesKey,
                        rawLastPacketIdKey,
                        messagesBytes
                ));
            } catch (InvalidDataAccessApiUsageException e) {
                log.debug("Slowly executing eval instead of fast evalSha [{}] due to exception throwing on sha evaluation: ", connection, e);
                return Objects.requireNonNull(connection.scriptingCommands().eval(
                        Objects.requireNonNull(addMessagesScript),
                        ReturnType.INTEGER,
                        2,
                        rawMessagesKey,
                        rawLastPacketIdKey,
                        messagesBytes
                ));
            }
        }
    }

    @Override
    public List<DevicePublishMsg> findPersistedMessages(String clientId) {
        if (log.isTraceEnabled()) {
            log.trace("Find persisted messages, clientId - {}", clientId);
        }
        byte[] rawMessagesKey = toMessagesCacheKey(clientId);
        try (var connection = getConnection(rawMessagesKey)) {
            List<byte[]> messagesBytes;
            try {
                messagesBytes = connection.scriptingCommands().evalSha(
                        Objects.requireNonNull(GET_MESSAGES_SCRIPT_SHA),
                        ReturnType.MULTI,
                        1,
                        rawMessagesKey
                );
            } catch (InvalidDataAccessApiUsageException e) {
                log.debug("Slowly executing eval instead of fast evalSha [{}] due to exception throwing on sha evaluation: ", connection, e);
                messagesBytes = connection.scriptingCommands().eval(
                        Objects.requireNonNull(getMessagesScript),
                        ReturnType.MULTI,
                        1,
                        rawMessagesKey
                );
            }
            return Objects.requireNonNull(messagesBytes)
                    .stream().map(DeviceMsgRedisCacheService::toData)
                    .toList();
        }
    }

    @Override
    public void removePersistedMessages(String clientId) {
        if (log.isTraceEnabled()) {
            log.trace("Removing persisted messages, clientId - {}", clientId);
        }
        byte[] rawMessagesKey = toMessagesCacheKey(clientId);
        byte[] rawLastPacketIdKey = toLastPacketIdKey(clientId);
        try (var connection = getConnection(rawMessagesKey)) {
            try {
                connection.scriptingCommands().evalSha(
                        Objects.requireNonNull(REMOVE_MESSAGES_SCRIPT_SHA),
                        ReturnType.VALUE,
                        2,
                        rawMessagesKey,
                        rawLastPacketIdKey
                );
            } catch (InvalidDataAccessApiUsageException e) {
                log.debug("Slowly executing eval instead of fast evalSha [{}] due to exception throwing on sha evaluation: ", connection, e);
                connection.scriptingCommands().eval(
                        Objects.requireNonNull(REMOVE_MESSAGES_SCRIPT),
                        ReturnType.VALUE,
                        2,
                        rawMessagesKey,
                        rawLastPacketIdKey
                );
            }
        } catch (Exception e) {
            log.error("Failed to remove persisted messages, clientId - {}", clientId, e);
        }
    }

    @Override
    public void removePersistedMessage(String clientId, int packetId) {
        if (log.isTraceEnabled()) {
            log.trace("Removing persisted message, clientId - {}, packetId - {}", clientId, packetId);
        }
        byte[] rawMessagesKey = toMessagesCacheKey(clientId);
        byte[] packetIdBytes = intToBytes(packetId);
        try (var connection = getConnection(rawMessagesKey)) {
            try {
                connection.scriptingCommands().evalSha(
                        Objects.requireNonNull(REMOVE_MESSAGE_SCRIPT_SHA),
                        ReturnType.VALUE,
                        1,
                        rawMessagesKey,
                        packetIdBytes
                );
            } catch (InvalidDataAccessApiUsageException e) {
                log.debug("Slowly executing eval instead of fast evalSha [{}] due to exception throwing on sha evaluation: ", connection, e);
                connection.scriptingCommands().eval(
                        Objects.requireNonNull(REMOVE_MESSAGE_SCRIPT),
                        ReturnType.VALUE,
                        1,
                        rawMessagesKey,
                        packetIdBytes
                );
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to remove persisted message, clientId - " + clientId + " packetId - " + packetId, e);
        }
    }

    @Override
    public void updatePacketReceived(String clientId, int packetId) {
        if (log.isTraceEnabled()) {
            log.trace("Update packet received, clientId - {}, packetId - {}", clientId, packetId);
        }
        byte[] rawMessagesKey = toMessagesCacheKey(clientId);
        byte[] packetIdBytes = intToBytes(packetId);
        try (var connection = getConnection(rawMessagesKey)) {
            try {
                connection.scriptingCommands().evalSha(
                        Objects.requireNonNull(UPDATE_PACKET_TYPE_SCRIPT_SHA),
                        ReturnType.VALUE,
                        1,
                        rawMessagesKey,
                        packetIdBytes
                );
            } catch (InvalidDataAccessApiUsageException e) {
                connection.scriptingCommands().eval(
                        Objects.requireNonNull(UPDATE_PACKET_TYPE_SCRIPT),
                        ReturnType.VALUE,
                        1,
                        rawMessagesKey,
                        packetIdBytes
                );
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to update packet type, clientId - " + clientId + " packetId - " + packetId, e);
        }
    }

    private static DevicePublishMsg toData(byte[] bytes) {
        return Objects.requireNonNull(JacksonUtil.fromBytes(bytes, DevicePublishMsgRedisEntity.class)).toData();
    }

    private byte[] toMessagesCacheKey(String clientId) {
        ClientIdMessagesCacheKey clientIdMessagesCacheKey = new ClientIdMessagesCacheKey(clientId);
        String stringValue = clientIdMessagesCacheKey.toString();
        byte[] rawKey;
        try {
            rawKey = stringSerializer.serialize(stringValue);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (rawKey == null) {
            throw new IllegalArgumentException("Failed to serialize the messages cache key, clientId - " + clientId);
        }
        return rawKey;
    }

    private byte[] toLastPacketIdKey(String clientId) {
        ClientIdLastPacketIdCacheKey clientIdLastPacketIdCacheKey = new ClientIdLastPacketIdCacheKey(clientId);
        String stringValue = clientIdLastPacketIdCacheKey.toString();
        byte[] rawKey;
        try {
            rawKey = stringSerializer.serialize(stringValue);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (rawKey == null) {
            throw new IllegalArgumentException("Failed to serialize the last packet id cache key, clientId - " + clientId);
        }
        return rawKey;
    }

    private byte[] intToBytes(int fromPacketId) {
        return stringSerializer.serialize(Integer.toString(fromPacketId));
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

    private RedisConnection getNonClusterAwareConnection() {
        return connectionFactory.getConnection();
    }

    private void loadAddMessagesScript(RedisConnection connection) {
        String scriptShaStr = new String(Objects.requireNonNull(ADD_MESSAGES_SCRIPT_SHA));
        log.debug("Loading LUA with expected SHA [{}], connection [{}]", scriptShaStr, connection.getNativeConnection());
        addMessagesScript = stringSerializer.serialize(ADD_MESSAGES_SCRIPT_WITHOUT_FORMAT.formatted(messagesLimit));
        String actualScriptSha = connection.scriptingCommands().scriptLoad(Objects.requireNonNull(addMessagesScript));
        validateSha(ADD_MESSAGES_SCRIPT_SHA, scriptShaStr, actualScriptSha, connection);
    }

    private void loadGetMessagesScript(RedisConnection connection) {
        String scriptShaStr = new String(Objects.requireNonNull(GET_MESSAGES_SCRIPT_SHA));
        log.debug("Loading LUA with expected SHA [{}], connection [{}]", scriptShaStr, connection.getNativeConnection());
        getMessagesScript = stringSerializer.serialize(GET_MESSAGES_SCRIPT_WITHOUT_FORMAT.formatted(messagesLimit));
        String actualScriptSha = connection.scriptingCommands().scriptLoad(Objects.requireNonNull(getMessagesScript));
        validateSha(GET_MESSAGES_SCRIPT_SHA, scriptShaStr, actualScriptSha, connection);
    }

    private void loadDeleteMessagesScript(RedisConnection connection) {
        String scriptShaStr = new String(Objects.requireNonNull(REMOVE_MESSAGES_SCRIPT_SHA));
        log.debug("Loading lua script with expected SHA [{}] connection [{}]", scriptShaStr, connection.getNativeConnection());
        String actualScriptSha = connection.scriptingCommands().scriptLoad(Objects.requireNonNull(REMOVE_MESSAGES_SCRIPT));
        validateSha(REMOVE_MESSAGES_SCRIPT_SHA, scriptShaStr, actualScriptSha, connection);
    }

    private void loadDeleteMessageScript(RedisConnection connection) {
        String scriptShaStr = new String(Objects.requireNonNull(REMOVE_MESSAGE_SCRIPT_SHA));
        log.debug("Loading lua script with expected SHA [{}] connection [{}]", scriptShaStr, connection.getNativeConnection());
        String actualScriptSha = connection.scriptingCommands().scriptLoad(Objects.requireNonNull(REMOVE_MESSAGE_SCRIPT));
        validateSha(REMOVE_MESSAGE_SCRIPT_SHA, scriptShaStr, actualScriptSha, connection);
    }


    private void loadUpdatePacketTypeScript(RedisConnection connection) {
        String scriptShaStr = new String(Objects.requireNonNull(UPDATE_PACKET_TYPE_SCRIPT_SHA));
        log.debug("Loading lua script with expected SHA [{}] connection [{}]", scriptShaStr, connection.getNativeConnection());
        String actualScriptSha = connection.scriptingCommands().scriptLoad(Objects.requireNonNull(UPDATE_PACKET_TYPE_SCRIPT));
        validateSha(UPDATE_PACKET_TYPE_SCRIPT_SHA, scriptShaStr, actualScriptSha, connection);
    }

    private void validateSha(byte[] expectedSha, String expectedShaStr, String actualAddMessagesScriptSha, RedisConnection connection) {
        if (!Arrays.equals(expectedSha, stringSerializer.serialize(actualAddMessagesScriptSha))) {
            log.error("SHA for script is wrong! Expected [{}] actual [{}] connection [{}]", expectedShaStr, actualAddMessagesScriptSha, connection.getNativeConnection());
        }
    }

}
