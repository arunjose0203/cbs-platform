package com.cbs.payments;

import static com.cbs.payments.StatementImportModels.*;
import com.cbs.tenancy.TenantException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Strict CSV, exact decimal arithmetic and explicit column mapping; no inferred payment types. */
public final class MpesaStatementCsv {
 public static final List<String> FIELDS=List.of("receipt","date","type","credit","debit","balance","payment_reference","beneficiary");
 private MpesaStatementCsv() {}
 public static BigDecimal money(String text){if(text==null||!text.matches("-?[0-9]{1,18}(\\.[0-9]{1,2})?"))throw error("STATEMENT_MONEY_INVALID");return new BigDecimal(text);}
 public static String text(BigDecimal amount){return amount.stripTrailingZeros().toPlainString();}
 public static List<Line> parse(Upload upload){
  if(upload.csv()==null||upload.csv().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1048576)throw error("STATEMENT_FILE_TOO_LARGE");
  if(upload.fromDate()==null||upload.toDate()==null||upload.fromDate().isAfter(upload.toDate())||upload.fromDate().getYear()<1900||upload.toDate().isAfter(LocalDate.now(ZoneOffset.UTC)))throw error("STATEMENT_PERIOD_INVALID");
  var records=records(upload.csv());if(records.isEmpty())throw error("STATEMENT_CSV_INVALID");var header=records.getFirst();
  if(header.size()>40||header.stream().anyMatch(String::isBlank)||new HashSet<>(header).size()!=header.size())throw error("STATEMENT_COLUMNS_INVALID");
  if(upload.columns()==null||!FIELDS.containsAll(upload.columns().keySet()))throw error("STATEMENT_COLUMNS_INVALID");
  var positions=new LinkedHashMap<String,Integer>();for(var field:FIELDS){String mapped=upload.columns().getOrDefault(field,field);int index=header.indexOf(mapped);if(index<0||positions.containsValue(index))throw error("STATEMENT_COLUMNS_INVALID");positions.put(field,index);}
  if(records.size()>5001)throw error("STATEMENT_FILE_TOO_LARGE");var result=new ArrayList<Line>();BigDecimal running=money(upload.openingBalance());LocalDate previous=upload.fromDate();var seen=new HashSet<String>();
  for(int i=1;i<records.size();i++){
   var cells=records.get(i);if(cells.size()!=header.size())throw error("STATEMENT_CSV_INVALID");var values=new HashMap<String,String>();positions.forEach((key,index)->values.put(key,cells.get(index).trim()));
   String receipt=values.get("receipt").toUpperCase(Locale.ROOT),ref=values.get("payment_reference"),phone=values.get("beneficiary");
   if(!receipt.matches("[A-Z0-9][A-Z0-9._/-]{0,79}")||(!ref.isEmpty()&&!ref.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,79}"))||(!phone.isEmpty()&&!phone.matches("254[17][0-9]{8}")))throw error("STATEMENT_ROW_INVALID");
   LocalDate date;Type type;try{date=LocalDate.parse(values.get("date"));type=Type.valueOf(values.get("type"));}catch(Exception e){throw error("STATEMENT_ROW_INVALID");}
   if(date.isBefore(previous)||date.isBefore(upload.fromDate())||date.isAfter(upload.toDate()))throw error("STATEMENT_PERIOD_INVALID");previous=date;
   BigDecimal credit=money(values.get("credit")),debit=money(values.get("debit")),balance=money(values.get("balance"));
   if(credit.signum()<0||debit.signum()<0||(credit.signum()>0)==(debit.signum()>0))throw error("STATEMENT_ROW_INVALID");
   if((type==Type.PAYOUT||type==Type.FEE||type==Type.DEBIT)&&debit.signum()==0||type==Type.CREDIT&&credit.signum()==0)throw error("STATEMENT_ROW_INVALID");
   if(type==Type.PAYOUT&&(ref.isEmpty()||phone.isEmpty()))throw error("STATEMENT_ROW_INVALID");
   running=running.add(credit).subtract(debit);if(running.compareTo(balance)!=0)throw error("STATEMENT_RUNNING_BALANCE");
   var row=new Line(i,receipt,date,type,text(credit),text(debit),text(balance),ref,phone);
   if(!seen.add(receipt+":"+type))throw error("STATEMENT_DUPLICATE_ROW");result.add(row);
  }
  if(running.compareTo(money(upload.closingBalance()))!=0)throw error("STATEMENT_CLOSING_BALANCE");return List.copyOf(result);
 }
 static List<List<String>> records(String raw){
  String input=raw.startsWith("\ufeff")?raw.substring(1):raw;var records=new ArrayList<List<String>>();var row=new ArrayList<String>();var field=new StringBuilder();boolean quoted=false,ended=false;
  for(int i=0;i<input.length();i++){
   char c=input.charAt(i);if(c=='\0')throw error("STATEMENT_CSV_INVALID");
   if(quoted){if(c=='"'){if(i+1<input.length()&&input.charAt(i+1)=='"'){field.append('"');i++;}else{quoted=false;ended=true;}}else field.append(c);}
   else if(c==','||c=='\n'||c=='\r'){row.add(field.toString());field.setLength(0);ended=false;if(c!=','){if(c=='\r'&&i+1<input.length()&&input.charAt(i+1)=='\n')i++;records.add(List.copyOf(row));row.clear();}}
   else if(c=='"'&&field.isEmpty()&&!ended)quoted=true;
   else {if(ended||c=='"')throw error("STATEMENT_CSV_INVALID");field.append(c);}
   if(field.length()>1024||row.size()>40||records.size()>5001)throw error("STATEMENT_FILE_TOO_LARGE");
  }
  if(quoted)throw error("STATEMENT_CSV_INVALID");if(!row.isEmpty()||!field.isEmpty()||ended){row.add(field.toString());records.add(List.copyOf(row));}return records;
 }
 static TenantException error(String code){return new TenantException(422,code,"Statement evidence requires attention");}
}
