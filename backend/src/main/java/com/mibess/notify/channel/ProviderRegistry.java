package com.mibess.notify.channel;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ProviderRegistry {

  private final Map<String, ChannelProvider> providers = new HashMap<>();

  public ProviderRegistry(List<ChannelProvider> providers) {
    providers.forEach(p -> this.providers.put(p.code(), p));
  }

  public ChannelProvider get(String code) {
    var p = providers.get(code);
    if (p == null) throw new ChannelProvider.DeliveryFailure(
      "PROVIDER_UNAVAILABLE",
      false,
      false,
      0
    );
    return p;
  }
}
