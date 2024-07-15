package org.thingsboard.mqtt.broker.dao.messages.cache;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "cache", value = "type", havingValue = "redis")
@RequiredArgsConstructor
public class DeviceMsgRedisCacheService implements DeviceMsgCacheService {

    @Value("${mqtt.persistent-session.device.persisted-messages.ttl}")
    private long defaultTtl;

    @Value("${mqtt.persistent-session.device.persisted-messages.limit}")
    private int messagesLimit;

    private final JedisConnectionFactory connectionFactory;
    private final RedisSerializer<String> keySerializer = StringRedisSerializer.UTF_8;
//    private final TbRedisSerializer<ClientIdMessageCacheKey, DevicePublishMsg> valueSerializer = new TbJsonRedisSerializer<>(DevicePublishMsg.class);

    private static final JedisPool MOCK_POOL = new JedisPool(); //non-null pool required for JedisConnection to trigger closing jedis connection
    private static final byte[] ADD_MESSAGES_SCRIPT_SHA = StringRedisSerializer.UTF_8.serialize("b0fc55122be0a5f19601892a3d5ac156ce01f102");
    private static final byte[] GET_MESSAGES_IN_RANGE_SCRIPT_SHA = StringRedisSerializer.UTF_8.serialize("01a4966ad295170f390fc181a788f40928a5811e");

    @PostConstruct
    public void init() {
        // TODO: load lua scripts. Consider if the connection should be the same for all scripts. If so what we should use as a key?
        try (var connection = getConnection(ADD_MESSAGES_SCRIPT_SHA)) {
            loadAddMessagesScript(connection);
            loadGetMessagesInRangeScript(connection);
        } catch (Throwable t) {
            log.error("Failed to init persisted device messages cache service for messages persistence!", t);
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
            byte[] rawLastInfoKey = toClientLastInfoKey(clientId);
            byte[] rawSetKey = toClientIdMessageCacheKey(clientId);
            byte[] messagesBytes = JacksonUtil.writeValueAsBytes(messages);
            try (var connection = getConnection(rawSetKey)) {
                try {
                    connection.scriptingCommands().evalSha(
                            Objects.requireNonNull(ADD_MESSAGES_SCRIPT_SHA),
                            ReturnType.INTEGER,
                            2,
                            rawSetKey,
                            rawLastInfoKey,
                            messagesBytes
                    );
                } catch (Exception e) {
                    log.error("error while saving device publish messages!", e);
                }
            }
        });
    }

    @Override
    public List<DevicePublishMsg> findPersistedMessages(String clientId, long fromSerialNumber, long toSerialNumber) {
        return List.of();
    }

    private byte[] toClientIdMessageCacheKey(String clientId) {
        ClientIdMessageCacheKey clientIdMessageCacheKey = new ClientIdMessageCacheKey(clientId);
        String stringValue = clientIdMessageCacheKey.toString();
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

    private byte[] toClientLastInfoKey(String clientId) {
        ClientIdLastInfoCacheKey clientIdLastInfoCacheKey = new ClientIdLastInfoCacheKey(clientId);
        String stringValue = clientIdLastInfoCacheKey.toString();
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
                local fromSerial = tonumber(ARGV[1])
                local toSerial = tonumber(ARGV[2])
                
                -- Get the range of elements from the sorted set
                local elements = redis.call('ZRANGEBYSCORE', messagesKey, fromSerial, toSerial)
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
                
                return lastPacketId
                """.formatted(messagesLimit));
        String actualScriptSha = connection.scriptingCommands().scriptLoad(Objects.requireNonNull(scriptBytes));
        validateSha(ADD_MESSAGES_SCRIPT_SHA, scriptShaStr, actualScriptSha, connection);
    }

    private static void validateSha(byte[] expectedSha, String expectedShaStr, String actualAddMessagesScriptSha, RedisConnection connection) {
        if (!Arrays.equals(expectedSha, StringRedisSerializer.UTF_8.serialize(actualAddMessagesScriptSha))) {
            log.error("SHA for SCRIPT wrong! Expected [{}], but actual [{}], connection [{}]", expectedShaStr, actualAddMessagesScriptSha, connection.getNativeConnection());
            // TODO: consider to throw an exception
        }
    }

}
