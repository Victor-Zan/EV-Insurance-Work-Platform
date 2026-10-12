package com.evinsurance.platform.quotation.application;

import com.evinsurance.platform.quotation.infrastructure.QuotationRows.DocumentHeader;
import com.evinsurance.platform.quotation.infrastructure.QuotationRows.Formal;
import com.evinsurance.platform.quotation.infrastructure.QuotationRows.FormalItem;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

@Component
public class FormalQuotePdf {
    private static final float LEFT=42, RIGHT=553, BOTTOM=72;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public byte[] render(Formal quote,DocumentHeader header,List<FormalItem> items) {
        try(PDDocument document=new PDDocument();var input=getClass().getResourceAsStream("/fonts/SourceHanSerifSC-Heavy.ttf")) {
            if(input==null)throw new IllegalStateException("Quote PDF font missing");
            PDType0Font font=PDType0Font.load(document,input);
            var page=new Page(document,font);
            page.text("平台正式报价单",18,LEFT,796);
            page.y=766;
            page.detail("报价单编号",quote.id().toString());
            page.detail("版本",String.valueOf(quote.versionNo()));
            page.detail("生成时间",TIME.format(quote.createdAt().atZone(ZoneId.of("Asia/Shanghai"))));
            page.detail("工单号",header.businessNo());
            page.detail("报案号",header.claimNo());
            page.detail("保险公司",header.insuranceCompany());
            page.detail("车主",header.ownerName());
            page.detail("车辆",header.vehicleBrand()+" "+header.vehicleModel());
            if("BACKFILL".equals(header.snapshotKind()))page.detail("历史版本", "案件抬头按迁移时记录生成");
            page.y-=14;
            page.tableHeader();
            for(FormalItem item:items) {
                var lines=wrap(font,item.description(),245,10);
                float height=Math.max(27,lines.size()*15+10);
                page.space(height+36);
                if(page.headerNeeded)page.tableHeader();
                float top=page.y;
                page.text(String.valueOf(item.lineNo()),9,LEFT,top-12);
                for(String line:lines){page.text(line,10,75,top-12);top-=15;}
                page.right(String.valueOf(item.quantity()),9,365,page.y-12);
                page.right(item.externalUnitPrice().toPlainString(),9,455,page.y-12);
                page.right(item.externalAmount().toPlainString(),9,RIGHT,page.y-12);
                page.y-=height;
                page.rule(page.y);
            }
            page.space(82);
            page.y-=25;
            page.text("对外报价总额  CNY",12,LEFT,page.y);
            page.right(quote.total().toPlainString(),14,RIGHT,page.y);
            page.y-=34;
            page.text("明细行金额为准；展示单价保留 6 位小数。",9,LEFT,page.y);
            page.y-=20;
            page.text("本报价以保险公司最终核损结果为准。",9,LEFT,page.y);
            page.close();
            document.getDocumentInformation().setTitle("平台正式报价单 v"+quote.versionNo());
            try(var output=new ByteArrayOutputStream()){document.save(output);return output.toByteArray();}
        }catch(IOException e){throw new IllegalStateException("Cannot render formal quote PDF",e);}
    }

    private static List<String> wrap(PDType0Font font,String value,float width,float size) throws IOException {
        String source=safe(font,value),word="";
        var lines=new ArrayList<String>();
        for(int offset=0;offset<source.length();) {
            int code=source.codePointAt(offset);offset+=Character.charCount(code);
            if(code=='\n'){lines.add(word);word="";continue;}
            String next=word+new String(Character.toChars(code));
            if(!word.isEmpty()&&font.getStringWidth(next)*size/1000>width){lines.add(word);word=new String(Character.toChars(code));}
            else word=next;
        }
        lines.add(word);
        return lines;
    }

    private static String safe(PDType0Font font,String value) throws IOException {
        StringBuilder result=new StringBuilder();
        for(int code:value.codePoints().toArray()) {
            if(code=='\n'){result.append('\n');continue;}
            if(Character.isISOControl(code))continue;
            String glyph=new String(Character.toChars(code));
            try{font.getStringWidth(glyph);result.append(glyph);}
            catch(IllegalArgumentException unsupported){result.append('?');}
        }
        return result.toString();
    }

    private static class Page {
        final PDDocument document;final PDType0Font font;
        PDPageContentStream stream;float y;boolean headerNeeded;
        Page(PDDocument document,PDType0Font font) throws IOException {this.document=document;this.font=font;newPage();}
        void newPage() throws IOException {
            if(stream!=null)stream.close();
            var page=new PDPage(PDRectangle.A4);document.addPage(page);
            stream=new PDPageContentStream(document,page);
            y=780;headerNeeded=document.getNumberOfPages()>1;
            if(headerNeeded){text("平台正式报价单（续）",12,LEFT,797);y=765;}
        }
        void close() throws IOException {stream.close();}
        void space(float height) throws IOException {if(y-height<BOTTOM)newPage();}
        void text(String value,float size,float x,float at) throws IOException {
            stream.beginText();stream.setFont(font,size);stream.newLineAtOffset(x,at);
            stream.showText(safe(font,value));stream.endText();
        }
        void right(String value,float size,float edge,float at) throws IOException {
            String safe=safe(font,value);text(safe,size,edge-font.getStringWidth(safe)*size/1000,at);
        }
        void rule(float at) throws IOException {
            stream.setLineWidth(.5f);stream.moveTo(LEFT,at);stream.lineTo(RIGHT,at);stream.stroke();
        }
        void detail(String label,String value) throws IOException {
            for(String line:wrap(font,label+"："+(value==null?"":value),RIGHT-LEFT,9)){
                space(17);text(line,9,LEFT,y);y-=17;
            }
        }
        void tableHeader() throws IOException {
            space(28);rule(y+5);
            text("序号",9,LEFT,y-12);text("项目",9,75,y-12);
            right("数量",9,365,y-12);right("对外单价",9,455,y-12);right("行金额（元）",9,RIGHT,y-12);
            y-=28;rule(y);headerNeeded=false;
        }
    }
}
