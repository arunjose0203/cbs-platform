package com.cbs.accounts;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Printable A4 customer statement. Lines are laid out as they stream in; page numbers
 * ("Page n of N") are stamped once the total is known. Pages are buffered in a temporary
 * file rather than memory, so long statements do not exhaust the heap.
 * Built-in PDF fonts cover Western European text (English, French, Swahili); other characters
 * in free-text narrations are replaced with "?".
 */
public final class StatementPdf implements StatementService.Sink {
    private static final Map<String,Map<String,String>> LABELS=Map.of(
        "en",Map.ofEntries(Map.entry("title","Account statement"),Map.entry("holders","Account holders"),Map.entry("account","Account"),Map.entry("name","Account name"),Map.entry("customer","Customer number"),Map.entry("currency","Currency"),Map.entry("period","Period"),
            Map.entry("opening","Opening balance"),Map.entry("debits","Total debits"),Map.entry("credits","Total credits"),Map.entry("closing","Closing balance"),Map.entry("entries","Entries"),
            Map.entry("date","Date"),Map.entry("reference","Reference"),Map.entry("narration","Narration"),Map.entry("debit","Debit"),Map.entry("credit","Credit"),Map.entry("balance","Balance"),
            Map.entry("none","No transactions in this period."),Map.entry("page","Page %d of %d"),Map.entry("generated","Generated %s UTC"),Map.entry("to","to")),
        "fr",Map.ofEntries(Map.entry("title","Relevé de compte"),Map.entry("holders","Titulaires"),Map.entry("account","Compte"),Map.entry("name","Intitulé du compte"),Map.entry("customer","Numéro client"),Map.entry("currency","Devise"),Map.entry("period","Période"),
            Map.entry("opening","Solde d'ouverture"),Map.entry("debits","Total des débits"),Map.entry("credits","Total des crédits"),Map.entry("closing","Solde de clôture"),Map.entry("entries","Écritures"),
            Map.entry("date","Date"),Map.entry("reference","Référence"),Map.entry("narration","Libellé"),Map.entry("debit","Débit"),Map.entry("credit","Crédit"),Map.entry("balance","Solde"),
            Map.entry("none","Aucune opération sur cette période."),Map.entry("page","Page %d sur %d"),Map.entry("generated","Généré le %s UTC"),Map.entry("to","au")),
        "sw",Map.ofEntries(Map.entry("title","Taarifa ya akaunti"),Map.entry("holders","Wamiliki"),Map.entry("account","Akaunti"),Map.entry("name","Jina la akaunti"),Map.entry("customer","Namba ya mteja"),Map.entry("currency","Sarafu"),Map.entry("period","Kipindi"),
            Map.entry("opening","Salio la mwanzo"),Map.entry("debits","Jumla ya matoleo"),Map.entry("credits","Jumla ya maingizo"),Map.entry("closing","Salio la mwisho"),Map.entry("entries","Miamala"),
            Map.entry("date","Tarehe"),Map.entry("reference","Kumbukumbu"),Map.entry("narration","Maelezo"),Map.entry("debit","Toleo"),Map.entry("credit","Ingizo"),Map.entry("balance","Salio"),
            Map.entry("none","Hakuna miamala katika kipindi hiki."),Map.entry("page","Ukurasa %d kati ya %d"),Map.entry("generated","Imetolewa %s UTC"),Map.entry("to","hadi")));
    public static final Set<String> LANGUAGES=LABELS.keySet();

    private static final float MARGIN=40, TOP=PDRectangle.A4.getHeight()-MARGIN, BOTTOM=MARGIN+28, ROW=13;
    // Column x-positions and widths: date, reference, narration, debit, credit, balance.
    private static final float[] W={50,88,121,78,78,90};
    private static final float[] X={MARGIN,MARGIN+52,MARGIN+142,MARGIN+265,MARGIN+345,MARGIN+425}; // last column ends at the right margin
    private final OutputStream out;private final String requested;private final Instant generated;
    private final PDType1Font regular=new PDType1Font(Standard14Fonts.FontName.HELVETICA), bold=new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private PDDocument document;private PDPageContentStream page;private float y;private Map<String,String> label;private StatementService.Header header;private long lines;

    public StatementPdf(OutputStream out,String language,Instant generated){this.out=out;this.requested=language;this.generated=generated;}

