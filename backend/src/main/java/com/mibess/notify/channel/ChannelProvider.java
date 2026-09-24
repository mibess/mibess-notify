package com.mibess.notify.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.mibess.notify.shared.ResourceStore.Resource;

public interface ChannelProvider {
    String code();
    record SendCommand(String recipient,JsonNode message,Resource connection) {}
    record Result(String messageId,int responseCode) {}
    Result send(SendCommand command);
    boolean validateConfiguration(Resource connection);
    default String healthCheck(Resource connection) {return validateConfiguration(connection)?"CONFIGURED":"NOT_CONFIGURED";}
    class DeliveryFailure extends RuntimeException {
        public final String code;public final boolean retryable;public final boolean ambiguous;public final int httpStatus;
        public DeliveryFailure(String code,boolean retryable,boolean ambiguous,int httpStatus){super(code);this.code=code;this.retryable=retryable;this.ambiguous=ambiguous;this.httpStatus=httpStatus;}
    }
}
