package com.cbs.notifications;

import static com.cbs.notifications.NotificationModels.*;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Delivers one message on one channel. Real SMS or email providers implement this once a
 * provider is contracted; until then no sender is configured and messages stay PENDING.
 */
public interface NotificationSender {
    String name();
    boolean supports(Channel channel);
    Result send(Notification notification);

    /**
     * Development/test sender: accepts every message without contacting anyone.
     * Enabled only with cbs.notifications.sender=test and refused by the production guard.
     */
    @Component
    @ConditionalOnProperty(name="cbs.notifications.sender",havingValue="test")
    class Test implements NotificationSender {
        public String name(){return "TEST";}
        public boolean supports(Channel channel){return channel==Channel.SMS||channel==Channel.EMAIL;}
        public Result send(Notification n){
            // Fictional failure hook for automated tests: destinations ending in 0000 fail.
            if(n.destination().endsWith("0000")||n.destination().startsWith("fail@"))return Result.failed("TEST_PROVIDER_REJECTED");
            return Result.sent("TEST-"+UUID.nameUUIDFromBytes(n.id().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }
}
