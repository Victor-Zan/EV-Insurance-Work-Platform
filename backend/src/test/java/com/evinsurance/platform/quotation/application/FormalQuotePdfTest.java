package com.evinsurance.platform.quotation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.evinsurance.platform.quotation.infrastructure.QuotationRows.DocumentHeader;
import com.evinsurance.platform.quotation.infrastructure.QuotationRows.Formal;
import com.evinsurance.platform.quotation.infrastructure.QuotationRows.FormalItem;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class FormalQuotePdfTest {
    @Test void generatesReadableExternalOnlyVersionedPdf() throws Exception {
        UUID id=UUID.randomUUID(),caseId=UUID.randomUUID();
        var quote=new Formal(id,caseId,2,UUID.randomUUID(),"CNY","FIXED_AMOUNT",
            new BigDecimal("10.00"),new BigDecimal("60.00"),new BigDecimal("10.00"),new BigDecimal("70.00"),
            "PRO_RATA_LARGEST_REMAINDER_V1",1,Instant.parse("2026-10-11T00:00:00Z"));
        var header=new DocumentHeader(id,"WO-TEST-100","CLAIM-100","测试保险公司","测试车主","电动","自行车","ISSUE");
        var item=new FormalItem(1,"维修项目",2,new BigDecimal("30.00"),new BigDecimal("60.00"),
            new BigDecimal("10.00"),new BigDecimal("35.000000"),new BigDecimal("70.00"));
        byte[] bytes=new FormalQuotePdf().render(quote,header,List.of(item));
        assertThat(bytes).startsWith("%PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        try(var pdf=Loader.loadPDF(bytes)) {
            String text=new PDFTextStripper().getText(pdf);
            assertThat(text).contains("平台正式报价单","WO-TEST-100","CLAIM-100","测试保险公司","35.000000","70.00");
            assertThat(text).doesNotContain("30.00","60.00","10.00","加价","网点结算","markup");
        }
        var sparseHeader=new DocumentHeader(id,"WO-TEST-100","CLAIM-100",null,null,null,null,"ISSUE");
        try(var pdf=Loader.loadPDF(new FormalQuotePdf().render(quote,sparseHeader,List.of(item)))) {
            assertThat(new PDFTextStripper().getText(pdf)).contains("车辆").doesNotContain("null");
        }
    }
}
