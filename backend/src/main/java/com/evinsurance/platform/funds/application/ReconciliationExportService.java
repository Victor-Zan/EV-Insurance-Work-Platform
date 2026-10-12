package com.evinsurance.platform.funds.application;

import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.audit.application.AuditService.Action;
import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.foundation.api.PageResponse;
import com.evinsurance.platform.funds.infrastructure.ReconciliationMapper;
import com.evinsurance.platform.funds.infrastructure.ReconciliationMapper.Row;
import com.evinsurance.platform.identity.domain.CurrentUser;
import com.evinsurance.platform.identity.domain.Role;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationExportService {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZONE);
    private static final String[] HEADERS={"工单号","报案号","保险公司","维修网点","案件状态","建单时间",
        "保司应收目标（元）","已收净额（元）","应收差额（元）","网点应付目标（元）","已付净额（元）","应付差额（元）"};
    private final ReconciliationMapper mapper;
    private final AuditService audit;

    public ReconciliationExportService(ReconciliationMapper mapper,AuditService audit){this.mapper=mapper;this.audit=audit;}

    public byte[] export(int page,int size,String status,String query,String insuranceCompany,Long shopId,LocalDate from,LocalDate to){
        var actor=CurrentUser.require();
        if(!actor.roles().contains(Role.ADMIN)&&!actor.roles().contains(Role.CUSTOMER_SERVICE))throw ApiException.denied();
        int offset=PageResponse.offset(page,size);
        query=filter(query);insuranceCompany=filter(insuranceCompany);
        if(from!=null&&to!=null&&from.isAfter(to))throw ApiException.invalid("Start date must not be after end date");
        if(shopId!=null&&shopId<=0)throw ApiException.invalid("Invalid shop ID");
        Instant start=from==null?null:from.atStartOfDay(ZONE).toInstant();
        Instant until=to==null?null:to.plusDays(1).atStartOfDay(ZONE).toInstant();
        List<Row> rows=mapper.page(status,query,insuranceCompany,shopId,start,until,offset,size);
        byte[] bytes=workbook(rows);
        audit.recordIdentifier(actor,Action.FUNDS_EXPORT,"RECONCILIATION",null,"Scoped reconciliation XLSX; page="+page+"; rows="+rows.size());
        return bytes;
    }

    private String filter(String value){if(value==null||value.isBlank())return null;if(value.length()>100)throw ApiException.invalid("Filter is too long");return value.trim();}

    static byte[] workbook(List<Row> rows){
        try(var book=new XSSFWorkbook();var output=new ByteArrayOutputStream()){
            var sheet=book.createSheet("工单资金对账");
            CellStyle header=book.createCellStyle();header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font bold=book.createFont();bold.setBold(true);bold.setColor(IndexedColors.WHITE.getIndex());header.setFont(bold);
            var first=sheet.createRow(0);
            for(int i=0;i<HEADERS.length;i++){var cell=first.createCell(i);cell.setCellValue(HEADERS[i]);cell.setCellStyle(header);sheet.setColumnWidth(i,(i==2?24:i==3?22:i>=6?20:18)*256);}
            int index=1;
            for(Row item:rows){var row=sheet.createRow(index++);int cell=0;
                put(row,cell++,item.businessNo());put(row,cell++,item.claimNo());put(row,cell++,item.insuranceCompany());
                put(row,cell++,item.shopName());put(row,cell++,item.status());put(row,cell++,TIME.format(item.createdAt()));
                money(row,cell++,item.receivableTarget());money(row,cell++,item.receivedNet());money(row,cell++,difference(item.receivableTarget(),item.receivedNet()));
                money(row,cell++,item.payableTarget());money(row,cell++,item.paidNet());money(row,cell++,difference(item.payableTarget(),item.paidNet()));
            }
            sheet.createFreezePane(0,1);sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,Math.max(1,rows.size()),0,HEADERS.length-1));
            book.write(output);return output.toByteArray();
        }catch(IOException error){throw new IllegalStateException("Cannot export reconciliation XLSX",error);}
    }
    private static BigDecimal difference(BigDecimal target,BigDecimal net){return target==null?null:target.subtract(net==null?BigDecimal.ZERO:net);}
    private static void money(org.apache.poi.ss.usermodel.Row row,int index,BigDecimal value){if(value!=null)row.createCell(index).setCellValue(value.setScale(2,java.math.RoundingMode.UNNECESSARY).toPlainString());}
    private static void put(org.apache.poi.ss.usermodel.Row row,int index,String value){
        if(value==null)return;
        String safe=value.stripLeading();
        row.createCell(index).setCellValue(!safe.isEmpty()&&"=+@-".indexOf(safe.charAt(0))>=0?"'"+value:value);
    }
}
