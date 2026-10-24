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

    record Address(String city,String cityAdcode,String district,String districtAdcode,String detail) {}
    record AddressCandidate(String address,Point point,String province,String city,String district,
                            String adcode,String level,String status,boolean mock) {}
    record DrivingRoute(long metres,Long seconds,int strategy,int alternative,boolean mock) {}
    record PlacePage(int page,int size,boolean hasMore,List<AddressCandidate> records) {}
    record Workshop(String id,String name,String address,Point point,String province,String city,String district,String adcode,String type,String typecode,boolean mock){}
    record Workshops(int page,boolean hasMore,List<Workshop> records){}
    default String providerId(){return "MOCK_V1";}
    default List<AddressCandidate> resolve(Address address){throw MapFailure.of("STRUCTURED_ADDRESS_REQUIRED");}
    default PlacePage places(Address address,int page){throw MapFailure.of("UNSUPPORTED_OPERATION");}
    default Workshops workshops(Point center,int page){throw MapFailure.of("UNSUPPORTED_OPERATION");}
    default AddressCandidate locate(Point point){throw MapFailure.of("UNSUPPORTED_OPERATION");}
    default DrivingRoute driving(Point from,Point to,int strategy){throw MapFailure.of("UNSUPPORTED_OPERATION");}
}
