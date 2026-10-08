package com.cbs.contracts;
import static com.cbs.contracts.ContractModels.*;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
/** Replays actual posted financial events. Future projected interest is never a receivable. */
public final class LoanArrears {
 private LoanArrears(){}
 public record Due(LocalDate dueDate,String principal,String interest,String fees,String total,long daysPastDue,String bucket){}
 public record Totals(String principal,String interest,String fees,String total){}
 public record Report(LocalDate asOf,List<Due> instalments,Map<String,Totals> buckets,Totals outstanding,Totals overdue,long daysPastDue,boolean reconciled,boolean accrualComplete){}
 private static class Amounts {BigDecimal principal=BigDecimal.ZERO,interest=BigDecimal.ZERO,fees=BigDecimal.ZERO;BigDecimal total(){return principal.add(interest).add(fees);}Totals values(){return new Totals(text(principal),text(interest),text(fees),text(total()));}void add(Amounts a){principal=principal.add(a.principal);interest=interest.add(a.interest);fees=fees.add(a.fees);}}
 public static String bucket(long days){return days<=0?"CURRENT":days<=30?"1_30":days<=60?"31_60":days<=90?"61_90":days<=180?"91_180":"181_PLUS";}
 public static Set<UUID> reversed(List<Posting> events){var ids=new HashSet<UUID>();for(var p:events)if(p.kind()==Kind.REVERSE)ids.add(p.originalEventId());return ids;}
 private static void reduce(TreeMap<LocalDate,Amounts> rows,BigDecimal payment,boolean interest){for(var row:rows.values()){var value=interest?row.interest:row.principal;var used=value.min(payment);if(interest)row.interest=value.subtract(used);else row.principal=value.subtract(used);payment=payment.subtract(used);if(payment.signum()==0)return;}if(payment.signum()!=0)throw new IllegalArgumentException("Repayment allocation exceeds posted balance");}
 public static Report calculate(List<Posting> source,LocalDate asOf){
  var events=source.stream().filter(p->!p.businessDate().isAfter(asOf)).toList();var reversed=reversed(events);var rows=new TreeMap<LocalDate,Amounts>();var fees=new HashMap<UUID,LoanFeeModels.Details>();Position last=null;
  for(var p:events){last=p.after();if(p.kind()==Kind.REVERSE||reversed.contains(p.id()))continue;var terms=p.contract().terms();if(terms.type()!=Type.LOAN)throw new IllegalArgumentException("Loan required");int scale=scale(p.contract());
   switch(p.kind()){
    case OPEN,RESTRUCTURE -> {rows.clear();var effective=p.kind()==Kind.OPEN?terms:p.loanServicing().renewedTerms();for(var i:schedule(effective,scale))rows.computeIfAbsent(i.dueDate(),k->new Amounts()).principal=decimal(i.principal());}
    case ACCRUE -> {
     LocalDate from=p.before().accruedThrough(),through=p.after().accruedThrough();String carry=p.before().carry();BigDecimal allocated=BigDecimal.ZERO;
     while(from.isBefore(through)){
      LocalDate due=null;for(int n=1;n<=terms.instalments();n++){var d=terms.startDate().plusMonths(n);if(d.isAfter(from)){due=d;break;}}
      if(due==null)due=from.plusDays(1);var end=through.isBefore(due)?through:due;
      var calc=interest(p.before().principal(),terms.annualRatePercent(),terms.dayBasis(),from,end,carry,scale);carry=calc.carry();var amount=decimal(calc.interest());if(amount.signum()<0)throw new IllegalArgumentException("Negative interest allocation");rows.computeIfAbsent(due,k->new Amounts()).interest=rows.get(due).interest.add(amount);allocated=allocated.add(amount);from=end;
     }
     if(allocated.compareTo(decimal(p.after().interest()).subtract(decimal(p.before().interest())))!=0)throw new IllegalArgumentException("Accrual allocation mismatch");
    }
    case REPAY -> {reduce(rows,decimal(p.before().interest()).subtract(decimal(p.after().interest())),true);reduce(rows,decimal(p.before().principal()).subtract(decimal(p.after().principal())),false);}
    case WRITE_OFF -> rows.clear();
    case FEE_ASSESS,FEE_PAY,FEE_WAIVE -> fees.put(p.feeServicing().feeId(),p.feeServicing());
    default -> {}
   }
  }
  for(var fee:fees.values())rows.computeIfAbsent(fee.dueDate(),k->new Amounts()).fees=rows.get(fee.dueDate()).fees.add(decimal(fee.after()));
  var buckets=new LinkedHashMap<String,Amounts>();for(String key:List.of("CURRENT","1_30","31_60","61_90","91_180","181_PLUS"))buckets.put(key,new Amounts());var all=new Amounts();var overdue=new Amounts();long dpd=0;List<Due> due=new ArrayList<>();
  for(var entry:rows.entrySet()){var a=entry.getValue();if(a.total().signum()==0)continue;long days=Math.max(0,ChronoUnit.DAYS.between(entry.getKey(),asOf));all.add(a);buckets.get(bucket(days)).add(a);if(days>0){overdue.add(a);dpd=Math.max(dpd,days);}due.add(new Due(entry.getKey(),text(a.principal),text(a.interest),text(a.fees),text(a.total()),days,bucket(days)));}
  var values=new LinkedHashMap<String,Totals>();buckets.forEach((k,v)->values.put(k,v.values()));boolean reconciled=last!=null&&all.principal.compareTo(decimal(last.principal()))==0&&all.interest.compareTo(decimal(last.interest()))==0;
  return new Report(asOf,List.copyOf(due),Collections.unmodifiableMap(values),all.values(),overdue.values(),dpd,reconciled,last!=null&&(last.closed()||!last.accruedThrough().isBefore(asOf)));
 }
}
