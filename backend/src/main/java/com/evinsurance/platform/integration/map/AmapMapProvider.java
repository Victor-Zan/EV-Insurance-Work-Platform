package com.evinsurance.platform.integration.map;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.evinsurance.platform.foundation.web.TraceContext;
import okhttp3.*;
import tools.jackson.databind.*;

public final class AmapMapProvider implements MapProvider {
    private static final Logger LOG=LoggerFactory.getLogger(AmapMapProvider.class);
    private static final String OFFICIAL="https://restapi.amap.com";
    private static final int RESPONSE_LIMIT=1024*1024;
    private final String key,base;
    private final ObjectMapper json;
    private final OkHttpClient client;
    private final int budget,rate;
    private MapCallBudget ledger;
    public AmapMapProvider withLedger(MapCallBudget ledger){this.ledger=ledger;return this;}
    private final AtomicInteger requests=new AtomicInteger();
    private long windowStarted=System.nanoTime();
    private int windowRequests;

    public AmapMapProvider(String key,ObjectMapper json,int budget,int rate){
        this(key,json,budget,rate,OFFICIAL,new OkHttpClient.Builder().connectTimeout(3,TimeUnit.SECONDS)
            .readTimeout(8,TimeUnit.SECONDS).callTimeout(10,TimeUnit.SECONDS).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).build());
    }
    // Offline tests only; production never accepts endpoint URLs from configuration or requests.
    AmapMapProvider(String key,ObjectMapper json,int budget,int rate,String base,OkHttpClient client){
        if(key==null||key.isBlank())throw new IllegalStateException("AMAP_WEB_SERVICE_KEY is required for AMAP mode");
        if(budget<0||rate<1||rate>100)throw new IllegalArgumentException("Invalid Amap request limits");
        this.key=key;this.json=json;this.budget=budget;this.rate=rate;this.base=base;this.client=client;
    }
    public String providerId(){return "AMAP_WEB_V1";}
    public int requestCount(){return requests.get();}
    public List<Location> search(String q){throw MapFailure.of("STRUCTURED_ADDRESS_REQUIRED");}
    public Location geocode(String q){throw MapFailure.of("STRUCTURED_ADDRESS_REQUIRED");}
    public Location reverse(Point p){var c=locate(p);return new Location(c.address(),c.point(),false);}
    public Distance distance(Point a,Point b){throw MapFailure.of("UNSUPPORTED_OPERATION");}

    public List<AddressCandidate> resolve(Address a){
        GuangdongAddress.validate(a);
        return get("/v3/geocode/geo",Map.of("address","广东省"+a.city()+a.district()+a.detail().trim(),"city",a.cityAdcode()),root->{
        var nodes=root.path("geocodes");if(!nodes.isArray()||nodes.isEmpty())throw MapFailure.of("NO_RESULT");
        if(nodes.size()>20)throw MapFailure.of("BAD_RESPONSE");
        var result=new ArrayList<AddressCandidate>();
        for(var node:nodes){var c=candidate(node,parsePoint(text(node,"location")),text(node,"formatted_address"),text(node,"level"));
            result.add(new AddressCandidate(c.address(),c.point(),c.province(),c.city(),c.district(),c.adcode(),c.level(),GuangdongAddress.status(a,c),false));}
        return List.copyOf(result);
        });
    }
    public PlacePage places(Address a,int page){
        GuangdongAddress.validate(a);
        if(a.detail().length()>80||page<1||page>20)throw MapFailure.of("INVALID_REQUEST");
        return get("/v5/place/text",Map.of("keywords",a.detail().trim(),"region",a.districtAdcode(),"city_limit","true","page_size","10","page_num",Integer.toString(page)),root->{
            var nodes=root.path("pois");if(!nodes.isArray()||nodes.size()>10)throw MapFailure.of("BAD_RESPONSE");
            var result=new ArrayList<AddressCandidate>();
            for(var node:nodes){
                String name=text(node,"name"),street=text(node,"address");
                if(name.isBlank())throw MapFailure.of("BAD_RESPONSE");
                String province=text(node,"pname"),city=text(node,"cityname"),district=text(node,"adname");
                var raw=new AddressCandidate(province+city+district+street+" · "+name,parsePoint(text(node,"location")),province,city,district,text(node,"adcode"),"兴趣点","REQUIRES_CONFIRMATION",false);
                result.add(new AddressCandidate(raw.address(),raw.point(),province,city,district,raw.adcode(),"地点名称候选（请人工核对具体入口）",GuangdongAddress.status(a,raw),false));
            }
            return new PlacePage(page,10,nodes.size()==10&&page<20,List.copyOf(result));
        });
    }
    public AddressCandidate locate(Point p){
        GuangdongAddress.point(p);
        return get("/v3/geocode/regeo",Map.of("location",coordinate(p),"extensions","base","radius","100"),root->{
        var regeo=root.path("regeocode");var component=regeo.path("addressComponent");
        var c=candidate(component,p,text(regeo,"formatted_address"),"REVERSE");
        if(!GuangdongAddress.inProvince(c))throw MapFailure.of("REGION_MISMATCH");
        return c;
        });
    }
    public Workshops workshops(Point center,int page){
        GuangdongAddress.point(center);if(page<1||page>10)throw MapFailure.of("INVALID_REQUEST");
        return get("/v5/place/around",Map.of("location",coordinate(center),"keywords","电动自行车维修","radius","10000","page_size","20","page_num",Integer.toString(page)),root->{
            var nodes=root.path("pois");if(!nodes.isArray()||nodes.size()>20)throw MapFailure.of("BAD_RESPONSE");var rows=new ArrayList<Workshop>();
            for(var n:nodes){String name=text(n,"name"),id=text(n,"id");if(name.isBlank()||name.length()>200||id.isBlank()||id.length()>100)throw MapFailure.of("BAD_RESPONSE");
                String province=text(n,"pname"),city=text(n,"cityname"),district=text(n,"adname"),adcode=text(n,"adcode");
                if(!"广东省".equals(province)||!adcode.matches("44[0-9]{4}"))continue;
                rows.add(new Workshop(id,name,province+city+district+text(n,"address"),parsePoint(text(n,"location")),province,city,district,adcode,text(n,"type"),text(n,"typecode"),false));
            }return new Workshops(page,nodes.size()==20&&page<10,List.copyOf(rows));
        });
    }
    public DrivingRoute driving(Point from,Point to,int strategy){
        GuangdongAddress.point(from);GuangdongAddress.point(to);
        if(!Set.of(0,1,2,32,33,34,35,36,37,38,39,40,41,42,43,44,45).contains(strategy))throw MapFailure.of("UNSUPPORTED_OPERATION");
        return get("/v5/direction/driving",Map.of("origin",coordinate(from),"destination",coordinate(to),"strategy",Integer.toString(strategy),"show_fields","cost"),root->{
        var route=root.path("route");
        if(!samePoint(text(route,"origin"),from)||!samePoint(text(route,"destination"),to))throw MapFailure.of("BAD_RESPONSE");
        var paths=route.path("paths");if(!paths.isArray()||paths.isEmpty())throw MapFailure.of("NO_ROUTE");
        // The first alternative is the provider's primary strategy result; no time-based re-ranking.
        var path=paths.get(0);long metres=positiveLong(text(path,"distance"),false);
        String duration=text(path.path("cost"),"duration");
        return new DrivingRoute(metres,duration.isEmpty()?null:positiveLong(duration,false),strategy,0,false);
        });
    }
    private synchronized void reserve(String path,Map<String,String> params){
        long now=System.nanoTime();if(now-windowStarted>=TimeUnit.SECONDS.toNanos(1)){windowStarted=now;windowRequests=0;}
        if(windowRequests>=rate)throw MapFailure.of("RATE_LIMIT");
        if((ledger==null||!ledger.providerManaged())&&requests.get()>=budget)throw MapFailure.of("LOCAL_BUDGET");
        if(ledger!=null)ledger.reserve(path,params);
        windowRequests++;requests.incrementAndGet();
    }
    private <T> T get(String path,Map<String,String> params,Function<JsonNode,T> decode){
        long started=System.nanoTime();int httpStatus=0;String infoCode="-";boolean reserved=false;
        try {
        var url=Objects.requireNonNull(HttpUrl.parse(base+path)).newBuilder().addQueryParameter("key",key).addQueryParameter("output",path.startsWith("/v5/place/")?"json":"JSON");
        params.forEach(url::addQueryParameter);reserve(path,params);reserved=true;
        try(var response=client.newCall(new Request.Builder().url(url.build()).get().build()).execute()){
            httpStatus=response.code();
            if(response.code()==429)throw MapFailure.of("RATE_LIMIT");
            if(response.code()==401)throw MapFailure.of("CREDENTIALS");
            if(response.code()==403)throw MapFailure.of("PERMISSION");
            if(!response.isSuccessful()||response.body()==null)throw MapFailure.of("UPSTREAM");
            if(response.body().contentLength()>RESPONSE_LIMIT)throw MapFailure.of("BAD_RESPONSE");
            byte[] bytes;try(var stream=response.body().byteStream()){bytes=stream.readNBytes(RESPONSE_LIMIT+1);}
            if(bytes.length>RESPONSE_LIMIT)throw MapFailure.of("BAD_RESPONSE");
            JsonNode root=json.readTree(bytes);
            if(root==null||!root.isObject())throw MapFailure.of("BAD_RESPONSE");
            String code=text(root,"infocode");infoCode=code.matches("[0-9]{5,6}")?code:"-";
            if(!"1".equals(text(root,"status"))||!"10000".equals(code))throw MapFailure.of(error(code));
            return decode.apply(root);
        }
        }catch(MapFailure error){
            failure(path,error,started,httpStatus,infoCode);
            if(reserved&&ledger!=null){if(path.startsWith("/v5/place/"))ledger.stopPlaceFor(error.code());else ledger.stopFor(error.code());}throw error;
        }
        catch(java.io.InterruptedIOException error){throw failure(path,MapFailure.of("TIMEOUT"),started,httpStatus,infoCode);}
        catch(IOException error){throw failure(path,MapFailure.of("NETWORK"),started,httpStatus,infoCode);}
        catch(RuntimeException error){if(!reserved)throw error;throw failure(path,MapFailure.of("BAD_RESPONSE"),started,httpStatus,infoCode);}
    }
    private static MapFailure failure(String path,MapFailure error,long started,int httpStatus,String infoCode){
        String operation=switch(path){case "/v3/geocode/geo"->"GEOCODE";case "/v3/geocode/regeo"->"REVERSE";case "/v5/direction/driving"->"DRIVING";case "/v5/place/text","/v5/place/around"->"POI_SEARCH";default->"UNKNOWN";};
        String trace=TraceContext.currentTraceId();
        if(trace==null||!trace.matches("[A-Za-z0-9._-]{1,64}"))trace="-";
        // Deliberately no exception/cause, URL, parameters, address, coordinates or response body.
        LOG.warn("Amap request failed: operation={} code={} httpStatus={} infocode={} elapsedMs={} traceId={}",
            operation,error.code(),httpStatus,infoCode,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),trace);
        return error;
    }
    private static String error(String code){return switch(code){
        case "10001","10013" -> "CREDENTIALS";
        case "10002","10005","10006","10007","10008","10009","10011","10012","10026","10041","40002" -> "PERMISSION";
        case "10003","10044","10045","40000","40003" -> "QUOTA";
        case "10004","10010","10014","10015","10019","10020","10021","10029" -> "RATE_LIMIT";
        case "20801","20802","20803" -> "NO_ROUTE";
        case "20011","20800" -> "REGION_MISMATCH";
        case "20000","20001","20002","20012" -> "INVALID_REQUEST";
        default -> "UPSTREAM";
    };}
    private static AddressCandidate candidate(JsonNode n,Point p,String address,String level){return new AddressCandidate(address,p,text(n,"province"),text(n,"city"),text(n,"district"),text(n,"adcode"),level,"REQUIRES_CONFIRMATION",false);}
    private static String text(JsonNode n,String key){var v=n.path(key);return v.isString()||v.isNumber()?v.asString():"";}
    static String coordinate(Point p){return decimal(p.longitude())+","+decimal(p.latitude());}
    private static String decimal(double n){return BigDecimal.valueOf(n).setScale(6,RoundingMode.HALF_UP).toPlainString();}
    private static Point parsePoint(String value){
        try{var parts=value.split(",",-1);if(parts.length!=2)throw MapFailure.of("BAD_RESPONSE");var p=new Point(Double.parseDouble(parts[1]),Double.parseDouble(parts[0]));GuangdongAddress.point(p);return p;}
        catch(RuntimeException error){throw MapFailure.of("BAD_RESPONSE");}
    }
    private static boolean samePoint(String text,Point expected){var actual=parsePoint(text);return Math.abs(actual.latitude()-expected.latitude())<=0.000002&&Math.abs(actual.longitude()-expected.longitude())<=0.000002;}
    private static long positiveLong(String value,boolean strictlyPositive){try{long n=Long.parseLong(value);if(n<0||(strictlyPositive&&n==0))throw new NumberFormatException();return n;}catch(NumberFormatException error){throw MapFailure.of("BAD_RESPONSE");}}
}
