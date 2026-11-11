package com.cbs.reporting;

import java.io.IOException;
import java.io.OutputStream;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Renders a titled table as spreadsheet-safe CSV or as an A4 PDF. Amount columns are never truncated. */
public final class TableOutput {
    private TableOutput() {}
    public record Table(String title,List<String> subtitle,List<String> columns,Set<Integer> amountColumns,List<List<String>> rows,Set<Integer> boldRows,Instant generatedAt) {}

    public static String csv(Table t){
        var out=new StringBuilder("﻿");row(out,List.of(t.title()));for(var s:t.subtitle())row(out,List.of(s));row(out,t.columns());for(var r:t.rows())row(out,r);return out.toString();
    }
    private static void row(StringBuilder out,List<String> values){
        for(int i=0;i<values.size();i++){
            if(i>0)out.append(',');String v=values.get(i)==null?"":values.get(i);String s=v.stripLeading();
            // Neutralize formula triggers, except plain signed numbers.
            if((!s.isEmpty()&&"=+-@".indexOf(s.charAt(0))>=0&&!s.matches("-?[0-9]+(\\.[0-9]+)?"))||v.indexOf('\t')>=0||v.indexOf('\r')>=0||v.indexOf('\n')>=0)v="'"+v;
            out.append('"').append(v.replace("\"","\"\"")).append('"');
        }
        out.append("\r\n");
    }

    private static final float MARGIN=40,BOTTOM=MARGIN+26,ROW=13,WIDTH=PDRectangle.A4.getWidth()-2*MARGIN;
    public static void pdf(Table t,OutputStream out)throws IOException{
        var regular=new PDType1Font(Standard14Fonts.FontName.HELVETICA);var bold=new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        try(var doc=new PDDocument(IOUtils.createTempFileOnlyStreamCache())){
            doc.getDocumentInformation().setTitle(t.title());doc.getDocumentInformation().setProducer("CBS platform");
            int n=t.columns().size();float amountWidth=Math.min(110,WIDTH*0.6f/Math.max(1,t.amountColumns().size()));float textWidth=(WIDTH-amountWidth*t.amountColumns().size())/Math.max(1,n-t.amountColumns().size());
            float[] w=new float[n],x=new float[n];float pos=MARGIN;for(int i=0;i<n;i++){w[i]=t.amountColumns().contains(i)?amountWidth:textWidth;x[i]=pos;pos+=w[i];}
            PDPageContentStream[] page={null};float[] y={0};
            java.util.function.Consumer<Boolean> newPage=first->{try{
                if(page[0]!=null)page[0].close();var p=new PDPage(PDRectangle.A4);doc.addPage(p);page[0]=new PDPageContentStream(doc,p);y[0]=PDRectangle.A4.getHeight()-MARGIN;
                text(page[0],bold,13,MARGIN,y[0]-=13,fit(t.title(),bold,13,WIDTH));
                if(first)for(var s:t.subtitle())text(page[0],regular,9,MARGIN,y[0]-=12,fit(s,regular,9,WIDTH));
                y[0]-=8;line(page[0],y[0]);y[0]-=11;
                for(int i=0;i<n;i++){String v=fit(t.columns().get(i),bold,8,w[i]-4);text(page[0],bold,8,t.amountColumns().contains(i)?x[i]+w[i]-width(v,bold,8):x[i],y[0],v);}
                y[0]-=4;line(page[0],y[0]);
            }catch(IOException e){throw new java.io.UncheckedIOException(e);}};
            newPage.accept(true);
            for(int r=0;r<t.rows().size();r++){
                if(y[0]-ROW<BOTTOM)newPage.accept(false);y[0]-=ROW;var row=t.rows().get(r);var font=t.boldRows().contains(r)?bold:regular;
                for(int i=0;i<n&&i<row.size();i++){
                    String v=row.get(i)==null?"":row.get(i);
                    if(t.amountColumns().contains(i)){float size=8;while(size>4&&width(v,font,size)>w[i]-2)size-=0.25f;text(page[0],font,size,x[i]+w[i]-width(v,font,size),y[0],v);}
                    else text(page[0],font,8,x[i],y[0],fit(v,font,8,w[i]-4));
                }
            }
            page[0].close();int total=doc.getNumberOfPages();String stamp=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC).format(t.generatedAt());
            for(int i=0;i<total;i++)try(var s=new PDPageContentStream(doc,doc.getPage(i),PDPageContentStream.AppendMode.APPEND,true)){
                line(s,MARGIN+18);text(s,regular,7,MARGIN,MARGIN+8,"Generated "+stamp+" UTC");String p="Page "+(i+1)+" of "+total;text(s,regular,7,MARGIN+WIDTH-width(p,regular,7),MARGIN+8,p);
            }
            doc.save(out);
        }
    }
    private static void text(PDPageContentStream s,PDType1Font f,float size,float x,float y,String v)throws IOException{s.beginText();s.setFont(f,size);s.newLineAtOffset(x,y);s.showText(com.cbs.accounts.StatementPdf.safe(v));s.endText();}
    private static void line(PDPageContentStream s,float y)throws IOException{s.setLineWidth(0.5f);s.moveTo(MARGIN,y);s.lineTo(MARGIN+WIDTH,y);s.stroke();}
    private static float width(String v,PDType1Font f,float size)throws IOException{return f.getStringWidth(com.cbs.accounts.StatementPdf.safe(v))/1000f*size;}
    private static String fit(String v,PDType1Font f,float size,float max)throws IOException{String s=com.cbs.accounts.StatementPdf.safe(v);if(width(s,f,size)<=max)return s;while(!s.isEmpty()&&width(s+"…",f,size)>max)s=s.substring(0,s.length()-1);return s+"…";}
}
