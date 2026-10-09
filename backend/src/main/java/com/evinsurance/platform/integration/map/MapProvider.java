package com.evinsurance.platform.integration.map;
import java.util.List;
public interface MapProvider {
    record Point(double latitude,double longitude){}
    record Location(String label,Point point,boolean mock){}
    record Distance(double metres,String method,boolean mock){}
    List<Location> search(String query);
    Location geocode(String address);
    Location reverse(Point point);
    Distance distance(Point from,Point to);
}
