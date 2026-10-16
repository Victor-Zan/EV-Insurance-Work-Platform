package com.evinsurance.platform.document.application;

import com.evinsurance.platform.foundation.api.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;

public final class PdfPagePreview {
    private PdfPagePreview() {}
    public record Image(byte[] bytes,int pages) {}
    public static Image render(byte[] pdf,int page) throws IOException {
        try(var document=Loader.loadPDF(pdf);var output=new ByteArrayOutputStream()){
            int pages=document.getNumberOfPages();
            if(page<1||page>pages)throw ApiException.invalid("页码超出单据范围");
            var box=document.getPage(page-1).getCropBox();
            float dpi=Math.min(110f,1800f*72f/Math.max(1f,Math.max(box.getWidth(),box.getHeight())));
            var image=new PDFRenderer(document).renderImageWithDPI(page-1,dpi);
            javax.imageio.ImageIO.write(image,"png",output);return new Image(output.toByteArray(),pages);
        }
    }
}
