package com.cbs.reporting;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class TableOutputTest {
 private static TableOutput.Table table(int rows){
  var list=new ArrayList<List<String>>();for(int i=0;i<rows;i++)list.add(List.of("A"+i,"=HYPERLINK(\"x\")","-9007199254740993.17","1"));
  return new TableOutput.Table("Balance sheet — Fictional Bank",List.of("Entity · KES","As at 2026-12-31"),List.of("Code","Account","Current","Comparative"),Set.of(2,3),list,Set.of(0),Instant.parse("2026-12-31T18:00:00Z"));
 }
 @Test void csvNeutralizesFormulasButKeepsSignedAmounts(){
  var csv=TableOutput.csv(table(1));
  assertThat(csv).contains("\"'=HYPERLINK(\"\"x\"\")\"").contains("\"-9007199254740993.17\"").startsWith("﻿\"Balance sheet");
 }
 @Test void pdfPaginatesWithFullAmounts()throws Exception{
  var out=new java.io.ByteArrayOutputStream();TableOutput.pdf(table(120),out);
  try(var doc=Loader.loadPDF(out.toByteArray())){var text=new PDFTextStripper().getText(doc);assertThat(doc.getNumberOfPages()).isGreaterThan(1);assertThat(text).contains("-9007199254740993.17","Page 1 of "+doc.getNumberOfPages(),"Generated 2026-12-31 18:00 UTC","A119");}
 }
}
