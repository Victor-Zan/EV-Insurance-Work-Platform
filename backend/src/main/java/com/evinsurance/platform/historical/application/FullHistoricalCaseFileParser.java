package com.evinsurance.platform.historical.application;

import com.evinsurance.platform.document.application.FileService;
import com.evinsurance.platform.foundation.api.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class FullHistoricalCaseFileParser {
    static final List<String> FIELDS=List.of("序号","保司","机构","定损员","案件发出日期","车主电话","车主姓名","案件号",
        "电动车车牌号","电动车品牌","电动车车型","报价配件商","电动车主意愿","受损部位","单位","配件商明细报价","CP明细报价",
        "维修网点","省","市","区","详细地址","网点联系人","网点联系电话","车主-报价金额","配件商-报价金额","创配-报价金额",
        "最终定损金额","基本毛利率","是否为低毛利率案件","施工是否完成","施工完成日期","是否打款","打款日期","打款金额",
        "是否与配件商结算","结算门店名称","结算日期","结算价格","实际毛利率","整车照片","备注","负责人","协同人","创建人",
        "创建时间","更新时间","最后跟进时间","分配时间","是否归档");

    public record Detail(int rowNumber,Map<String,String> original,String correctedSupplierAmount) {}
    public record CaseData(Map<String,String> fields,String repairShop,List<Detail> details) {}
    public record Parsed(String name,String hash,byte[] bytes,List<CaseData> cases) {}
    private record Identifier(String orderNo,String caseNo) {}

    public Parsed parse(MultipartFile file,List<Integer> excluded,Integer correctionRow,String correctionAmount) {
        if(file==null||file.isEmpty()||file.getSize()>10485760)throw ApiException.invalid("请选择不超过 10 MiB 的 XLSX 文件");
        String name=Objects.toString(file.getOriginalFilename(),"").replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);
        if(!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")||name.length()>120)throw ApiException.invalid("请使用 XLSX 文件");
        Set<Integer> ignored=new HashSet<>(excluded==null?List.of():excluded);
        if(ignored.size()>2000||ignored.stream().anyMatch(number->number<5||number>20004))throw ApiException.invalid("排除行号须为有效的 Excel 明细行");
        if((correctionRow==null)!=(correctionAmount==null)||correctionRow!=null&&ignored.contains(correctionRow))
            throw ApiException.invalid("更正必须指定一个未排除的行号和配件商金额");
        String corrected=correctionAmount==null?null:positiveAmount(correctionAmount).toPlainString();
        try(var input=file.getInputStream()) {
            byte[] bytes=input.readNBytes(10485761);
            if(bytes.length>10485760)throw ApiException.invalid("文件超过 10 MiB");
            try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                var sheet=book.getSheet("导入文档");
                if(sheet==null||sheet.getLastRowNum()>20003)throw ApiException.invalid("需要“导入文档”工作表，且行号须在支持范围内");
                var display=new DataFormatter(Locale.ROOT);
                checkHeader(sheet,display);
                var grouped=new LinkedHashMap<Identifier,CaseData>();
                var foundExcluded=new HashSet<Integer>();
                String shop="";
                int accepted=0;
                boolean correctionFound=false;
                for(int rowNumber=5;rowNumber<=sheet.getLastRowNum()+1;rowNumber++) {
                    var row=sheet.getRow(rowNumber-1);
                    if(row==null)continue;
                    if(ignored.contains(rowNumber)){foundExcluded.add(rowNumber);continue;}
                    var values=new LinkedHashMap<String,String>();
                    for(int col=0;col<FIELDS.size();col++)values.put(FIELDS.get(col),value(row.getCell(col),display));
                    if(values.values().stream().allMatch(String::isBlank))continue;
                    if(row.getLastCellNum()>FIELDS.size()||++accepted>2000)throw ApiException.invalid("最多支持 50 列、2000 条明细");
                    var id=new Identifier(values.get("序号").strip(),values.get("案件号").strip());
                    if(id.orderNo().isEmpty()||id.caseNo().isEmpty())throw ApiException.invalid("Excel 第 "+rowNumber+" 行缺少平台工单号或案件号");
                    if(!values.get("维修网点").isBlank())shop=values.get("维修网点").strip();
                    var groupedCase=grouped.get(id);
                    if(groupedCase==null) {
                        groupedCase=new CaseData(new LinkedHashMap<>(values),shop,new ArrayList<>());
                        grouped.put(id,groupedCase);
                    } else {
                        if(!shop.equals(groupedCase.repairShop()))throw ApiException.invalid("同一案件跨维修网点分界，请核对 Excel 第 "+rowNumber+" 行");
                        for(var field:values.entrySet())if(groupedCase.fields().get(field.getKey()).isBlank())groupedCase.fields().put(field.getKey(),field.getValue());
                    }
                    boolean correct=Objects.equals(correctionRow,rowNumber);
                    correctionFound|=correct;
                    groupedCase.details().add(new Detail(rowNumber,values,correct?corrected:null));
                }
                if(grouped.isEmpty()||!foundExcluded.equals(ignored)||corrected!=null&&!correctionFound)
                    throw ApiException.invalid("请检查导入范围、排除行和更正行是否存在");
                return new Parsed(name,FileService.hash(bytes),bytes,List.copyOf(grouped.values()));
            }
        }catch(IOException error){throw ApiException.invalid("无法读取历史 XLSX");}
    }

    private void checkHeader(Sheet sheet,DataFormatter display) {
        for(int col=0;col<FIELDS.size();col++) {
            int row=col>=18&&col<=21?3:col>=13&&col<=23?2:1;
            String expected=switch(col) {
                case 15->"配件商报价金额";
                case 16->"cp报价金额";
                case 17->"网点名称";
                case 22->"联系人";
                case 23->"联系电话";
                default->FIELDS.get(col);
            };
            if(sheet.getRow(row)==null||!value(sheet.getRow(row).getCell(col),display).replace("*","").replace("^","").strip().equalsIgnoreCase(expected))
                throw ApiException.invalid("“导入文档”表头不匹配，第 "+(col+1)+" 列应为 "+expected);
        }
    }

    private String value(Cell cell,DataFormatter display) {
        if(cell==null)return "";
        if(cell.getCellType()==CellType.FORMULA||cell.getCellType()==CellType.ERROR)
            throw ApiException.invalid("Excel 第 "+(cell.getRowIndex()+1)+" 行包含公式或错误值");
        String result=cell.getCellType()==CellType.NUMERIC&&DateUtil.isCellDateFormatted(cell)
            ? cell.getLocalDateTimeCellValue().toString() : display.formatCellValue(cell);
        if(result.length()>8192)throw ApiException.invalid("Excel 单元格超长");
        return result;
    }

    public static BigDecimal positiveAmount(String value) {
        if(value==null||!value.strip().matches("[0-9]{1,16}(\\.[0-9]{1,2})?"))throw ApiException.invalid("配件单价须为最多两位小数的正金额");
        var amount=new BigDecimal(value.strip());
        if(amount.signum()<=0)throw ApiException.invalid("配件单价须大于零");
        return amount;
    }
}
