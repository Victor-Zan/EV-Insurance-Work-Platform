package com.evinsurance.platform.integration.ocr;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;

/** Adds quote-only evidence to the shared extractor. Never infers quantity, price or a case identity. */
public final class ShopQuoteCandidates {
    public static final String RULE_VERSION = "SHOP_QUOTE_GRID_2";
    private static final Pattern ROW=Pattern.compile("^(?:配件名称|维修项目|项目名称)[：:]\\s*(.*?)(?=\\s+(?:数量|单位|单价|金额)[：:]|$)");
    private static final Pattern CELL=Pattern.compile("(数量|单位|单价|金额)[：:]\\s*(.*?)(?=\\s+(?:数量|单位|单价|金额)[：:]|$)");
    private static final Pattern FEE=Pattern.compile("^(工时费|人工费|拖车费|其他费用)[：:]\\s*([0-9]{1,16}(?:\\.[0-9]{1,2})?)(?:元)?$");
    public ObjectNode organize(ObjectNode base,ObjectMapper json){
        return organize(base,json,null,null);
    }
    public ObjectNode organize(ObjectNode base,ObjectMapper json,byte[] original,byte[] raw){
        ObjectNode out=base.deepCopy();ArrayNode items=json.createArrayNode(),gridItems=json.createArrayNode();
        var warnings=new LinkedHashSet<String>();
        for(var warning:base.path("warnings"))warnings.add(warning.asString());
        boolean grid=raw!=null&&ShopQuoteGrid.extract(base.path("lines"),json.readTree(raw).path("words_result"),original,gridItems,warnings);
        int labelledRows=0;
        for(var source:base.path("lines")){
            String text=source.path("text").asString("").strip();var row=ROW.matcher(text);var fee=FEE.matcher(text);
            if(footer(text))continue;
            if(row.find()){
                if(grid||footer(row.group(1)))continue;
                labelledRows++;
                var item=items.addObject();item.put("description",row.group(1).strip()).put("quantity","").put("unit","").put("unitPrice","").put("lineAmount","");
                var cell=CELL.matcher(text);var seen=new HashSet<String>();
                while(cell.find()){
                    String name=cell.group(1),value=cell.group(2).strip(),field=switch(name){case "数量"->"quantity";case "单位"->"unit";case "单价"->"unitPrice";default->"lineAmount";};
                    if(!seen.add(field)){item.put(field,"");warnings.add("同一行字段重复，须核对，未选择其中一个值");continue;}
                    if(field.equals("unit"))item.put(field,value);
                    else item.put(field,number(field,value,warnings));
                }
                item.set("evidence",source.deepCopy());item.put("origin","PROGRAM_LABELLED_QUOTE_ROW");
            }else if(!grid&&fee.matches()){
                var item=items.addObject();item.put("description",fee.group(1)).put("quantity","").put("unit","").put("unitPrice","").put("lineAmount",fee.group(2)).put("feeCandidate",true);
                item.set("evidence",source.deepCopy());item.put("origin","PROGRAM_EXPLICIT_FEE");warnings.add("费用仅作为一条项目候选，数量/单价须人工补充；勿与已有费用重复计入");
            }
        }
        // Shared coordinate-table output is used when no complete labelled rows were available.
        if(grid)items.addAll(gridItems);
        else if(labelledRows==0){
            var fees=new HashSet<String>();
            items.forEach(item->fees.add(item.path("description").asString()));
            for(var candidate:base.path("items")){
                var item=(ObjectNode)candidate.deepCopy();
                String description=item.path("description").asString();
                if(footer(description))continue;
                if(fees.contains(description)){
                    warnings.add("费用同时出现在表格及文字摘要，保留单一待核对候选，勿重复计费");
                    continue;
                }
                if(!item.has("unit"))item.put("unit",unit(base.path("lines"),item));
                items.add(item);
            }
        }
        if(items.size()>200)throw new OcrFailure("BAIDU_RESULT_TOO_LARGE");
        for(var node:items){var item=(ObjectNode)node;
            String quantity=number("quantity",item.path("quantity").asString(""),warnings);
            String price=number("unitPrice",item.path("unitPrice").asString(""),warnings);
            item.put("quantity",quantity).put("unitPrice",price);
            item.remove("calculatedAmount");
            if(!quantity.isEmpty()&&!price.isEmpty()){
                var amount=new BigDecimal(quantity).multiply(new BigDecimal(price));item.put("calculatedAmount",amount.toPlainString());
                String lineAmount=item.path("lineAmount").asString("");if(!lineAmount.isEmpty()&&amount.compareTo(new BigDecimal(lineAmount))!=0)warnings.add("数量×单价与原始行金额不符，须核对，原值不更改");
            }
            if(item.path("description").asString("").matches(".*[?？□].*"))warnings.add("项目名称可能模糊，须对照原图核对");
            if(quantity.isEmpty()||price.isEmpty())warnings.add("候选缺少数量或单价，采用后仍须人工补全才能提交");
        }
        if(grid)warnings.add("已按原图格线整理明细，请逐行核对数量、单价及缺漏项目");
        else if(raw!=null&&items.isEmpty())warnings.add("已识别文字，但未能定位有效报价明细，请核对原图或手工录入");
        warnings.add("程序整理的报价候选，须人工核对；合计不跨图累加，身份信息不修改当前案件");
        out.set("items",items);out.set("warnings",json.valueToTree(warnings));out.put("quoteRuleVersion",RULE_VERSION);return out;
    }
    static String number(String field,String original,Set<String> warnings){
        if(original.isBlank())return "";
        // Fold only full-width digits and dots. Other characters remain evidence, not numeric guesses.
        String value=original.chars().collect(StringBuilder::new,(out,c)->out.append((char)(c>='０'&&c<='９'?c-'０'+'0':c=='．'?'.':c)),StringBuilder::append).toString();
        boolean trailingDot=value.matches("[0-9]+\\.");
        if(trailingDot)value=value.substring(0,value.length()-1);
        boolean valid=field.equals("quantity")?value.matches("[0-9]{1,10}")&&Long.parseLong(value)>0&&Long.parseLong(value)<=Integer.MAX_VALUE
            :value.matches("[0-9]{1,16}(?:\\.[0-9]{1,2})?");
        if(!valid){warnings.add("数量或金额模糊或不符合格式，保持空白；l不转换为1");return "";}
        if(trailingDot)warnings.add("数字末尾点号已去除，请对照保留的原文核对");
        return new BigDecimal(value).toPlainString();
    }
    static boolean footer(String text){
        String compact=text.replaceAll("[\\s\\p{Z}]", "");
        return compact.matches("[拾佰仟万亿千百十元整圆]+[￥¥:：]*")
            ||compact.matches("^(?:维修金额|付款方式|客户签名|客户签字|经手人|维修人|修理人|修人|备注|金额大写|总计|合计|共计)(?:[：:￥¥0-9].*)?$");
    }
    private String unit(JsonNode lines,JsonNode item){
        var headers=new ArrayList<JsonNode>();for(var line:lines)if(line.path("text").asString("").strip().equals("单位")&&line.has("location"))headers.add(line);
        if(headers.size()!=1)return "";var header=headers.getFirst().path("location");var evidence=item.path("evidence");JsonNode name=evidence.isArray()?evidence.path(0):evidence;
        var box=name.path("location");if(!box.has("top"))return "";var values=new ArrayList<String>();
        for(var line:lines){var b=line.path("location");String text=line.path("text").asString("").strip();
            if(b.has("left")&&text.matches("[\\p{IsHan}A-Za-z/]{1,8}")&&Math.abs(b.path("left").asInt()-header.path("left").asInt())<=header.path("width").asInt()&&Math.abs((2*b.path("top").asInt()+b.path("height").asInt())-(2*box.path("top").asInt()+box.path("height").asInt()))<=Math.min(b.path("height").asInt(),box.path("height").asInt()))values.add(text);
        }return values.size()==1?values.getFirst():"";
    }
}
