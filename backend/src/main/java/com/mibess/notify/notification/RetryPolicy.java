package com.mibess.notify.notification;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class RetryPolicy {
    private final List<Long> delays;
    public RetryPolicy(@Value("${notify.retry-delays}") String delays){this.delays=Arrays.stream(delays.split(",")).map(String::trim).map(Long::parseLong).toList();if(this.delays.stream().anyMatch(v->v<1))throw new IllegalArgumentException("Retry deve ser positivo");}
    public Optional<Duration> delay(int retries){return retries<delays.size()?Optional.of(Duration.ofSeconds(delays.get(retries))):Optional.empty();}
}
