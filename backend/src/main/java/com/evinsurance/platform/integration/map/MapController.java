package com.evinsurance.platform.integration.map;
import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.workorder.application.WorkOrderService;
import com.evinsurance.platform.workorder.infrastructure.WorkOrderRows;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/maps") @SecurityRequirement(name="bearerAuth")
public class MapController {
    private final MapProvider provider;private final WorkOrderService orders;
    public MapController(MapProvider provider,WorkOrderService orders){this.provider=provider;this.orders=orders;}
    private void access(){if(!CurrentUser.require().roles().contains(Role.CUSTOMER_SERVICE))throw ApiException.denied();}
    private String address(String text){if(text==null||text.isBlank()||text.length()>255)throw ApiException.invalid("Address must be 1..255 characters");return text.trim();}
    private MapProvider.Point point(double lat,double lon){if(!Double.isFinite(lat)||!Double.isFinite(lon)||lat< -90||lat>90||lon< -180||lon>180)throw ApiException.invalid("Invalid coordinates");return new MapProvider.Point(lat,lon);}
    @GetMapping("/search") public ApiResponse<List<MapProvider.Location>> search(@RequestParam String query){access();return ApiResponse.success(provider.search(address(query)));}
    @GetMapping("/geocode") public ApiResponse<MapProvider.Location> geocode(@RequestParam String address){access();return ApiResponse.success(provider.geocode(address(address)));}
    @GetMapping("/reverse") public ApiResponse<MapProvider.Location> reverse(@RequestParam double latitude,@RequestParam double longitude){access();return ApiResponse.success(provider.reverse(point(latitude,longitude)));}
    @GetMapping("/distance") public ApiResponse<MapProvider.Distance> distance(@RequestParam double fromLat,@RequestParam double fromLon,@RequestParam double toLat,@RequestParam double toLon){access();return ApiResponse.success(provider.distance(point(fromLat,fromLon),point(toLat,toLon)));}
    public record Shop(long id,String name,MapProvider.Location mockLocation){}
    @GetMapping("/cases/{id}/shops") public ApiResponse<PageResponse<Shop>> shops(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){access();var rows=orders.eligibleShops(id,page,size);return ApiResponse.success(new PageResponse<>(page,size,rows.total(),rows.records().stream().map(s->new Shop(s.getId(),s.getName(),provider.geocode(s.getAddress()==null?s.getName():s.getAddress()))).toList()));}
}
