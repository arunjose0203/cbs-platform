package com.cbs.notifications;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

public final class NotificationModels {
    private NotificationModels() {}
    public enum Channel { SMS, EMAIL, NONE }
    /** Account movements, scheduled notices (Round 10) and the one-time code that verifies a destination. */
    public enum Event { CREDIT, DEBIT, TD_MATURITY, LOAN_DUE, LOAN_ARREARS, VERIFICATION }
    public enum Status { PENDING, SENT, FAILED, SUPPRESSED }
    public enum ReceiptKind { DELIVERED, UNDELIVERED, OPTED_OUT }
    static final String TEXT="[^\\p{Cntrl}]+";

    /** A customer's alert preference; applied only after independent approval. */
    public record Preference(@PositiveOrZero long expectedVersion,@NotNull UUID customerId,@NotNull Channel channel,
        @Size(max=254) String destination,@NotNull @Pattern(regexp="en|fr|sw") String language,@NotNull Set<Event> events,
        @NotBlank @Size(max=120) @Pattern(regexp=TEXT) String consentReference,
        @NotBlank @Size(max=500) @Pattern(regexp=TEXT) String reason) {}
    public record Stored(UUID customerId,Channel channel,String destination,String language,Set<Event> events,String consentReference,UUID approvalId,Instant updatedAt,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Instant verifiedAt) {
        public boolean verified(){return channel==Channel.NONE||verifiedAt!=null;}
    }
    /** The code the customer received on the new destination, read back to staff. */
    public record Verification(@NotBlank @Pattern(regexp="[0-9]{6}") String code) {}

    /** A posted customer movement that may produce an alert. */
    public record Movement(UUID bankId,UUID accountId,Event event,UUID sourceId,String amount,String currency,LocalDate businessDate,String reference) {}
    /** Any alert or notice for the holders of one account; values fill the template placeholders. */
    public record Notice(UUID bankId,UUID accountId,Event event,UUID sourceId,Map<String,String> values) {}

    public record Notification(UUID id,UUID customerId,UUID accountId,Event event,UUID sourceId,Channel channel,String destination,String language,
        String subject,String body,Status status,int attempts,String failureCode,String provider,String providerReference,Instant createdAt,Instant sentAt,
        String receiptStatus,Instant receiptAt) {}
    public record Page(List<Notification> items,long total,int limit,int offset) {}

    /** Bank wording for one event, language and channel; maker-checker (NOTIFICATION_TEMPLATE). */
    public record Template(@PositiveOrZero long expectedVersion,@NotNull Event event,@NotNull @Pattern(regexp="en|fr|sw") String language,@NotNull Channel channel,
        @Size(max=200) @Pattern(regexp=TEXT) String subject,@NotBlank @Size(max=1000) @Pattern(regexp=TEXT) String body,
        @NotBlank @Size(max=500) @Pattern(regexp=TEXT) String reason) {}
    public record TemplateView(Event event,String language,Channel channel,String subject,String body,boolean approved,UUID approvalId,Instant updatedAt) {}

    /** A destination that asked not to be contacted. */
    public record OptOut(@NotNull Channel channel,@NotBlank @Size(max=254) String destination,@NotBlank @Size(max=120) @Pattern(regexp=TEXT) String reference) {}
    public record OptOutView(Channel channel,String destination,String source,String reference,String recordedBy,Instant recordedAt) {}

    /**
     * Signed delivery receipt or opt-out from the SMS/email provider, in the platform's provider-neutral form.
     * A provider adapter translates its own callbacks into this shape.
     */
    public record Receipt(@NotBlank @Size(max=120) @Pattern(regexp="[A-Za-z0-9._:-]+") String receiptId,@NotNull ReceiptKind kind,
        @Size(max=120) String providerReference,@Size(max=80) @Pattern(regexp="[A-Za-z0-9_.-]*") String code,
        UUID bankId,Channel channel,@Size(max=254) String destination) {}

    /** Outcome of one delivery attempt by a channel provider. */
    public record Result(boolean delivered,String providerReference,String failureCode) {
        public static Result sent(String reference){return new Result(true,reference,null);}
        public static Result failed(String code){return new Result(false,null,code);}
    }

    static final java.util.regex.Pattern PHONE=java.util.regex.Pattern.compile("\\+[1-9][0-9]{7,14}");
    static final java.util.regex.Pattern EMAIL=java.util.regex.Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,24}");
    static boolean validDestination(Channel channel,String destination){return destination!=null&&channel!=Channel.NONE&&(channel==Channel.SMS?PHONE:EMAIL).matcher(destination).matches();}
    /** Shows enough of a destination to recognise it without exposing it in logs and lists. */
    public static String mask(Channel channel,String destination){
        if(destination==null)return null;
        if(channel==Channel.SMS)return destination.length()<=6?"***":destination.substring(0,4)+"*".repeat(destination.length()-7)+destination.substring(destination.length()-3);
        int at=destination.indexOf('@');return at<=1?"***"+destination.substring(Math.max(at,0)):destination.charAt(0)+"***"+destination.substring(at);
    }
}
