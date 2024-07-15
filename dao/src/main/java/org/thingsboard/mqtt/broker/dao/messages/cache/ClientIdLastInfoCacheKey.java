package org.thingsboard.mqtt.broker.dao.messages.cache;

import java.io.Serial;
import java.io.Serializable;

public record ClientIdLastInfoCacheKey(String clientId) implements Serializable {

    @Serial
    private static final long serialVersionUID = 6899529084577281154L;

    @Override
    public String toString() {
        return "{" + clientId + "}_last_packet_id";
    }

}
