package com.evinsurance.platform.historical.application;

import com.evinsurance.platform.document.application.FileService;
import com.evinsurance.platform.foundation.api.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class HistoricalCaseFileParser {
    public static final List<String> FIELDS=List.of("报案号","车主姓名","车主电话","车牌号","品牌","车型","事故地址","故障描述",
        "纬度","经度","定损金额","维修日期","维修网点","状态","故障类型","来源","备注");
    public record Row(int number,Map<String,String> values,boolean formula) {}
    public record Parsed(String name,String hash,byte[] bytes,List<Row> rows) {}

    public Parsed parse(MultipartFile file) {
        if(file==null||file.isEmpty()||file.getSize()>10485760)throw ApiException.invalid("Use a nonempty XLSX file up to 10 MiB");
        String name=String.valueOf(file.getOriginalFilename()).replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);
        if(!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")||name.length()>120)throw ApiException.invalid("Use the historical-case XLSX template");
        try {
            byte[] bytes;
            try(var input=file.getInputStream()){bytes=input.readNBytes(10485761);}
            if(bytes.length>10485760)throw ApiException.invalid("Import exceeds 10 MiB");
            var rows=new ArrayList<Row>();
            try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                var sheet=book.getSheet("历史订单");
                if(sheet==null||book.getSheet("字段说明")==null)throw ApiException.invalid("Template sheets 历史订单 and 字段说明 are required");
                if(sheet.getLastRowNum()>20000)throw ApiException.invalid("Historical-case sheet extends beyond the supported row range");
                var header=sheet.getRow(0);
                if(header==null||header.getLastCellNum()!=FIELDS.size())throw ApiException.invalid("Historical-case header must contain exactly 17 columns");
                var display=new DataFormatter(Locale.ROOT);
                for(int column=0;column<FIELDS.size();column++){
                    if(!FIELDS.get(column).equals(value(header.getCell(column),display)))throw ApiException.invalid("Historical-case header does not match the template");
                }
                for(int number=1;number<=sheet.getLastRowNum();number++){
                    var line=sheet.getRow(number);if(line==null)continue;
                    if(line.getLastCellNum()>FIELDS.size())throw ApiException.invalid("Historical-case row has extra columns: "+(number+1));
                    boolean formula=false;var values=new LinkedHashMap<String,String>();boolean nonempty=false;
                    for(int column=0;column<FIELDS.size();column++){
                        Cell cell=line.getCell(column);if(cell!=null&&(cell.getCellType()==CellType.FORMULA||cell.getCellType()==CellType.ERROR))formula=true;
                        String value=value(cell,display);if(value.length()>2000)throw ApiException.invalid("Cell exceeds 2000 characters at row "+(number+1));
                        values.put(FIELDS.get(column),value);nonempty|=!value.isBlank();
                    }
                    if(!nonempty)continue;
                    if(rows.size()>=2000)throw ApiException.invalid("Import exceeds 2000 data rows");
                    rows.add(new Row(number+1,values,formula));
                }
            }
            if(rows.isEmpty())throw ApiException.invalid("Import has no data rows");
            return new Parsed(name,FileService.hash(bytes),bytes,rows);
        }catch(ApiException error){throw error;}
        catch(IOException|RuntimeException error){throw ApiException.invalid("Cannot parse historical-case XLSX template");}
    }

    private String value(Cell cell,DataFormatter display) {
        if(cell==null||cell.getCellType()==CellType.BLANK)return "";
        if(cell.getCellType()==CellType.FORMULA||cell.getCellType()==CellType.ERROR)return "[FORMULA_OR_ERROR]";
        if(cell.getCellType()==CellType.NUMERIC&&org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell))
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        return display.formatCellValue(cell);
    }
}
