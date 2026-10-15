package com.evinsurance.platform.integration.ocr;

import java.util.*;
import java.util.regex.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;

/** Adds quote-only evidence to the shared extractor. Never infers quantity, price or a case identity. */
public final class ShopQuoteCandidates {
    private static final Pattern ROW=Pattern.compile("^(?:配件名称|维修项目|项目名称)[：:]\\s*(.*?)(?=\\s+(?:数量|单位|单价|金额)[：:]|$)");
    private static final Pattern CELL=Pattern.compile("(数量|单位|单价|金额)[：:]\\s*(.*?)(?=\\s+(?:数量|单位|单价|金额)[：:]|$)");
    private static final Pattern FEE=Pattern.compile("^(工时费|人工费|拖车费|其他费用)[：:]\\s*([0-9]{1,16}(?:\\.[0-9]{1,2})?)(?:元)?$");
    public ObjectNode organize(ObjectNode base,ObjectMapper json){
        ObjectNode out=base.deepCopy();ArrayNode items=json.createArrayNode(),warnings=json.createArrayNode();
        for(var warning:base.path("warnings"))warnings.add(warning);
        int labelledRows=0;
        for(var source:base.path("lines")){
            String text=source.path("text").asString("").strip();var row=ROW.matcher(text);var fee=FEE.matcher(text);
            if(row.find()){
                labelledRows++;
                var item=items.addObject();item.put("description",row.group(1).strip()).put("quantity","").put("unit","").put("unitPrice","").put("lineAmount","");
                var cell=CELL.matcher(text);var seen=new HashSet<String>();
                while(cell.find()){
                    String name=cell.group(1),value=cell.group(2).strip(),field=switch(name){case "数量"->"quantity";case "单位"->"unit";case "单价"->"unitPrice";default->"lineAmount";};
                    if(!seen.add(field)){item.put(field,"");warnings.add("同一行字段重复，须核对，未选择其中一个值");continue;}
                    if(field.equals("unit"))item.put(field,value);
                    else if(value.matches("[0-9]{1,16}(?:\\.[0-9]{1,2})?"))item.put(field,value);
                    else {item.put(field,"");warnings.add("数量或金额缺失/模糊，保持空白；l不转换为1");}
                }
                item.set("evidence",source.deepCopy());item.put("origin","PROGRAM_LABELLED_QUOTE_ROW");
            }else if(fee.matches()){
                var item=items.addObject();item.put("description",fee.group(1)).put("quantity","").put("unit","").put("unitPrice","").put("lineAmount",fee.group(2)).put("feeCandidate",true);
                item.set("evidence",source.deepCopy());item.put("origin","PROGRAM_EXPLICIT_FEE");warnings.add("费用仅作为一条项目候选，数量/单价须人工补充；勿与已有费用重复计入");
            }
        }
        // Shared coordinate-table output is used when no complete labelled rows were available.
        if(labelledRows==0)for(var candidate:base.path("items")){var item=(ObjectNode)candidate.deepCopy();boolean duplicate=false;for(var fee:items)if(fee.path("description").asString().equals(item.path("description").asString()))duplicate=true;if(duplicate){warnings.add("费用同时出现在表格及文字摘要，保留单一待核对候选，勿重复计费");continue;}if(!item.has("unit"))item.put("unit",unit(base.path("lines"),item));items.add(item);}
        if(items.size()>200)throw new OcrFailure("BAIDU_RESULT_TOO_LARGE");
        for(var node:items){var item=(ObjectNode)node;String quantity=item.path("quantity").asString(""),price=item.path("unitPrice").asString("");
            if(!quantity.isEmpty()&&!price.isEmpty()){
                var amount=new java.math.BigDecimal(quantity).multiply(new java.math.BigDecimal(price));item.put("calculatedAmount",amount.toPlainString());
                String original=item.path("lineAmount").asString("");if(!original.isEmpty()&&amount.compareTo(new java.math.BigDecimal(original))!=0)warnings.add("数量×单价与原始行金额不符，须核对，原值不更改");
            }
            if(item.path("description").asString("").matches(".*[?？□].*"))warnings.add("项目名称可能模糊，须对照原图核对");
            if(quantity.isEmpty()||price.isEmpty())warnings.add("候选缺少数量或单价，采用后仍须人工补全才能提交");
        }
        warnings.add("程序整理的报价候选，须人工核对；合计不跨图累加，身份信息不修改当前案件");
        out.set("items",items);out.set("warnings",warnings);out.put("quoteRuleVersion","SHOP_QUOTE_LABELS_1");return out;
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
