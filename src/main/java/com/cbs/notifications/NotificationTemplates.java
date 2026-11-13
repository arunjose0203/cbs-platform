package com.cbs.notifications;

import static com.cbs.notifications.NotificationModels.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Built-in wording, used when the bank has no approved template for an event, language and channel.
 * Messages never contain balances, full account numbers or names, because SMS and lock screens are
 * not private. Kiswahili and French wording needs the native-speaker review of Round 17.
 */
public final class NotificationTemplates {
    private NotificationTemplates() {}
    private static final Map<String,Map<Event,String>> BODY=Map.of(
        "en",Map.of(Event.CREDIT,"{bank}: {currency} {amount} credited to account ending {last4} on {date}. Ref {reference}.",
                    Event.DEBIT,"{bank}: {currency} {amount} debited from account ending {last4} on {date}. Ref {reference}. Not you? Call your branch.",
                    Event.TD_MATURITY,"{bank}: your fixed deposit {reference} on account ending {last4} matures on {date}. Visit your branch to give instructions.",
                    Event.LOAN_DUE,"{bank}: your loan {reference} instalment is due on {date}. Please make sure funds are in account ending {last4}.",
                    Event.LOAN_ARREARS,"{bank}: your loan {reference} is {days} days overdue. Please pay or call your branch to agree a plan.",
                    Event.VERIFICATION,"{bank}: your verification code is {code}. Give it only to bank staff confirming your alert settings. It expires in 24 hours."),
        "fr",Map.of(Event.CREDIT,"{bank} : {currency} {amount} crédités sur le compte se terminant par {last4} le {date}. Réf. {reference}.",
                    Event.DEBIT,"{bank} : {currency} {amount} débités du compte se terminant par {last4} le {date}. Réf. {reference}. Ce n'est pas vous ? Contactez votre agence.",
                    Event.TD_MATURITY,"{bank} : votre dépôt à terme {reference} sur le compte se terminant par {last4} arrive à échéance le {date}. Contactez votre agence pour vos instructions.",
                    Event.LOAN_DUE,"{bank} : l'échéance de votre prêt {reference} est due le {date}. Veuillez approvisionner le compte se terminant par {last4}.",
                    Event.LOAN_ARREARS,"{bank} : votre prêt {reference} est en retard de {days} jours. Veuillez régler ou contacter votre agence.",
                    Event.VERIFICATION,"{bank} : votre code de vérification est {code}. Ne le communiquez qu'au personnel de la banque qui confirme vos alertes. Il expire dans 24 heures."),
        "sw",Map.of(Event.CREDIT,"{bank}: {currency} {amount} zimeingizwa kwenye akaunti inayoishia {last4} tarehe {date}. Kumb. {reference}.",
                    Event.DEBIT,"{bank}: {currency} {amount} zimetolewa kwenye akaunti inayoishia {last4} tarehe {date}. Kumb. {reference}. Si wewe? Piga simu tawi lako.",
                    Event.TD_MATURITY,"{bank}: amana yako ya muda maalum {reference} kwenye akaunti inayoishia {last4} inaiva tarehe {date}. Tembelea tawi lako kutoa maelekezo.",
                    Event.LOAN_DUE,"{bank}: rejesho la mkopo wako {reference} linadaiwa tarehe {date}. Hakikisha kuna fedha kwenye akaunti inayoishia {last4}.",
                    Event.LOAN_ARREARS,"{bank}: mkopo wako {reference} umechelewa kwa siku {days}. Tafadhali lipa au piga simu tawi lako kupanga utaratibu.",
                    Event.VERIFICATION,"{bank}: nambari yako ya uthibitisho ni {code}. Mpe tu mfanyakazi wa benki anayethibitisha mipangilio ya arifa zako. Inaisha baada ya saa 24."));
    private static final Map<String,Map<Event,String>> SUBJECT=Map.of(
        "en",Map.of(Event.CREDIT,"{bank}: account activity",Event.DEBIT,"{bank}: account activity",Event.TD_MATURITY,"{bank}: fixed deposit maturity",
                    Event.LOAN_DUE,"{bank}: loan instalment due",Event.LOAN_ARREARS,"{bank}: loan payment overdue",Event.VERIFICATION,"{bank}: verification code"),
        "fr",Map.of(Event.CREDIT,"{bank} : mouvement sur votre compte",Event.DEBIT,"{bank} : mouvement sur votre compte",Event.TD_MATURITY,"{bank} : échéance de votre dépôt à terme",
                    Event.LOAN_DUE,"{bank} : échéance de prêt",Event.LOAN_ARREARS,"{bank} : retard de paiement",Event.VERIFICATION,"{bank} : code de vérification"),
        "sw",Map.of(Event.CREDIT,"{bank}: shughuli ya akaunti",Event.DEBIT,"{bank}: shughuli ya akaunti",Event.TD_MATURITY,"{bank}: kuiva kwa amana ya muda maalum",
                    Event.LOAN_DUE,"{bank}: rejesho la mkopo linadaiwa",Event.LOAN_ARREARS,"{bank}: malipo ya mkopo yamechelewa",Event.VERIFICATION,"{bank}: nambari ya uthibitisho"));
    /** Placeholders each event may use; anything else in a bank template is refused. */
    static final Map<Event,Set<String>> PLACEHOLDERS=Map.of(
        Event.CREDIT,Set.of("bank","currency","amount","last4","date","reference"),Event.DEBIT,Set.of("bank","currency","amount","last4","date","reference"),
        Event.TD_MATURITY,Set.of("bank","last4","date","reference"),Event.LOAN_DUE,Set.of("bank","last4","date","reference"),
        Event.LOAN_ARREARS,Set.of("bank","last4","days","reference"),Event.VERIFICATION,Set.of("bank","code"));
    private static final Pattern PLACEHOLDER=Pattern.compile("\\{([^{}]*)}");
    /** Two SMS segments with the GSM alphabet. */
    static final int SMS_LIMIT=306;

