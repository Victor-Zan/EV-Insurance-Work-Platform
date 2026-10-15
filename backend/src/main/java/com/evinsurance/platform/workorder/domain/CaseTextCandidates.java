package com.evinsurance.platform.workorder.domain;

import java.util.*;
import java.util.regex.*;

/** Explicit plate labels, including the user-confirmed insured-party label; contacts remain party scoped. */
public final class CaseTextCandidates {
    private static final Map<String,String> LABELS=Map.ofEntries(
        Map.entry("案件号","claimNo"),Map.entry("报案号","claimNo"),Map.entry("保险报案号","claimNo"),
        Map.entry("车主手机号","ownerPhone"),Map.entry("车主手机","ownerPhone"),Map.entry("车主电话","ownerPhone"),Map.entry("物主方电话","ownerPhone"),Map.entry("三者电话","ownerPhone"),
        Map.entry("标的车牌","vehiclePlate"),Map.entry("标的车牌号","vehiclePlate"),Map.entry("车牌","vehiclePlate"),Map.entry("车辆牌照","vehiclePlate"),Map.entry("车牌号","vehiclePlate"),Map.entry("三者车牌","vehiclePlate"),Map.entry("电动车车牌","vehiclePlate"),
        Map.entry("车架号","vehicleVin"),Map.entry("VIN","vehicleVin"),Map.entry("三者车架号","vehicleVin"),
        Map.entry("查勘员姓名","surveyorName"),Map.entry("查勘员电话","surveyorPhone"),Map.entry("查勘员手机号","surveyorPhone"),
        Map.entry("车辆颜色","vehicleColor"),Map.entry("颜色","vehicleColor"),Map.entry("车辆品牌","vehicleBrand"),Map.entry("品牌","vehicleBrand"),Map.entry("车型","vehicleModel"),Map.entry("车辆型号","vehicleModel"),
        Map.entry("地址","accidentAddress"),Map.entry("事故地址","accidentAddress"),Map.entry("接车地址","accidentAddress"),
        Map.entry("事故描述","accidentDescription"),Map.entry("事故说明","accidentDescription"),Map.entry("保险公司","insuranceCompany"),Map.entry("车主姓名","ownerName"));
    private static final Pattern LABEL=Pattern.compile("(?<![\\p{L}\\p{N}])("+LABELS.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).map(Pattern::quote).collect(java.util.stream.Collectors.joining("|"))+")(?:[：:]\\s*|\\s+|(?=[0-9京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵青藏琼宁川粤]))");
    private static final Pattern SURVEYOR=Pattern.compile("^查勘员[：:]?\\s*([\\p{IsHan}]{2,10})\\s*[：:]\\s*(1[3-9][0-9]{9})$");
    public Map<String,Object> extract(String text){
        var evidence=new LinkedHashMap<String,List<Map<String,Object>>>();var lines=new ArrayList<Map<String,Object>>();var warnings=new LinkedHashSet<String>();
        int index=0;
        for(String original:text.lines().toList()){
            String line=original.strip();var source=new LinkedHashMap<String,Object>();source.put("line",index++);source.put("text",original);lines.add(source);
            if(line.matches("[0-9]{16,40}")){add(evidence,"claimNo",line,source,false);continue;}
            if(line.startsWith("·")||line.startsWith("•")){
                var nickname=Pattern.compile("查勘(?:员)?([\\p{IsHan}]{2,6})$").matcher(line);
                if(nickname.find())add(evidence,"surveyorName",nickname.group(1),source,false);
                continue;
            }
            if((line.startsWith("标的")&&!line.startsWith("标的车牌"))||line.startsWith("司机"))continue;
            var surveyor=SURVEYOR.matcher(line);
            if(!line.startsWith("查勘员姓名")&&!line.startsWith("查勘员电话")&&!line.startsWith("查勘员手机号")&&surveyor.matches()){add(evidence,"surveyorName",surveyor.group(1),source,false);add(evidence,"surveyorPhone",surveyor.group(2),source,false);continue;}
            var matcher=LABEL.matcher(line);var matches=new ArrayList<int[]>();var names=new ArrayList<String>();
            while(matcher.find()){matches.add(new int[]{matcher.start(),matcher.end()});names.add(matcher.group(1));}
            // Do not harvest a label embedded in an unrecognised party description.
            if(matches.isEmpty()||matches.getFirst()[0]!=0)continue;
            for(int n=0;n<matches.size();n++){
                String value=line.substring(matches.get(n)[1],n+1<matches.size()?matches.get(n+1)[0]:line.length()).strip().replaceAll("[；;，,]+$","").strip();
                if(!value.isBlank())add(evidence,LABELS.get(names.get(n)),value,source,false);
            }
        }
        var fields=new LinkedHashMap<String,String>();
        for(var entry:evidence.entrySet()){
            var values=entry.getValue().stream().map(v->v.get("value").toString()).distinct().toList();
            if(entry.getKey().equals("surveyorName")){
                String name=entry.getValue().stream().filter(v->!v.get("text").toString().strip().matches("^[·•].*")).map(v->v.get("value").toString()).findFirst().orElse(values.getFirst());
                fields.put("surveyorName",name);continue;
            }
            if(values.size()!=1){warnings.add(entry.getKey()+" 有多个候选，须逐项确认");continue;}
            if(entry.getValue().stream().allMatch(v->Boolean.TRUE.equals(v.get("requiresConfirmation"))))continue;
            String value=values.getFirst();
            if(entry.getKey().equals("vehiclePlate")){var plate=VehiclePlate.check(value);if(!plate.acceptable()){warnings.add(plate.message());continue;}value=plate.value();if(plate.review())warnings.add(plate.message());}
            if(Set.of("ownerPhone","surveyorPhone").contains(entry.getKey())&&!value.matches("1[3-9][0-9]{9}")){warnings.add(entry.getKey()+" 电话格式不完整，待人工核对");continue;}
            fields.put(entry.getKey(),value);
        }
        warnings.add("标的车牌按已确认规则提取；标的/司机电话不作为车主电话，缺失信息不猜填");
        var result=new LinkedHashMap<String,Object>();result.put("origin","CASE_TEXT_PARTY_LABELS_V2");result.put("candidateOnly",true);result.put("fields",fields);result.put("evidence",evidence);result.put("lines",lines);result.put("warnings",warnings);return result;
    }
    private void add(Map<String,List<Map<String,Object>>> evidence,String field,String value,Map<String,Object> source,boolean confirm){var row=new LinkedHashMap<String,Object>(source);row.put("value",value);if(confirm)row.put("requiresConfirmation",true);evidence.computeIfAbsent(field,k->new ArrayList<>()).add(row);}
}
