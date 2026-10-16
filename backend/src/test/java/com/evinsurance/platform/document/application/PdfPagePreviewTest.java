package com.evinsurance.platform.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

class PdfPagePreviewTest {
    @Test void rendersRealPdfPagesAsPngAndRejectsInvalidPages() throws Exception {
        byte[] bytes;
        try(var pdf=new PDDocument();var output=new ByteArrayOutputStream()){
            pdf.addPage(new PDPage());pdf.addPage(new PDPage());pdf.save(output);bytes=output.toByteArray();
        }
        var page=PdfPagePreview.render(bytes,2);assertThat(page.pages()).isEqualTo(2);
        var image=ImageIO.read(new ByteArrayInputStream(page.bytes()));assertThat(image.getWidth()).isGreaterThan(600);assertThat(image.getHeight()).isGreaterThan(800);
        assertThrows(com.evinsurance.platform.foundation.api.ApiException.class,()->PdfPagePreview.render(bytes,0));
        assertThrows(com.evinsurance.platform.foundation.api.ApiException.class,()->PdfPagePreview.render(bytes,3));
    }
}
