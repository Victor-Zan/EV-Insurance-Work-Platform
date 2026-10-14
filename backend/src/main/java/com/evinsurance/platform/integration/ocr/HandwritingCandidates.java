package com.evinsurance.platform.integration.ocr;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;
import tools.jackson.databind.JsonNode;

/** Deterministic labelled text extraction. Coordinates are evidence, never a licence to guess a cell. */
public final class HandwritingCandidates {
    private static final Map<String,String> LABELS=Map.ofEntries(
        Map.entry("客户姓名","ownerName"),Map.entry("客户名称","ownerName"),Map.entry("车主姓名","ownerName"),Map.entry("客户手机号","ownerPhone"),Map.entry("车主手机号","ownerPhone"),
        Map.entry("保险公司","insuranceCompany"),Map.entry("保险报案号","claimNo"),Map.entry("报案号","claimNo"),Map.entry("报案时间","claimReportedAt"),
        Map.entry("保单号","policyNo"),Map.entry("车辆品牌","vehicleBrand"),Map.entry("品牌","vehicleBrand"),Map.entry("车型","vehicleModel"),
        Map.entry("车架号","vehicleVin"),Map.entry("VIN","vehicleVin"),Map.entry("车牌号","vehiclePlate"),Map.entry("车辆其他标识","vehicleOtherIdentifier"),
        Map.entry("事故时间","accidentAt"),Map.entry("事故区域","accidentRegionText"),Map.entry("事故地址","accidentAddress"),Map.entry("事故描述","accidentDescription"),
        Map.entry("维修单号","documentNo"),Map.entry("清单编号","documentNo"),Map.entry("单据编号","documentNo"),Map.entry("单据日期","documentDate"),Map.entry("维修日期","documentDate"),
        Map.entry("总金额","amountCandidate"),Map.entry("合计","amountCandidate"));
    private static final Pattern LABEL=Pattern.compile("("+LABELS.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).map(Pattern::quote).collect(java.util.stream.Collectors.joining("|"))+")[：:]\\s*");
    private static final Pattern ITEM=Pattern.compile("配件名称[：:]\\s*(.+?)\\s+数量[：:]\\s*(\\S*)\\s+单价[：:]\\s*(\\S+)\\s+金额[：:]\\s*(\\S+)\\s*$");
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm[:ss]").withResolverStyle(ResolverStyle.STRICT);
    public Map<String,Object> extract(JsonNode response,String category){
        var fields=new LinkedHashMap<String,String>();var evidence=new LinkedHashMap<String,List<Map<String,Object>>>();
        var warnings=new LinkedHashSet<String>();var lines=new ArrayList<Map<String,Object>>();var items=new ArrayList<Map<String,Object>>();
        int index=0;
        for(JsonNode line:response.path("words_result")){
            if(index>=1000)throw new OcrFailure("BAIDU_RESULT_TOO_LARGE");
            String words=line.path("words").asString("");var source=new LinkedHashMap<String,Object>();source.put("line",index++);source.put("text",words);
            if(line.has("location"))source.put("location",line.get("location"));
            if(line.has("probability"))source.put("probability",line.get("probability"));
            lines.add(source);
            var total=Pattern.compile("^(?:共计|合计|总计|总金额)[：:\\s￥¥]*([0-9]{1,16}(?:\\.[0-9]{1,2})?)(?:元)?$").matcher(words.strip());
            if(total.matches()){var entry=new LinkedHashMap<String,Object>(source);entry.put("value",total.group(1));evidence.computeIfAbsent("amountCandidate",k->new ArrayList<>()).add(entry);continue;}
            Matcher item=ITEM.matcher(words);
            if(item.matches()){
                var row=new LinkedHashMap<String,Object>();row.put("description",item.group(1));String quantity=item.group(2);
                row.put("quantity",quantity.matches("[0-9]+(?:\\.[0-9]+)?")?quantity:"");row.put("quantityOriginal",quantity);
                row.put("unitPrice",money(item.group(3),warnings));row.put("lineAmount",money(item.group(4),warnings));row.put("evidence",source);
                row.put("origin","PROGRAM_LABELLED_ROW");
                if(quantity.isBlank()||!quantity.matches("[0-9]+(?:\\.[0-9]+)?"))warnings.add("明细数量缺失或模糊，保持空值；字母不转换为数字");
                if(!row.get("quantity").equals("")&&!row.get("unitPrice").equals("")&&!row.get("lineAmount").equals("")){
                    BigDecimal calculated=new BigDecimal(quantity).multiply(new BigDecimal(row.get("unitPrice").toString()));row.put("calculatedAmount",calculated.toPlainString());
                    if(calculated.compareTo(new BigDecimal(row.get("lineAmount").toString()))!=0)warnings.add("明细数量乘单价与单据金额不符，须核对");
                }
                items.add(row);continue;
            }
            Matcher matcher=LABEL.matcher(words);var matches=new ArrayList<int[]>();var names=new ArrayList<String>();
            while(matcher.find()){matches.add(new int[]{matcher.start(),matcher.end()});names.add(matcher.group(1));}
            for(int n=0;n<matches.size();n++){
                String value=words.substring(matches.get(n)[1],n+1<matches.size()?matches.get(n+1)[0]:words.length()).strip().replaceAll("[；;]+$","").strip();
                String field=LABELS.get(names.get(n));if(value.isBlank()){warnings.add(field+" 缺失，待核对");continue;}
                var entry=new LinkedHashMap<String,Object>(source);entry.put("value",value);evidence.computeIfAbsent(field,k->new ArrayList<>()).add(entry);
            }
            if(matches.isEmpty()&&!words.isBlank())warnings.add("部分文字没有可靠字段或行列映射，保留原文待人工整理");
        }
        // Baidu can return a printed label and its handwritten value as separate word boxes.
        // Accept only one close, horizontally aligned value. Multiple boxes are an explicit ambiguity.
        for(var labelLine:lines){
            String labelText=labelLine.get("text").toString().strip().replaceAll("[：:]$","");String field=LABELS.get(labelText);
            if(field==null||!(labelLine.get("location") instanceof JsonNode location))continue;
            int left=location.path("left").asInt(),top=location.path("top").asInt(),width=location.path("width").asInt(),height=location.path("height").asInt();if(width<=0||height<=0)continue;
            var neighbours=new ArrayList<Map<String,Object>>();
            for(var valueLine:lines){
                String text=valueLine.get("text").toString().strip();if(text.isBlank()||LABELS.containsKey(text.replaceAll("[：:]$",""))||LABEL.matcher(text).find()||!(valueLine.get("location") instanceof JsonNode valueBox))continue;
                int x=valueBox.path("left").asInt(),y=valueBox.path("top").asInt(),h=valueBox.path("height").asInt();int overlap=Math.min(top+height,y+h)-Math.max(top,y);
                if(h>0&&x>=left+width-2&&x-left-width<=8*height&&overlap>=0.6*Math.min(height,h))neighbours.add(valueLine);
            }
            if(neighbours.size()==1){var entry=new LinkedHashMap<String,Object>(neighbours.getFirst());entry.put("value",neighbours.getFirst().get("text").toString().strip());entry.put("labelEvidence",labelLine);entry.put("origin","PROGRAM_COORDINATE_LABEL");evidence.computeIfAbsent(field,k->new ArrayList<>()).add(entry);}
            else warnings.add(field+" 的标签与文字框关系不明确，未强行配对");
        }
        for(var e:evidence.entrySet()){
            var values=e.getValue().stream().map(v->v.get("value").toString()).distinct().toList();
            if(values.size()!=1){warnings.add(e.getKey()+" 存在多个不同值，未自动选取");continue;}
            String value=values.getFirst();
            if(e.getKey().equals("ownerPhone")&&!value.matches("1[3-9][0-9]{9}")){warnings.add("手机号不完整或不明确，未预填");continue;}
            if(Set.of("accidentAt","claimReportedAt").contains(e.getKey())){
                try{value=LocalDateTime.parse(value,DATE).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toString();}
                catch(DateTimeException error){warnings.add(e.getKey()+" 日期或时间不完整，未预填");continue;}
            }
            if(value.length()>2000){warnings.add(e.getKey()+" 过长，未预填");continue;}
            fields.put(e.getKey(),value);
        }
        if(items.isEmpty())items.addAll(CoordinateRepairTable.extract(lines,warnings));
        if(!items.isEmpty()&&fields.containsKey("amountCandidate")){
            String total=money(fields.get("amountCandidate"),warnings);
            if(!total.isEmpty()&&items.stream().allMatch(i->!i.get("lineAmount").equals(""))){
                BigDecimal sum=items.stream().map(i->new BigDecimal(i.get("lineAmount").toString())).reduce(BigDecimal.ZERO,BigDecimal::add);
                if(sum.compareTo(new BigDecimal(total))!=0)warnings.add("明细金额合计与单据总金额不符，须核对");
            }
        }
        warnings.add("候选结果，须人工核对；未识别字段不自动补齐");
        var result=new LinkedHashMap<String,Object>();result.put("mock",false);result.put("candidateOnly",true);result.put("sourceCategory",category);
        result.put("origin","PROGRAM_LABELLED_TEXT_V2");result.put("fields",fields);result.put("evidence",evidence);result.put("lines",lines);result.put("items",items);result.put("warnings",warnings);
        return result;
    }
    private static String money(String text,Set<String> warnings){
        if(text.matches("[0-9]{1,16}(?:\\.[0-9]{1,2})?"))return new BigDecimal(text).toPlainString();
        warnings.add("金额格式不明确，不自动纠正");return "";
    }
}
