package com.cbs.notifications;

import static com.cbs.notifications.NotificationModels.*;
import java.util.Map;

/**
 * Built-in alert wording. Messages never contain balances, full account numbers or names,
 * because SMS and lock screens are not private. Bank-specific wording needs an approved
 * template workflow (not yet implemented).
 */
public final class NotificationTemplates {
    private NotificationTemplates() {}
    private static final Map<String,Map<Event,String>> BODY=Map.of(
        "en",Map.of(Event.CREDIT,"{bank}: {currency} {amount} credited to account ending {last4} on {date}. Ref {reference}.",
                    Event.DEBIT,"{bank}: {currency} {amount} debited from account ending {last4} on {date}. Ref {reference}. Not you? Call your branch."),
        "fr",Map.of(Event.CREDIT,"{bank} : {currency} {amount} crédités sur le compte se terminant par {last4} le {date}. Réf. {reference}.",
                    Event.DEBIT,"{bank} : {currency} {amount} débités du compte se terminant par {last4} le {date}. Réf. {reference}. Ce n'est pas vous ? Contactez votre agence."),
        "sw",Map.of(Event.CREDIT,"{bank}: {currency} {amount} zimeingizwa kwenye akaunti inayoishia {last4} tarehe {date}. Kumb. {reference}.",
                    Event.DEBIT,"{bank}: {currency} {amount} zimetolewa kwenye akaunti inayoishia {last4} tarehe {date}. Kumb. {reference}. Si wewe? Piga simu tawi lako."));
    private static final Map<String,String> SUBJECT=Map.of("en","{bank}: account activity","fr","{bank} : mouvement sur votre compte","sw","{bank}: shughuli ya akaunti");

    public static String body(String language,Event event,Map<String,String> values){return fill(BODY.getOrDefault(language,BODY.get("en")).get(event),values);}
    public static String subject(String language,Map<String,String> values){return fill(SUBJECT.getOrDefault(language,SUBJECT.get("en")),values);}
    private static String fill(String template,Map<String,String> values){
        String out=template;for(var e:values.entrySet())out=out.replace("{"+e.getKey()+"}",clean(e.getValue()));return out;
    }
    /** Strips control characters and caps length so data can never reshape the message. */
    private static String clean(String value){if(value==null)return "";var v=value.replaceAll("\\p{Cntrl}"," ").strip();return v.length()>60?v.substring(0,60):v;}
    public static String last4(String accountNumber){return accountNumber==null||accountNumber.length()<4?"****":accountNumber.substring(accountNumber.length()-4);}
}
