package com.cbs.accounts;

/** CSV text fields are neutralized before quoting to prevent spreadsheet formulas. */
public final class StatementCsv {
 private StatementCsv() {}
 public static String render(StatementService.Statement statement) {
  var out=new StringBuilder("\uFEFF");
  row(out,"Account",statement.accountNumber(),"Currency",statement.currency());
  row(out,"From",statement.from().toString(),"To",statement.to().toString());
  row(out,"Opening balance",statement.openingBalance(),"Closing balance",statement.closingBalance());
  row(out,"Transaction ID","Business date","Reference","Narration","Debit","Credit","Balance");
  for(var line:statement.items())row(out,line.transactionId().toString(),line.businessDate().toString(),line.reference(),line.narration(),line.debit(),line.credit(),line.balance());
  return out.toString();
 }
 private static void row(StringBuilder out,String... values){
  for(int i=0;i<values.length;i++){
   if(i>0)out.append(',');
   String value=values[i]==null?"":values[i];
   String stripped=value.stripLeading();
   if(!stripped.isEmpty()&&("=+-@".indexOf(stripped.charAt(0))>=0)||value.indexOf('\t')>=0||value.indexOf('\r')>=0||value.indexOf('\n')>=0)value="'"+value;
   out.append('"').append(value.replace("\"","\"\"")).append('"');
  }
  out.append("\r\n");
 }
}
