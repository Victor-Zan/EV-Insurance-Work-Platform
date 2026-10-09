package com.evinsurance.platform.integration.map;
import java.util.List;
import org.springframework.stereotype.Component;
@Component
public class MockMapProvider implements MapProvider {
    public List<Location> search(String q){return List.of(geocode(q));}
    public Location geocode(String address){long h=Integer.toUnsignedLong(address.hashCode());return new Location("模拟地址："+address,new Point(22.5+(h%1000)/10000d,113.9+(h/1000%1000)/10000d),true);}
    public Location reverse(Point p){return new Location("模拟坐标点（不是实际地址）",p,true);}
    public Distance distance(Point a,Point b){double lat=Math.toRadians(b.latitude()-a.latitude()),lon=Math.toRadians(b.longitude()-a.longitude());double h=Math.pow(Math.sin(lat/2),2)+Math.cos(Math.toRadians(a.latitude()))*Math.cos(Math.toRadians(b.latitude()))*Math.pow(Math.sin(lon/2),2);return new Distance(6371000*2*Math.atan2(Math.sqrt(h),Math.sqrt(Math.max(0,1-h))),"MOCK_STRAIGHT_LINE_NOT_ROUTE",true);}
}
