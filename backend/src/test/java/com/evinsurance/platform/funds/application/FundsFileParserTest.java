package com.evinsurance.platform.funds.application;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
class FundsFileParserTest {
    private final FundsFileParser parser=new FundsFileParser();
    private MockMultipartFile csv(String text){return new MockMultipartFile("file","synthetic.csv","text/csv",text.getBytes(StandardCharsets.UTF_8));}
    @Test void csvPreservesQuotedNotesAndMalformedRowsWithoutGuessingColumns(){
        String header=String.join(",",FundsFileParser.FIELDS)+"\n";
        var parsed=parser.parse(csv(header+"FLOW,RECEIVE,CLAIM,WO,0.01,2026-10-10T10:00:00+08:00,\"合成,备注\"\nSHORT,RECEIVE\n"));
        assertThat(parsed.rows()).hasSize(2);assertThat(parsed.rows().getFirst().values().get("note")).isEqualTo("合成,备注");assertThat(parsed.rows().get(1).values().get("__parseError")).isEqualTo("COLUMN_COUNT");
    }
    @Test void xlsxTextAmountsAndTimestampsArePreservedButFormulaRowsAreMarkedInvalid() throws Exception {
        byte[] bytes;try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()){
            var sheet=workbook.createSheet("SYNTHETIC");var header=sheet.createRow(0);for(int i=0;i<FundsFileParser.FIELDS.size();i++)header.createCell(i).setCellValue(FundsFileParser.FIELDS.get(i));
            var row=sheet.createRow(1);String[] values={"FLOW","RECEIVE","CLAIM","WO","100.01","2026-10-10T10:00:00+08:00","合成材料"};for(int i=0;i<values.length;i++)row.createCell(i).setCellValue(values[i]);
            var formula=sheet.createRow(2);for(int i=0;i<values.length;i++)formula.createCell(i).setCellValue(values[i]);formula.getCell(4).setCellFormula("1+1");workbook.write(output);bytes=output.toByteArray();
        }
        var parsed=parser.parse(new MockMultipartFile("file","synthetic.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes));assertThat(parsed.rows().getFirst().values().get("amount")).isEqualTo("100.01");assertThat(parsed.rows().get(1).values().get("__parseError")).isEqualTo("FORMULA_NOT_SUPPORTED");
    }
    @Test void rejectsWrongHeadersOversizeAndTooManyRows(){
        assertThatThrownBy(()->parser.parse(csv("wrong\nvalue\n"))).isInstanceOf(com.evinsurance.platform.foundation.api.ApiException.class);
        assertThatThrownBy(()->parser.parse(new MockMultipartFile("file","large.csv","text/csv",new byte[10485761]))).isInstanceOf(com.evinsurance.platform.foundation.api.ApiException.class);
        String content=String.join(",",FundsFileParser.FIELDS)+"\n"+"FLOW,RECEIVE,C,W,1,2026-10-10T02:00:00Z,SYNTHETIC\n".repeat(2001);assertThatThrownBy(()->parser.parse(csv(content))).isInstanceOf(com.evinsurance.platform.foundation.api.ApiException.class);
    }
}