    @Override public void header(StatementService.Header h)throws IOException{
        header=h;String language=requested!=null&&LABELS.containsKey(requested)?requested:LABELS.containsKey(h.language())?h.language():"en";label=LABELS.get(language);
        document=new PDDocument(IOUtils.createTempFileOnlyStreamCache());
        document.getDocumentInformation().setTitle(label.get("title")+" "+h.accountNumber());document.getDocumentInformation().setProducer("CBS platform");
        newPage(true);
    }
    @Override public void line(StatementService.Line l)throws IOException{
        if(y-ROW<BOTTOM)newPage(false);
        y-=ROW;lines++;
        String[] cells={l.businessDate().toString(),l.reference(),l.narration(),blankZero(l.debit()),blankZero(l.credit()),l.balance()};
        for(int i=0;i<cells.length;i++){
            if(i>=3){money(cells[i],X[i],W[i],y);continue;}
            text(fit(cells[i],regular,8,W[i]),regular,8,X[i],y);
        }
    }
    @Override public void finish()throws IOException{
        if(lines==0){y-=ROW*1.5f;text(label.get("none"),regular,9,MARGIN,y);}
        page.close();int total=document.getNumberOfPages();String stamp=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC).format(generated);
        for(int i=0;i<total;i++)try(var s=new PDPageContentStream(document,document.getPage(i),PDPageContentStream.AppendMode.APPEND,true)){
            page=s;line(MARGIN,MARGIN+18,PDRectangle.A4.getWidth()-MARGIN);
            text(safe(String.format(label.get("generated"),stamp)+" · "+header.accountNumber()),regular,7,MARGIN,MARGIN+8);
            String n=String.format(label.get("page"),i+1,total);text(n,regular,7,PDRectangle.A4.getWidth()-MARGIN-width(n,regular,7),MARGIN+8);
        }
        document.save(out);document.close();out.flush();
    }

    private void newPage(boolean first)throws IOException{
        if(page!=null)page.close();
        var p=new PDPage(PDRectangle.A4);document.addPage(p);page=new PDPageContentStream(document,p);y=TOP;
        text(fit(header.bankName(),bold,14,515),bold,14,MARGIN,y-=14);
        if(!header.legalEntityName().isBlank())text(fit(header.legalEntityName(),regular,9,515),regular,9,MARGIN,y-=12);
        String title=label.get("title");text(title,bold,12,PDRectangle.A4.getWidth()-MARGIN-width(title,bold,12),TOP-14);
        y-=10;
        if(first){
            String[][] facts={{label.get("account"),header.accountNumber()},{label.get("name"),header.accountName()},{label.get("holders"),header.holders()==null?"":String.join(", ",header.holders())},{label.get("customer"),header.customerNumber()},{label.get("currency"),header.currency()},{label.get("period"),header.from()+" "+label.get("to")+" "+header.to()}};
            for(var f:facts){y-=12;text(f[0],bold,9,MARGIN,y);text(fit(f[1],regular,9,380),regular,9,MARGIN+110,y);}
            y-=10;
            String[][] totals={{label.get("opening"),header.openingBalance()},{label.get("debits"),header.totalDebits()},{label.get("credits"),header.totalCredits()},{label.get("closing"),header.closingBalance()},{label.get("entries"),String.valueOf(header.total())}};
            float x=MARGIN;for(var f:totals){text(f[0],regular,8,x,y-10);float size=10;while(size>5&&width(f[1],bold,size)>99)size-=0.25f;text(f[1],bold,size,x,y-23);x+=103;}
            y-=34;
        }else{y-=4;text(header.accountNumber()+" · "+header.currency()+" · "+header.from()+" "+label.get("to")+" "+header.to(),regular,9,MARGIN,y-=10);}
        y-=8;line(MARGIN,y,PDRectangle.A4.getWidth()-MARGIN);y-=11;
        String[] heads={label.get("date"),label.get("reference"),label.get("narration"),label.get("debit"),label.get("credit"),label.get("balance")};
        for(int i=0;i<heads.length;i++){String v=fit(heads[i],bold,8,W[i]);text(v,bold,8,i>=3?X[i]+W[i]-width(v,bold,8):X[i],y);}
        y-=4;line(MARGIN,y,PDRectangle.A4.getWidth()-MARGIN);
    }
    private void text(String value,PDType1Font font,float size,float x,float at)throws IOException{page.beginText();page.setFont(font,size);page.newLineAtOffset(x,at);page.showText(safe(value));page.endText();}
    private void line(float from,float at,float to)throws IOException{page.setLineWidth(0.5f);page.moveTo(from,at);page.lineTo(to,at);page.stroke();}
    /** Amounts are never truncated: the font shrinks for unusually long values instead. */
    private void money(String v,float x,float w,float at)throws IOException{
        float size=8;while(size>4&&width(v,regular,size)>w)size-=0.25f;
        text(v,regular,size,x+w-width(v,regular,size),at);
    }
    private static String blankZero(String v){return v==null||v.equals("0")?"":v;}
    /** Keeps characters the built-in font can encode; replaces control and unsupported characters. */
    static String safe(String value){
        if(value==null)return "";var b=new StringBuilder(value.length());
        for(char ch:value.toCharArray()){if(Character.isISOControl(ch))b.append(' ');else if(ENCODABLE.computeIfAbsent(ch,StatementPdf::encodable))b.append(ch);else b.append('?');}
        return b.toString();
    }
    private static final Map<Character,Boolean> ENCODABLE=new java.util.concurrent.ConcurrentHashMap<>();
    private static final PDType1Font PROBE=new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static boolean encodable(char ch){try{PROBE.encode(String.valueOf(ch));return true;}catch(IOException|IllegalArgumentException e){return false;}}
    private static float width(String v,PDType1Font font,float size)throws IOException{return font.getStringWidth(safe(v))/1000f*size;}
    private static String fit(String v,PDType1Font font,float size,float max)throws IOException{
        String s=safe(v);if(width(s,font,size)<=max)return s;
        while(!s.isEmpty()&&width(s+"…",font,size)>max)s=s.substring(0,s.length()-1);
        return s+"…";
    }
}
