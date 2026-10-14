package com.evinsurance.platform.integration.ocr;

import java.io.*;
import javax.imageio.ImageIO;

public final class BaiduImage {
    private BaiduImage(){}
    public static void validate(byte[] bytes){
        if(bytes==null||bytes.length==0||bytes.length>10485760)throw new OcrFailure("BAIDU_IMAGE_SIZE");
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext())throw new OcrFailure("BAIDU_IMAGE_FORMAT");
            var reader=readers.next();
            try{
                String format=reader.getFormatName();
                if(!format.equalsIgnoreCase("JPEG")&&!format.equalsIgnoreCase("PNG"))throw new OcrFailure("BAIDU_IMAGE_FORMAT");
                reader.setInput(input,true,true);int w=reader.getWidth(0),h=reader.getHeight(0);
                if(w<15||h<15||w>4096||h>4096)throw new OcrFailure("BAIDU_IMAGE_DIMENSIONS");
                var decodeWarning=new java.util.concurrent.atomic.AtomicBoolean();
                reader.addIIOReadWarningListener((source,warning)->decodeWarning.set(true));
                var image=reader.read(0);if(image==null||decodeWarning.get())throw new OcrFailure("BAIDU_IMAGE_DAMAGED");
                int first=image.getRGB(0,0);boolean blank=true;
                for(int y=0;y<h&&blank;y++)for(int x=0;x<w;x++)if(image.getRGB(x,y)!=first){blank=false;break;}
                if(blank)throw new OcrFailure("BAIDU_IMAGE_BLANK");
                // A complete JPEG may contain an application trailer after EOI (e.g. messaging exports).
                // Decoder warnings reject truncated streams; keep and transmit every original byte.
                if(format.equalsIgnoreCase("JPEG")){boolean eoi=false;for(int i=0;i<bytes.length-1;i++)if((bytes[i]&255)==255&&(bytes[i+1]&255)==217){eoi=true;break;}if(!eoi)throw new OcrFailure("BAIDU_IMAGE_DAMAGED");}
                if(format.equalsIgnoreCase("PNG")&&(bytes.length<12||bytes[bytes.length-8]!=73||bytes[bytes.length-7]!=69||bytes[bytes.length-6]!=78||bytes[bytes.length-5]!=68))throw new OcrFailure("BAIDU_IMAGE_DAMAGED");
            }finally{reader.dispose();}
        }catch(OcrFailure e){throw e;}catch(Exception e){throw new OcrFailure("BAIDU_IMAGE_DAMAGED");}
    }
}
