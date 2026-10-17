package com.evinsurance.platform.integration.ocr;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
class ShopQuoteCandidatesTest {
    private final ObjectMapper json=new ObjectMapper();
    private tools.jackson.databind.JsonNode extract(String... words){var raw=json.createObjectNode();var lines=raw.putArray("words_result");for(String word:words)lines.addObject().put("words",word);return new ShopQuoteCandidates().organize(json.valueToTree(new HandwritingCandidates().extract(raw,"SHOP_QUOTE")),json);}
    @Test void unitsMissingNumbersLetterAndMismatchRemainEvidence(){var result=extract("配件名称：合成轮胎 数量：l 单位：条 单价：25.00 金额：25.00","维修项目：名称？ 数量：2 单价：10.00 金额：30.00");
        assertThat(result.path("items").size()).isEqualTo(2);assertThat(result.at("/items/0/quantity").asString()).isEmpty();assertThat(result.at("/items/0/unit").asString()).isEqualTo("条");assertThat(result.at("/items/1/calculatedAmount").asString()).isEqualTo("20.00");assertThat(result.at("/items/1/lineAmount").asString()).isEqualTo("30.00");assertThat(result.path("warnings").toString()).contains("不转换","不符","模糊");}
    @Test void feeOnlyAmountDoesNotInventQuantityPriceOrAddSummary(){var result=extract("人工费：80.00","拖车费：50.00","其他费用：10.00","合计：140.00","维修单号：SYNTHETIC-1");assertThat(result.path("items").size()).isEqualTo(3);for(var row:result.path("items")){assertThat(row.path("quantity").asString()).isEmpty();assertThat(row.path("unitPrice").asString()).isEmpty();}assertThat(result.at("/fields/amountCandidate").asString()).isEqualTo("140.00");assertThat(result.path("fields").has("claimNo")).isFalse();}
    @Test void nonStructuredTextIsNotInventedAndOriginalUnchanged(){var result=extract("模糊的自由手写文字 l ？");assertThat(result.path("items").isEmpty()).isTrue();assertThat(result.at("/lines/0/text").asString()).isEqualTo("模糊的自由手写文字 l ？");}
    @Test void partialLabelledRowKeepsMissingPriceAndQuantityBlank(){var result=extract("配件名称：合成镜 单位：只 金额：9.90");assertThat(result.at("/items/0/quantity").asString()).isEmpty();assertThat(result.at("/items/0/unitPrice").asString()).isEmpty();assertThat(result.at("/items/0/lineAmount").asString()).isEqualTo("9.90");}
    @Test void coordinatesWithUnitColumnRemainDistinctFromQuantityAndFees(){
        var raw=json.createObjectNode();var words=raw.putArray("words_result");String[] headers={"项目名称","数量","单位","单价","金额"},values={"合成轮胎","2","条","12.50","25.00"};
        for(int row=0;row<2;row++)for(int col=0;col<5;col++){var word=words.addObject();word.put("words",row==0?headers[col]:values[col]);word.putObject("location").put("left",20+100*col).put("top",20+40*row).put("width",50).put("height",20);}
        words.addObject().put("words","人工费：30.00");
        var result=new ShopQuoteCandidates().organize(json.valueToTree(new HandwritingCandidates().extract(raw,"SHOP_QUOTE")),json);
        assertThat(result.path("items").size()).isEqualTo(2);var table=result.path("items").get(1);assertThat(table.path("description").asString()).isEqualTo("合成轮胎");assertThat(table.path("unit").asString()).isEqualTo("条");assertThat(table.path("quantity").asString()).isEqualTo("2");assertThat(table.path("unitPrice").asString()).isEqualTo("12.50");
    }
}
