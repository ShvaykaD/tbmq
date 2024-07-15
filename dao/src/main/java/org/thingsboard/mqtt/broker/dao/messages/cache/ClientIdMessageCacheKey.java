package org.thingsboard.mqtt.broker.dao.messages.cache;

import java.io.Serial;
import java.io.Serializable;

public record ClientIdMessageCacheKey(String clientId) implements Serializable {

    @Serial
    private static final long serialVersionUID = 65684921903757140L;

    @Override
    public String toString() {
        return "{" + clientId + "}_messages";
    }

}
