package com.cbs.payments;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.cbs.tenancy.TenantException;

class MpesaStatementCsvTest {
 private static final String HEADER="receipt,date,type,credit,debit,balance,payment_reference,beneficiary\n";
 private StatementImportModels.Upload input(String csv,String closing,Map<String,String> columns){return new StatementImportModels.Upload(0,UUID.randomUUID(),"TEST","WALLET",LocalDate.of(2026,1,1),LocalDate.of(2026,1,1),"1000",closing,"TEST/FILE",columns,csv);}
 private void fails(String csv,String closing,String code){assertThatThrownBy(()->MpesaStatementCsv.parse(input(csv,closing,Map.of()))).isInstanceOfSatisfying(TenantException.class,e->assertThat(e.code()).isEqualTo(code));}
 @Test void exactDecimalsBomQuotedFieldsAndMappedHeaders(){String csv="\ufeff"+HEADER.replace("receipt","Receipt No.")+"\"R1\",2026-01-01,PAYOUT,0,100,900,PAY-1,254700000001\r\nR1,2026-01-01,FEE,0,0.17,899.83,,\r\n";var rows=MpesaStatementCsv.parse(input(csv,"899.83",Map.of("receipt","Receipt No.")));assertThat(rows).hasSize(2);assertThat(rows.getLast().balance()).isEqualTo("899.83");}
 @Test void validatesEveryRunningBalanceAndClosingBalance(){fails(HEADER+"R1,2026-01-01,FEE,0,5,999,,","995","STATEMENT_RUNNING_BALANCE");fails(HEADER+"R1,2026-01-01,FEE,0,5,995,,","994","STATEMENT_CLOSING_BALANCE");}
 @Test void rejectsDuplicatesAndUnknownSemantics(){fails(HEADER+"R1,2026-01-01,FEE,0,5,995,,\nR1,2026-01-01,FEE,0,5,990,,","990","STATEMENT_DUPLICATE_ROW");fails(HEADER+"R1,2026-01-01,Success,0,5,995,,","995","STATEMENT_ROW_INVALID");fails(HEADER+"R1,2026-01-01,PAYOUT,0,5,995,,","995","STATEMENT_ROW_INVALID");}
 @Test void rejectsMalformedCsvAndAmbiguousColumns(){fails(HEADER+"\"R1,2026-01-01,FEE,0,5,995,,","995","STATEMENT_CSV_INVALID");assertThatThrownBy(()->MpesaStatementCsv.parse(input(HEADER,"1000",Map.of("receipt","date")))).isInstanceOfSatisfying(TenantException.class,e->assertThat(e.code()).isEqualTo("STATEMENT_COLUMNS_INVALID"));assertThat(MpesaStatementCsv.records("a,b\r\n\"a,\"\"b\"\"\",\"two\nlines\"\r\n")).containsExactly(List.of("a","b"),List.of("a,\"b\"","two\nlines"));}
 @Test void rejectsFloatNotationNegativeMovementsAndWrongDates(){fails(HEADER+"R1,2026-01-01,FEE,0,1e2,900,,","900","STATEMENT_MONEY_INVALID");fails(HEADER+"R1,2026-01-01,FEE,0,-5,1005,,","1005","STATEMENT_ROW_INVALID");fails(HEADER+"R1,2025-12-31,FEE,0,5,995,,","995","STATEMENT_PERIOD_INVALID");}
 @Test void boundedFilesAndEmptyPeriods(){assertThat(MpesaStatementCsv.parse(input(HEADER,"1000",Map.of()))).isEmpty();assertThatThrownBy(()->MpesaStatementCsv.parse(input("x".repeat(1048577),"1000",Map.of()))).isInstanceOfSatisfying(TenantException.class,e->assertThat(e.code()).isEqualTo("STATEMENT_FILE_TOO_LARGE"));fails(HEADER+"x".repeat(1025),"1000","STATEMENT_FILE_TOO_LARGE");}
}
