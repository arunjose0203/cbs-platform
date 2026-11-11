package com.cbs.notifications;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

public final class NotificationModels {
    private NotificationModels() {}
    public enum Channel { SMS, EMAIL, NONE }
    public enum Event { CREDIT, DEBIT }
    public enum Status { PENDING, SENT, FAILED }

    /** A customer's alert preference; applied only after independent approval. */
    public record Preference(@PositiveOrZero long expectedVersion,@NotNull UUID customerId,@NotNull Channel channel,
        @Size(max=254) String destination,@NotNull @Pattern(regexp="en|fr|sw") String language,@NotNull Set<Event> events,
        @NotBlank @Size(max=120) @Pattern(regexp="[^\\p{Cntrl}]+") String consentReference,
        @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) {}
    public record Stored(UUID customerId,Channel channel,String destination,String language,Set<Event> events,String consentReference,UUID approvalId,Instant updatedAt) {}

    /** A posted customer movement that may produce an alert. */
    public record Movement(UUID bankId,UUID accountId,Event event,UUID sourceId,String amount,String currency,LocalDate businessDate,String reference) {}

    public record Notification(UUID id,UUID customerId,UUID accountId,Event event,UUID sourceId,Channel channel,String destination,String language,
        String subject,String body,Status status,int attempts,String failureCode,String provider,String providerReference,Instant createdAt,Instant sentAt) {}
    public record Page(List<Notification> items,long total,int limit,int offset) {}

    /** Outcome of one delivery attempt by a channel provider. */
    public record Result(boolean delivered,String providerReference,String failureCode) {
        public static Result sent(String reference){return new Result(true,reference,null);}
        public static Result failed(String code){return new Result(false,null,code);}
    }

    static final java.util.regex.Pattern PHONE=java.util.regex.Pattern.compile("\\+[1-9][0-9]{7,14}");
    static final java.util.regex.Pattern EMAIL=java.util.regex.Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,24}");
    /** Shows enough of a destination to recognise it without exposing it in logs and lists. */
    public static String mask(Channel channel,String destination){
        if(destination==null)return null;
        if(channel==Channel.SMS)return destination.length()<=6?"***":destination.substring(0,4)+"*".repeat(destination.length()-7)+destination.substring(destination.length()-3);
        int at=destination.indexOf('@');return at<=1?"***"+destination.substring(Math.max(at,0)):destination.charAt(0)+"***"+destination.substring(at);
    }
}
