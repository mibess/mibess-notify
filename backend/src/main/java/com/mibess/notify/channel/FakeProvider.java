package com.mibess.notify.channel;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.mibess.notify.shared.ResourceStore.Resource;
@Component
@ConditionalOnProperty(name="notify.fake-provider",havingValue="true")
public class FakeProvider implements ChannelProvider {
    public String code(){return "FAKE";}
    public Result send(SendCommand command){return new Result("fake."+UUID.randomUUID(),200);}
    public boolean validateConfiguration(Resource connection){return true;}
}