    public static String defaultBody(String language,Event event){return BODY.getOrDefault(language,BODY.get("en")).get(event);}
    public static String defaultSubject(String language,Event event){return SUBJECT.getOrDefault(language,SUBJECT.get("en")).get(event);}
    public static String body(String language,Event event,Map<String,String> values){return fill(defaultBody(language,event),values);}
    public static String subject(String language,Map<String,String> values){return fill(defaultSubject(language,Event.CREDIT),values);}

    /** Placeholders used by a template that the event does not allow, plus any required placeholder it lacks. */
    static List<String> problems(Event event,String text,boolean body){
        var problems=new ArrayList<String>();var used=new HashSet<String>();var m=PLACEHOLDER.matcher(text);
        while(m.find()){used.add(m.group(1));if(!PLACEHOLDERS.get(event).contains(m.group(1)))problems.add("{"+m.group(1)+"}");}
        if(text.replaceAll("\\{[^{}]*}","").matches(".*[{}].*"))problems.add("unbalanced braces");
        if(body&&!used.contains("bank"))problems.add("{bank} required");
        if(body&&event==Event.VERIFICATION&&!used.contains("code"))problems.add("{code} required");
        return problems;
    }
    static String sample(Event event){
        return fill(defaultBody("en",event),sampleValues());
    }
    static Map<String,String> sampleValues(){return Map.of("bank","Example Bank","currency","KES","amount","1500.00","last4","4821","date","2026-12-01","reference","REF-001","days","14","code","123456");}

    static String fill(String template,Map<String,String> values){
        String out=template;for(var e:values.entrySet())out=out.replace("{"+e.getKey()+"}",clean(e.getValue()));return out;
    }
    /** Strips control characters and caps length so data can never reshape the message. */
    private static String clean(String value){if(value==null)return "";var v=value.replaceAll("\\p{Cntrl}"," ").strip();return v.length()>60?v.substring(0,60):v;}
    public static String last4(String accountNumber){return accountNumber==null||accountNumber.length()<4?"****":accountNumber.substring(accountNumber.length()-4);}
}
