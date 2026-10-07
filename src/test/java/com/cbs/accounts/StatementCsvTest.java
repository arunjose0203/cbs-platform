package com.cbs.accounts;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StatementCsvTest {
 @Test void quotesUntrustedFieldsAndNeutralizesSpreadsheetFormulas(){
  var date=LocalDate.of(2026,10,7);
  var line=new StatementService.Line(UUID.randomUUID(),date," =HYPERLINK(\"bad\")","Note,\"quoted\"\nnext","10","0","90");
  var statement=new StatementService.Statement("000123","KES",date,date,"100","90",List.of(line),1,10000,0);
  String csv=StatementCsv.render(statement);
  assertThat(csv).startsWith("\uFEFF\"Account\",\"000123\"")
   .contains("\"' =HYPERLINK(\"\"bad\"\")\"")
   .contains("\"'Note,\"\"quoted\"\"\nnext\"")
   .contains("\"10\",\"0\",\"90\"\r\n");
 }
 @Test void emptyStatementRetainsOpeningAndClosingBalances(){
  var date=LocalDate.of(2026,10,7);
  assertThat(StatementCsv.render(new StatementService.Statement("A","UGX",date,date,"100","100",List.of(),0,10000,0)))
   .contains("\"Opening balance\",\"100\",\"Closing balance\",\"100\"")
   .endsWith("\"Balance\"\r\n");
 }
}
