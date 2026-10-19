package com.mibess.notify.channel;

import com.mibess.notify.shared.ResourceStore.Resource;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "notify.fake-provider", havingValue = "true")
public class FakeProvider implements ChannelProvider {

  public String code() {
    return "FAKE";
  }

  public Result send(SendCommand command) {
    return new Result("fake." + UUID.randomUUID(), 200);
  }

  public String healthCheck(Resource connection) {
    return "FAKE_READY";
  }

  public boolean validateConfiguration(Resource connection) {
    return true;
  }
}
