package com.cbs.notifications;

import static com.cbs.notifications.NotificationModels.*;
import com.cbs.tenancy.TenantException;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/**
 * Public webhook for provider delivery receipts and opt-outs (Round 10), in the platform's provider-neutral
 * form. It is on the public-route allowlist on purpose: every request must carry
 * {@code X-CBS-Timestamp} (epoch seconds, within five minutes) and {@code X-CBS-Signature: sha256=<hex>},
 * an HMAC-SHA256 of {@code timestamp + "." + body} with {@code cbs.notifications.callback-secret}.
 * Without a configured secret the endpoint answers 404. Replayed receipts are ignored by receipt ID.
 */
@RestController
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class NotificationReceiptController {
    static final long SKEW_SECONDS=300;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final NotificationService notifications;private final List<NotificationSender> senders;private final Environment env;private final Validator validator;
    public NotificationReceiptController(NotificationService notifications,List<NotificationSender> senders,Environment env,Validator validator){
        this.notifications=notifications;this.senders=senders;this.env=env;this.validator=validator;
    }

    @PostMapping("/api/v1/notifications/receipts/{provider}")
    public Map<String,Object> receipt(@PathVariable String provider,@RequestHeader(name="X-CBS-Timestamp",required=false) String timestamp,
            @RequestHeader(name="X-CBS-Signature",required=false) String signature,@RequestBody String body){
        String secret=env.getProperty("cbs.notifications.callback-secret","");
        if(secret.isBlank()||senders.stream().noneMatch(s->s.name().equals(provider)))throw new TenantException(404,"NOTIFICATION_RECEIPTS_DISABLED","Not found");
        if(!signed(secret,timestamp,signature,body,Instant.now().getEpochSecond()))throw new TenantException(401,"NOTIFICATION_RECEIPT_UNSIGNED","Receipt signature is not valid");
        Receipt receipt;
        try{receipt=JSON.readValue(body,Receipt.class);}catch(RuntimeException e){throw NotificationService.error(400,"NOTIFICATION_RECEIPT_INVALID");}
        if(receipt==null||receipt.kind()==null||!validator.validate(receipt).isEmpty())throw NotificationService.error(400,"NOTIFICATION_RECEIPT_INVALID");
        return Map.of("accepted",true,"outcome",notifications.receipt(provider,receipt));
    }

    static boolean signed(String secret,String timestamp,String signature,String body,long now){
        if(timestamp==null||signature==null||!timestamp.matches("[0-9]{1,12}")||!signature.startsWith("sha256="))return false;
        if(Math.abs(now-Long.parseLong(timestamp))>SKEW_SECONDS)return false;
        return MessageDigest.isEqual(sign(secret,timestamp,body).getBytes(StandardCharsets.US_ASCII),signature.substring(7).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }
    static String sign(String secret,String timestamp,String body){
        try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((timestamp+"."+body).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.GeneralSecurityException e){throw new IllegalStateException(e);}
    }
}
