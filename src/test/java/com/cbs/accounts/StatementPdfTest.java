package com.cbs.accounts;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class StatementPdfTest {
 private static StatementService.Header header(String language){return new StatementService.Header("Fictional Bank <PLC>","Fictional Bank Kenya Ltd","0001000001","Savings — Wanjiru","C000001","KES",LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"9007199254740993.17","9007199254741093.17",120,"50.5","150.5",language,java.util.List.of("Wanjiru Kamau","Otieno Ouma"));}
 private static String render(String requested,String bankLanguage,int lines)throws Exception{
  var out=new ByteArrayOutputStream();var pdf=new StatementPdf(out,requested,Instant.parse("2026-11-11T10:00:00Z"));pdf.header(header(bankLanguage));
  for(int i=0;i<lines;i++)pdf.line(new StatementService.Line(UUID.randomUUID(),LocalDate.of(2026,10,1+i%28),"REF-"+i,i==0?"Mobile money 支付 deposit with a very long narration that must be shortened to fit":"Narration "+i,i%2==0?"0":"1.25",i%2==0?"2.5":"0","9007199254740993."+String.format("%02d",i%100)));
  pdf.finish();
  try(var doc=Loader.loadPDF(out.toByteArray())){return doc.getNumberOfPages()+"\n"+new PDFTextStripper().getText(doc);}
 }
 @Test void longStatementsPaginateWithExactAmountsAndTotals()throws Exception{
  var text=render(null,"en",120);int pages=Integer.parseInt(text.substring(0,text.indexOf('\n')));
  assertThat(pages).isGreaterThan(1);
  assertThat(text).contains("Account holders","Wanjiru Kamau, Otieno Ouma","Account statement","Fictional Bank <PLC>","0001000001","Opening balance","9007199254740993.17","Closing balance","9007199254741093.17","Total debits","50.5","Total credits","150.5");
  assertThat(text).contains("Page 1 of "+pages,"Page "+pages+" of "+pages,"Generated 2026-11-11 10:00 UTC");
  assertThat(text).contains("REF-119","Narration 119","Mobile money ?? deposit","9007199254740993.19","9007199254740993.00").doesNotContain("支付").doesNotContain("900719925474…");
 }
 @Test void labelsFollowRequestedOrBankLanguageAndEmptyPeriodsSaySo()throws Exception{
  assertThat(render("fr","en",0)).contains("Relevé de compte","Solde d'ouverture","Aucune opération sur cette période.","Page 1 sur 1");
  assertThat(render(null,"sw",0)).contains("Taarifa ya akaunti","Salio la mwanzo","Hakuna miamala");
  assertThat(render(null,"xx",0)).contains("Account statement");
 }
 @Test void controlCharactersAreNeutralized(){assertThat(StatementPdf.safe("a\tb\nc\u0000")).isEqualTo("a b c ");}
}
