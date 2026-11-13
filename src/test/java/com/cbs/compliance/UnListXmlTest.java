package com.cbs.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cbs.tenancy.TenantException;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnListXmlTest {

    @Test
    void parsesValidIndividualAndEntityRecords() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <CONSOLIDATED_LIST>
                  <INDIVIDUALS>
                    <INDIVIDUAL>
                      <DATAID>12345</DATAID>
                      <REFERENCE_NUMBER>QDi.001</REFERENCE_NUMBER>
                      <FIRST_NAME>AHMED</FIRST_NAME>
                      <SECOND_NAME>ALI</SECOND_NAME>
                      <THIRD_NAME>MOHAMED</THIRD_NAME>
                      <FOURTH_NAME>HASSAN</FOURTH_NAME>
                      <UN_LIST_TYPE>Al-Qaida</UN_LIST_TYPE>
                      <INDIVIDUAL_ALIAS>
                        <QUALITY>Good</QUALITY>
                        <ALIAS_NAME>AHMED THE TALL</ALIAS_NAME>
                      </INDIVIDUAL_ALIAS>
                      <INDIVIDUAL_ALIAS>
                        <QUALITY>Low</QUALITY>
                        <ALIAS_NAME>ABU ALI</ALIAS_NAME>
                      </INDIVIDUAL_ALIAS>
                      <INDIVIDUAL_DATE_OF_BIRTH>
                        <TYPE_OF_DATE>EXACT</TYPE_OF_DATE>
                        <DATE>1980-05-15</DATE>
                      </INDIVIDUAL_DATE_OF_BIRTH>
                      <INDIVIDUAL_DATE_OF_BIRTH>
                        <TYPE_OF_DATE>APPROXIMATELY</TYPE_OF_DATE>
                        <YEAR>1980</YEAR>
                      </INDIVIDUAL_DATE_OF_BIRTH>
                    </INDIVIDUAL>
                  </INDIVIDUALS>
                  <ENTITIES>
                    <ENTITY>
                      <DATAID>67890</DATAID>
                      <FIRST_NAME>GLOBAL RELIEF FOUNDATION</FIRST_NAME>
                      <UN_LIST_TYPE>Taliban</UN_LIST_TYPE>
                      <ENTITY_ALIAS>
                        <QUALITY>Good</QUALITY>
                        <ALIAS_NAME>GRF</ALIAS_NAME>
                      </ENTITY_ALIAS>
                    </ENTITY>
                  </ENTITIES>
                </CONSOLIDATED_LIST>
                """;

        List<List<String>> rows = UnListXml.records(xml);
        assertThat(rows).hasSize(3);

        // Header
        assertThat(rows.get(0)).containsExactly("entry_ref", "entity_type", "name", "aliases", "birth_date", "country", "program");

        // Individual row
        List<String> individual = rows.get(1);
        assertThat(individual.get(0)).isEqualTo("QDi.001");
        assertThat(individual.get(1)).isEqualTo("INDIVIDUAL");
        assertThat(individual.get(2)).isEqualTo("AHMED ALI MOHAMED HASSAN");
        assertThat(individual.get(3)).isEqualTo("AHMED THE TALL;ABU ALI");
        assertThat(individual.get(4)).isEqualTo("1980-05-15");
        assertThat(individual.get(5)).isEmpty();
        assertThat(individual.get(6)).isEqualTo("Al-Qaida");

        // Entity row
        List<String> entity = rows.get(2);
        assertThat(entity.get(0)).isEqualTo("67890"); // Falls back to DATAID when REFERENCE_NUMBER missing
        assertThat(entity.get(1)).isEqualTo("ENTITY");
        assertThat(entity.get(2)).isEqualTo("GLOBAL RELIEF FOUNDATION");
        assertThat(entity.get(3)).isEqualTo("GRF");
        assertThat(entity.get(4)).isEmpty();
        assertThat(entity.get(5)).isEmpty();
        assertThat(entity.get(6)).isEqualTo("Taliban");
    }

    @Test
    void rejectsDisallowedDoctypeDeclaration() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE CONSOLIDATED_LIST [<!ENTITY test "test">]>
                <CONSOLIDATED_LIST/>
                """;
        assertThatThrownBy(() -> UnListXml.records(xml))
                .isInstanceOf(TenantException.class)
                .hasMessageContaining("The file is not a readable UN consolidated list XML document");
    }

    @Test
    void rejectsWrongRootTag() {
        String xml = """
                <?xml version="1.0"?>
                <OTHER_LIST/>
                """;
        assertThatThrownBy(() -> UnListXml.records(xml))
                .isInstanceOf(TenantException.class)
                .hasMessageContaining("The root element must be CONSOLIDATED_LIST");
    }

    @Test
    void dropsLowQualityAliasesWhenExceedingMaxNames() {
        StringBuilder aliases = new StringBuilder();
        // 1 primary name + 25 aliases = 26 names (exceeding MAX_NAMES=20)
        for (int i = 1; i <= 15; i++) {
            aliases.append("<INDIVIDUAL_ALIAS><QUALITY>Good</QUALITY><ALIAS_NAME>GOOD ALIAS ").append(i).append("</ALIAS_NAME></INDIVIDUAL_ALIAS>");
        }
        for (int i = 1; i <= 10; i++) {
            aliases.append("<INDIVIDUAL_ALIAS><QUALITY>Low</QUALITY><ALIAS_NAME>LOW ALIAS ").append(i).append("</ALIAS_NAME></INDIVIDUAL_ALIAS>");
        }
        String xml = """
                <CONSOLIDATED_LIST>
                  <INDIVIDUALS>
                    <INDIVIDUAL>
                      <DATAID>999</DATAID>
                      <FIRST_NAME>TEST PERSON</FIRST_NAME>
                """ + aliases + """
                    </INDIVIDUAL>
                  </INDIVIDUALS>
                </CONSOLIDATED_LIST>
                """;

        List<List<String>> rows = UnListXml.records(xml);
        assertThat(rows).hasSize(2);
        String aliasesField = rows.get(1).get(3);
        String[] aliasArr = aliasesField.split(";");
        // primary name (1) + 19 aliases = 20 total names
        assertThat(aliasArr).hasSize(19);
        // All 15 good aliases should be present
        for (int i = 1; i <= 15; i++) {
            assertThat(aliasesField).contains("GOOD ALIAS " + i);
        }
        // Only 4 low quality aliases should make it
        assertThat(aliasesField).contains("LOW ALIAS 1");
        assertThat(aliasesField).contains("LOW ALIAS 4");
        assertThat(aliasesField).doesNotContain("LOW ALIAS 5");
    }
}
