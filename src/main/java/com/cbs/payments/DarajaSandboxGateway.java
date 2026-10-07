package com.cbs.payments;

import com.cbs.tenancy.TenantException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import static com.cbs.payments.PaymentModels.*;

@Component
public class DarajaSandboxGateway implements MpesaGateway {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private static final String BASE="https://sandbox.safaricom.co.ke";
 private final Environment env;
 private volatile HttpClient http;
 @org.springframework.beans.factory.annotation.Autowired
 public DarajaSandboxGateway(Environment env){this(env,null);}
 DarajaSandboxGateway(Environment env,HttpClient http){this.env=env;this.http=http;}
 public String profile(UUID bank){return PaymentService.hash(bank+":"+setting("settlement-ledger-id")+":"+setting("shortcode")+":"+setting("consumer-key"));}
 private String setting(String name){return env.getProperty("cbs.payments.mpesa."+name,"");}
 public boolean configured(UUID bank){
  if(Arrays.asList(env.getActiveProfiles()).contains("production")||!setting("enabled").equals("true")||!setting("bank-id").equals(bank.toString()))return false;
  if(!setting("settlement-ledger-id").matches("[0-9a-fA-F-]{36}")||!setting("shortcode").matches("[0-9]{5,10}"))return false;
  for(String field:List.of("consumer-key","consumer-secret","initiator","security-credential"))if(setting(field).isBlank())return false;
  try{var uri=URI.create(setting("callback-base"));return "https".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null&&uri.getQuery()==null&&uri.getFragment()==null&&(uri.getPath().isEmpty()||uri.getPath().equals("/"));}catch(Exception e){return false;}
 }
 public boolean binding(UUID bank,UUID ledger){return configured(bank)&&setting("settlement-ledger-id").equals(ledger.toString());}
 private String callback(UUID payment,String token){return setting("callback-base").replaceAll("/$","")+"/api/v1/payments/mpesa/callback/"+payment+"/"+token;}
 public Acceptance transfer(UUID bank,UUID payment,Order order,String token){
  if(!binding(bank,order.settlementLedgerId()))throw failure();
  var body=new LinkedHashMap<String,Object>();body.put("InitiatorName",setting("initiator"));body.put("SecurityCredential",setting("security-credential"));body.put("CommandID","BusinessPayment");body.put("Amount",order.amount());body.put("PartyA",setting("shortcode"));body.put("PartyB",order.beneficiary());body.put("Remarks","CBS sandbox "+payment);body.put("Occasion",payment.toString());body.put("QueueTimeOutURL",callback(payment,token));body.put("ResultURL",callback(payment,token));
  return post("/mpesa/b2c/v1/paymentrequest",body);
 }
 public Acceptance query(UUID bank,UUID payment,String originator,String token){
  if(!configured(bank)||originator==null||originator.isBlank())throw failure();
  var body=new LinkedHashMap<String,Object>();body.put("Initiator",setting("initiator"));body.put("SecurityCredential",setting("security-credential"));body.put("CommandID","TransactionStatusQuery");body.put("OriginalConversationID",originator);body.put("PartyA",setting("shortcode"));body.put("IdentifierType","4");body.put("Remarks","CBS reconciliation");body.put("Occasion",payment.toString());body.put("ResultURL",callback(payment,token));body.put("QueueTimeOutURL",callback(payment,token));
  return post("/mpesa/transactionstatus/v1/query",body);
 }
 private synchronized HttpClient client(){if(http==null)http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();return http;}
 private Acceptance post(String path,Map<String,Object> body){
  try{
   String basic=Base64.getEncoder().encodeToString((setting("consumer-key")+":"+setting("consumer-secret")).getBytes(StandardCharsets.UTF_8));
   var auth=client().send(HttpRequest.newBuilder(URI.create(BASE+"/oauth/v1/generate?grant_type=client_credentials")).timeout(Duration.ofSeconds(15)).header("Authorization","Basic "+basic).GET().build(),HttpResponse.BodyHandlers.ofString());
   if(auth.statusCode()!=200||auth.body().length()>16384)throw failure();
   String token=JSON.readTree(auth.body()).path("access_token").asText();if(token.isBlank())throw failure();
   var response=client().send(HttpRequest.newBuilder(URI.create(BASE+path)).timeout(Duration.ofSeconds(25)).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
   if(response.statusCode()!=200||response.body().length()>16384)throw failure();
   var node=JSON.readTree(response.body());if(!node.path("ResponseCode").asText().equals("0"))throw failure();
   String conversation=node.path("ConversationID").asText(),originator=node.path("OriginatorConversationID").asText();
   if(!validIdentifier(conversation)||!validIdentifier(originator))throw failure();return new Acceptance(conversation,originator);
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw failure();}catch(Exception e){throw failure();}
 }
 static boolean validIdentifier(String value){return value!=null&&value.matches("[A-Za-z0-9_-]{1,100}");}
 private static TenantException failure(){return new TenantException(409,"PAYMENT_PROVIDER_UNCONFIRMED","Provider outcome is not confirmed; investigate before any new payment");}
}
