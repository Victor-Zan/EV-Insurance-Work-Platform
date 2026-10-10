package com.evinsurance.platform.funds.application;
import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.document.application.FileService;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.apache.commons.csv.*;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import java.io.*;
import java.nio.charset.*;
import java.util.*;

@Component
public class FundsFileParser {
    public static final List<String> FIELDS=List.of("transactionNo","direction","claimNo","businessNo","amount","occurredAt","note");
    public record Row(int number,Map<String,String> values) {}
    public record Parsed(String name,String format,String hash,byte[] bytes,List<Row> rows) {}
    public Parsed parse(MultipartFile file){
        if(file==null||file.isEmpty()||file.getSize()>10485760)throw ApiException.invalid("Use a nonempty XLSX/UTF-8 CSV, at most 10 MiB");
        String name=Objects.toString(file.getOriginalFilename(),"").replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);if(name.length()>120)name=name.substring(name.length()-120);
        String ext=name.toLowerCase(Locale.ROOT);String format=ext.endsWith(".csv")?"CSV":ext.endsWith(".xlsx")?"XLSX":null;if(format==null)throw ApiException.invalid("Only XLSX/CSV imports are supported");
        try(var input=file.getInputStream()){
            byte[] bytes=input.readNBytes(10485761);if(bytes.length>10485760)throw ApiException.invalid("Import exceeds 10 MiB");
            List<Row> rows=format.equals("CSV")?csv(bytes):excel(bytes);if(rows.isEmpty())throw ApiException.invalid("Import has no data rows");return new Parsed(name,format,FileService.hash(bytes),bytes,rows);
        }catch(ApiException error){throw error;}catch(Exception error){throw ApiException.invalid("Cannot parse import: exact header, UTF-8 CSV or single-sheet XLSX with text timestamps required");}
    }
    private void header(List<String> values){if(!values.isEmpty())values.set(0,values.getFirst().replace("\uFEFF",""));if(!values.equals(FIELDS))throw ApiException.invalid("Use header: "+String.join(",",FIELDS));}
    private void add(List<Row> rows,int number,List<String> cells,boolean formula){
        if(rows.size()>=2000)throw ApiException.invalid("Import exceeds 2000 data rows");var values=new LinkedHashMap<String,String>();
        for(int i=0;i<FIELDS.size();i++)values.put(FIELDS.get(i),i<cells.size()?cells.get(i):"");
        if(cells.size()!=FIELDS.size())values.put("__parseError","COLUMN_COUNT");if(formula)values.put("__parseError","FORMULA_NOT_SUPPORTED");rows.add(new Row(number,values));
    }
    private List<Row> csv(byte[] bytes)throws Exception{
        var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);var rows=new ArrayList<Row>();
        try(var reader=new InputStreamReader(new ByteArrayInputStream(bytes),decoder);var parser=CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).get().parse(reader)){
            header(new ArrayList<>(parser.getHeaderNames()));for(var record:parser){var cells=new ArrayList<String>();record.forEach(cells::add);add(rows,Math.toIntExact(record.getRecordNumber()+1),cells,false);}
        }return rows;
    }
    private List<Row> excel(byte[] bytes)throws Exception{
        var rows=new ArrayList<Row>();try(var pkg=OPCPackage.open(new ByteArrayInputStream(bytes))){var xssf=new XSSFReader(pkg);xssf.setUseReadOnlySharedStringsTable(true);var strings=xssf.getSharedStringsTable();var sheets=xssf.getSheetsData();if(!sheets.hasNext())throw ApiException.invalid("XLSX has no worksheet");
            try(var stream=sheets.next()){var sax=XMLHelper.newXMLReader();sax.setContentHandler(new DefaultHandler(){
                int number,column;String type;boolean capture,formula,rowFormula,seen;StringBuilder text=new StringBuilder();List<String> cells;
                @Override public void startElement(String uri,String local,String q,Attributes attributes){if(q.equals("row")){number=Integer.parseInt(attributes.getValue("r"));cells=new ArrayList<>();rowFormula=false;}if(q.equals("c")){column=new CellReference(attributes.getValue("r")).getCol();if(column>=FIELDS.size())throw ApiException.invalid("XLSX columns exceed template");type=Objects.toString(attributes.getValue("t"),"");text.setLength(0);formula=false;}if(q.equals("f")){formula=true;rowFormula=true;}if(q.equals("v")||q.equals("t"))capture=true;}
                @Override public void characters(char[] ch,int start,int length){if(capture){if(text.length()+length>2000)throw ApiException.invalid("XLSX cell exceeds 2000 characters");text.append(ch,start,length);}}
                @Override public void endElement(String uri,String local,String q){if(q.equals("v")||q.equals("t"))capture=false;if(q.equals("c")){String value=formula?"[FORMULA_NOT_SUPPORTED]":text.toString();if(type.equals("s")&&!formula)value=strings.getItemAt(Integer.parseInt(value)).getString();if(value.length()>2000)throw ApiException.invalid("XLSX cell exceeds 2000 characters");while(cells.size()<=column)cells.add("");cells.set(column,value);}if(q.equals("row")){while(cells.size()<FIELDS.size())cells.add("");if(number==1){if(rowFormula)throw ApiException.invalid("Header cannot contain formulas");header(cells);seen=true;}else{if(!seen)throw ApiException.invalid("Header must be row 1");if(cells.stream().anyMatch(v->!v.isBlank()))add(rows,number,cells,rowFormula);}}}
            });sax.parse(new InputSource(stream));}if(sheets.hasNext())throw ApiException.invalid("Use exactly one XLSX worksheet");
        }return rows;
    }
}
