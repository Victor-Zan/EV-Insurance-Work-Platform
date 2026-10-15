package com.evinsurance.platform.workorder.domain;

import java.text.Normalizer;
import java.util.Locale;

/** Structure checks only. Local bicycle registrations cannot be verified by one national regex. */
public final class VehiclePlate {
    private static final String PROVINCES="京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵青藏琼宁川粤";
    public record Check(String value,boolean acceptable,boolean review,String message) {}
    private VehiclePlate() {}
    public static Check check(String input){
        String value=input==null?"":Normalizer.normalize(input,Normalizer.Form.NFKC).toUpperCase(Locale.ROOT).replaceAll("[\\s·•.\\-－—]","");
        if(value.matches("["+PROVINCES+"][A-HJ-NP-Z][A-HJ-NP-Z0-9]{4}[A-HJ-NP-Z0-9学挂警港澳]"))return new Check(value,true,false,"符合常见大陆机动车号牌结构");
        if(value.matches("["+PROVINCES+"][A-HJ-NP-Z](?:[DF][A-HJ-NP-Z0-9][0-9]{4}|[0-9]{5}[DF])"))return new Check(value,true,false,"符合常见新能源号牌结构");
        if(value.length()>=4&&value.length()<=20&&value.matches("[\\p{IsHan}A-Z0-9]+")&&value.matches(".*[0-9].*")&&
            (value.matches("[0-9]{6,12}")||value.matches("[\\p{IsHan}]{1,6}[A-Z0-9]{4,12}")||value.matches("[A-Z]{1,3}[0-9]{4,10}")))
            return new Check(value,true,true,"地方电动自行车或特殊号牌，请按原件人工核对");
        return new Check(value,false,true,"号牌格式不完整或含无效字符，请核对原件，或填写车架号");
    }
}
