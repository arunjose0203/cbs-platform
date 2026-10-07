package com.cbs.accounting;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReportCsvTest {
    @Test void csvQuotesAndNeutralizesFormulasWithoutChangingSignedMoney(){
        assertThat(ReportCsv.cell("=1+2\nnext")).isEqualTo("\"'=1+2\nnext\"");
        assertThat(ReportCsv.cell("  @SUM(A1)")).startsWith("\"'");
        assertThat(ReportCsv.cell("\tformula")).startsWith("\"'");
        assertThat(ReportCsv.cell("Bank, \"A\"")).isEqualTo("\"Bank, \"\"A\"\"\"");
        assertThat(ReportCsv.cell("-9007199254740993.17")).isEqualTo("\"-9007199254740993.17\"");
    }
}
