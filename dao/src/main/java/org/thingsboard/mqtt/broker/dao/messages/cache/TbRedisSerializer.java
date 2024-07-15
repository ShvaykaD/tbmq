package org.thingsboard.mqtt.broker.dao.messages.cache;

import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.lang.Nullable;

public interface TbRedisSerializer<K, V> {

    @Nullable
    byte[] serialize(@Nullable V v) throws SerializationException;

    @Nullable
    V deserialize(K key, @Nullable byte[] bytes) throws SerializationException;

}
