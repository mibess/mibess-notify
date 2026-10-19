package com.mibess.notify;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mibess.notify.routing.EventTypes;
import org.junit.jupiter.api.Test;

class EventTypesTest {

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void legacyExactMultiAndPrefixRemainCaseSensitive() {
    var spec = json.createObjectNode().put("eventType", "ORDER_PAID");
    EventTypes.validateAndNormalize(spec);
    assertThat(EventTypes.matches(spec, "ORDER_PAID")).isTrue();
    assertThat(EventTypes.matches(spec, "order_paid")).isFalse();
    spec.putArray("eventTypes").add("CRM_SYNC_FAILED").add("CRM_ORDER_*");
    spec.put("eventType", "CRM_SYNC_FAILED");
    EventTypes.validateAndNormalize(spec);
    assertThat(EventTypes.matches(spec, "CRM_SYNC_FAILED")).isTrue();
    assertThat(EventTypes.matches(spec, "CRM_ORDER_PAID")).isTrue();
    assertThat(EventTypes.matches(spec, "CRM_SYNC_RECOVERED")).isFalse();
    assertThat(EventTypes.matches(spec, "OTHER_CRM_ORDER_PAID")).isFalse();
    assertThat(EventTypes.matches(spec, "ORDER_PAID")).isFalse();
  }

  @Test
  void multiTypesKeepAnExactFallbackForThePreviousImage() {
    var spec = json.createObjectNode();
    spec.putArray("eventTypes").add("CRM_*").add("CRM_NOTIFY_TEST");
    EventTypes.validateAndNormalize(spec);
    assertThat(spec.path("eventType").asText()).isEqualTo("CRM_NOTIFY_TEST");
    spec.put("eventType", "CRM_SYNC_RECOVERED");
    EventTypes.validateAndNormalize(spec);
    assertThat(spec.path("eventType").asText()).isEqualTo("CRM_SYNC_RECOVERED");
    spec.put("eventType", "OTHER_TYPE");
    assertThatIllegalArgumentException().isThrownBy(() ->
      EventTypes.validateAndNormalize(spec)
    );
  }

  @Test
  void invalidWildcardsDuplicatesAndMoreThanFiftyTypesAreRejected() {
    for (String value : new String[] {
      "*",
      "CRM*",
      "CRM_*_PAID",
      "crm_*",
      "",
      "A",
    }) {
      var spec = json.createObjectNode().put("eventType", "CRM_TEST");
      spec.putArray("eventTypes").add(value);
      assertThatIllegalArgumentException().isThrownBy(() ->
        EventTypes.validateAndNormalize(spec)
      );
    }
    var spec = json.createObjectNode();
    spec.putArray("eventTypes").add("CRM_TEST").add("CRM_TEST");
    assertThatIllegalArgumentException().isThrownBy(() ->
      EventTypes.validateAndNormalize(spec)
    );
    var array = spec.putArray("eventTypes");
    for (int i = 0; i < 50; i++) array.add("EVENT_" + i);
    EventTypes.validateAndNormalize(spec);
    array.add("EVENT_50");
    assertThatIllegalArgumentException().isThrownBy(() ->
      EventTypes.validateAndNormalize(spec)
    );
    spec.putArray("eventTypes");
    assertThatIllegalArgumentException().isThrownBy(() ->
      EventTypes.validateAndNormalize(spec)
    );
    spec.put("eventTypes", "CRM_TEST");
    assertThatIllegalArgumentException().isThrownBy(() ->
      EventTypes.validateAndNormalize(spec)
    );
  }

  @Test
  void wildcardOnlyRequiresExplicitFallback() {
    var spec = json.createObjectNode();
    spec.putArray("eventTypes").add("CRM_*");
    assertThatIllegalArgumentException().isThrownBy(() ->
      EventTypes.validateAndNormalize(spec)
    );
    spec.put("eventType", "CRM_TEST");
    EventTypes.validateAndNormalize(spec);
  }
}
