package com.evinsurance.platform.integration.map;

import com.evinsurance.platform.foundation.api.ApiException;
import java.util.Set;
import java.util.regex.Pattern;

public final class GuangdongAddress {
    private GuangdongAddress(){}
    private static final Set<String> PRECISE=Set.of("门牌号","门址","兴趣点","单元号","房间","楼层","公交地铁站点","住宅区","道路交叉路口");
    private static final Pattern FOREIGN=Pattern.compile("北京市|天津市|上海市|重庆市|河北省|山西省|辽宁省|吉林省|黑龙江省|江苏省|浙江省|安徽省|福建省|江西省|山东省|河南省|湖北省|湖南省|海南省|四川省|贵州省|云南省|陕西省|甘肃省|青海省|台湾省|内蒙古|广西|西藏|宁夏|新疆|香港|澳门");
    public static void validate(MapProvider.Address a){
        if(a==null||a.city()==null||a.district()==null||a.detail()==null||a.city().isBlank()||a.district().isBlank()
            ||a.detail().trim().length()<4||a.detail().length()>200||a.city().length()>100||a.district().length()>100
            ||a.cityAdcode()==null||!a.cityAdcode().matches("44[0-9]{2}00")
            ||a.districtAdcode()==null||!a.districtAdcode().matches("44[0-9]{4}")
            ||!a.districtAdcode().startsWith(a.cityAdcode().substring(0,4)))throw ApiException.invalid("请选择广东城市、区县并填写明确详细位置");
        // This Shenzhen institution's proper name is not an administrative address.
        // Preserve the exact submitted text for the provider and still reject any
        // other foreign-region marker, including one alongside this proper name.
        String administrative=a.detail().replace("香港中文大学（深圳）","").replace("香港中文大学(深圳)","");
        if(FOREIGN.matcher(administrative).find())throw ApiException.invalid("详细地址包含广东省外行政区域，请核对车辆位置");
    }
    public static String status(MapProvider.Address input,MapProvider.AddressCandidate c){
        if(!inProvince(c)||!input.city().equals(c.city())||!input.district().equals(c.district())||!input.districtAdcode().equals(c.adcode()))return "REGION_MISMATCH";
        return c.address()!=null&&!c.address().isBlank()&&PRECISE.contains(c.level())?"REQUIRES_CONFIRMATION":"NEEDS_DETAIL";
    }
    public static boolean inProvince(MapProvider.AddressCandidate c){return "广东省".equals(c.province())&&c.adcode()!=null&&c.adcode().matches("44[0-9]{4}");}
    public static void point(MapProvider.Point p){
        if(p==null||!Double.isFinite(p.longitude())||!Double.isFinite(p.latitude())||p.longitude()< -180||p.longitude()>180||p.latitude()< -90||p.latitude()>90)throw ApiException.invalid("Invalid coordinates");
    }
}
