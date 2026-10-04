package com.evinsurance.platform.pricing.application;
import com.evinsurance.platform.pricing.domain.ImportData;
import com.evinsurance.platform.pricing.domain.ImportData.*;
import com.evinsurance.platform.pricing.domain.ImportData.Error;
import com.evinsurance.platform.foundation.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.apache.commons.csv.*;
import org.apache.poi.openxml4j.opc.*;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

@Component public class PriceFileParser {
 public static final int MAX_ROWS=20000,MAX_CELL=256;
 public static final long MAX_BYTES=10L*1024*1024;
 static {ZipSecureFile.setMaxEntrySize(16L*1024*1024);ZipSecureFile.setMaxTextSize(16L*1024*1024);}
 public Parsed parse(MultipartFile file){
  if(file==null||file.isEmpty())throw ApiException.invalid("A nonempty XLSX or UTF-8 CSV file is required");
  if(file.getSize()>MAX_BYTES)throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"IMPORT_LIMIT","Import file exceeds 10 MiB");
  String name=Optional.ofNullable(file.getOriginalFilename()).orElse("").replace('\\','/');
  name=name.substring(name.lastIndexOf('/')+1);if(name.length()>120)name=name.substring(name.length()-120);
  String lower=name.toLowerCase(Locale.ROOT);String format=lower.endsWith(".csv")?"CSV":lower.endsWith(".xlsx")?"EXCEL":null;
  if(format==null)throw ApiException.invalid("Only .xlsx and UTF-8 .csv are supported");
  Path temp=null;
  try {
   temp=Files.createTempFile("price-import-",format.equals("CSV")?".csv":".xlsx");
   MessageDigest digest=MessageDigest.getInstance("SHA-256");
   try(var in=new DigestInputStream(file.getInputStream(),digest);var out=Files.newOutputStream(temp)){
    byte[] buffer=new byte[65536];int count;long total=0;
    while((count=in.read(buffer))!=-1){total+=count;if(total>MAX_BYTES)throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"IMPORT_LIMIT","Import file exceeds 10 MiB");out.write(buffer,0,count);}
   }
   List<RawRow> rows=format.equals("CSV")?csv(temp):excel(temp);
   if(rows.isEmpty())throw ApiException.invalid("Import contains no data rows");
   return new Parsed(name,format,HexFormat.of().formatHex(digest.digest()),rows);
  }catch(ApiException e){throw e;}catch(Exception e){throw ApiException.invalid("Cannot parse file; use the exact template, UTF-8 CSV or a single-sheet XLSX with text dates");}
  finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException e){throw new IllegalStateException("Cannot remove temporary import file");}}
 }
 private List<RawRow> csv(Path file)throws IOException{
  var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
  var rows=new ArrayList<RawRow>();
  try(var reader=new BufferedReader(new InputStreamReader(Files.newInputStream(file),decoder));
      var parser=CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).get().parse(reader)){
   var headers=new ArrayList<>(parser.getHeaderNames());if(!headers.isEmpty())headers.set(0,headers.getFirst().replace("\uFEFF",""));header(headers);
   for(CSVRecord record:parser){var values=new ArrayList<String>();for(String value:record)values.add(value);add(rows,Math.toIntExact(record.getRecordNumber()+1),values);}
  }
  return rows;
 }
 private List<RawRow> excel(Path file)throws Exception{
  var rows=new ArrayList<RawRow>();
  try(var pkg=OPCPackage.open(file.toFile(),PackageAccess.READ)){
   var xssf=new XSSFReader(pkg);xssf.setUseReadOnlySharedStringsTable(true);SharedStrings strings=xssf.getSharedStringsTable();
   var sheets=xssf.getSheetsData();if(!sheets.hasNext())throw ApiException.invalid("XLSX has no worksheet");
   try(var stream=sheets.next()){
    var sax=XMLHelper.newXMLReader();sax.setContentHandler(new DefaultHandler(){
     int rowNumber=0,column=0;String type="";boolean value=false,formula=false,headerSeen=false;StringBuilder text=new StringBuilder();List<String> cells;
     @Override public void startElement(String uri,String local,String q,Attributes attributes){
      if(q.equals("row")){rowNumber=Integer.parseInt(attributes.getValue("r"));cells=new ArrayList<>();}
      if(q.equals("c")){column=new CellReference(attributes.getValue("r")).getCol();if(column>=ImportData.FIELDS.size())throw ApiException.invalid("XLSX contains columns beyond the template");type=Objects.toString(attributes.getValue("t"),"");text.setLength(0);formula=false;}
      if(q.equals("f"))formula=true;if(q.equals("v")||q.equals("t"))value=true;
     }
     @Override public void characters(char[] ch,int start,int length){
      if(value){if(text.length()+length>MAX_CELL)throw ApiException.invalid("XLSX cell exceeds 256 characters");text.append(ch,start,length);}
     }
     @Override public void endElement(String uri,String local,String q){
      if(q.equals("v")||q.equals("t"))value=false;
      if(q.equals("c")){
       String result=formula?"[FORMULA_NOT_SUPPORTED]":text.toString();
       if(type.equals("s")&&!formula){result=strings.getItemAt(Integer.parseInt(result)).getString();if(result.length()>MAX_CELL)throw ApiException.invalid("XLSX shared string exceeds 256 characters");}
       while(cells.size()<=column)cells.add("");cells.set(column,result);
      }
      if(q.equals("row")){while(cells.size()<ImportData.FIELDS.size())cells.add("");if(rowNumber==1){header(cells);headerSeen=true;}else {if(!headerSeen)throw ApiException.invalid("XLSX row 1 must contain the template header");if(cells.stream().anyMatch(s->!s.isBlank()))add(rows,rowNumber,cells);}}
     }
    });sax.parse(new InputSource(stream));
   }
   if(sheets.hasNext()){try(var extra=sheets.next()){throw ApiException.invalid("Use exactly one worksheet per XLSX import");}}
  }
  return rows;
 }
 private static void header(List<String> values){if(!values.equals(ImportData.FIELDS))throw ApiException.invalid("Header fields/order must match the published price import template");}
 private static void add(List<RawRow> rows,int number,List<String> cells){
  if(rows.size()>=MAX_ROWS)throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"IMPORT_LIMIT","Import exceeds 20000 rows; split into bounded batches");
  var values=new LinkedHashMap<String,String>();var errors=new ArrayList<Error>();
  if(cells.size()!=ImportData.FIELDS.size())errors.add(new Error(number,"columns","Expected "+ImportData.FIELDS.size()+" template columns",Integer.toString(cells.size())));
  for(int i=0;i<ImportData.FIELDS.size();i++){
   String raw=i<cells.size()?cells.get(i):"";if(raw.equals("[FORMULA_NOT_SUPPORTED]"))errors.add(new Error(number,ImportData.FIELDS.get(i),"Excel formulas are not accepted; use literal values","[formula]"));if(raw.length()>MAX_CELL)errors.add(new Error(number,ImportData.FIELDS.get(i),"Cell exceeds 256 characters",ImportData.context(raw)));
   values.put(ImportData.FIELDS.get(i),raw.substring(0,Math.min(MAX_CELL,raw.length())).strip());
  }
  rows.add(new RawRow(number,values,errors));
 }
}
