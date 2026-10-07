package com.cbs.accounting;

import java.util.*;
import java.util.stream.Collectors;

public final class ReportCsv {
    private ReportCsv(){}
    public static String write(AccountingReportService.Report r){
        StringBuilder out=new StringBuilder("\uFEFF");
        row(out,List.of("Report",r.kind().name()));row(out,List.of("Bank",r.bankName(),r.bankId().toString()));
        row(out,List.of("Legal entity",r.legalEntityName(),r.legalEntityId().toString()));row(out,List.of("Currency",r.currency()));
        row(out,List.of("From",Objects.toString(r.fromDate(),""),"Through",Objects.toString(r.toDate(),"")));
        row(out,List.of("Generated at UTC",r.generatedAt().toString()));
        for(var item:r.summary().entrySet())row(out,List.of(item.getKey(),item.getValue()));
        row(out,r.columns());for(var values:r.rows())row(out,values);return out.toString();
    }
    private static void row(StringBuilder out,List<String> values){out.append(values.stream().map(ReportCsv::cell).collect(Collectors.joining(","))).append("\r\n");}
    static String cell(String value){
        // Neutralize spreadsheet formulas while retaining genuine signed decimal values.
        String trimmed=value.stripLeading();
        if(!value.matches("-?[0-9]+(\\.[0-9]+)?")&&!trimmed.isEmpty()&&"=+@-".indexOf(trimmed.charAt(0))>=0)value="'"+value;
        if(value.startsWith("\t")||value.startsWith("\r")||value.startsWith("\n"))value="'"+value;
        return "\""+value.replace("\"","\"\"")+"\"";
    }
}
