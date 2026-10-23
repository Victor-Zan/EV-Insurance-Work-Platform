package com.evinsurance.platform.integration.map;

/** Conservative text/category evidence, never a verified business capability. */
public final class EbikeWorkshopClassification {
    private EbikeWorkshopClassification(){}
    public record Assessment(boolean included,String label,String reason){}
    public static Assessment assess(String name,String type){
        String n=(name==null?"":name).replaceAll("\\s+",""),t=type==null?"":type,all=n+";"+t;
        boolean explicitEbike=all.matches(".*(电动自行车|电单车|两轮电动|电动单车).*"),repair=all.matches(".*(维修|修理|修车|修补|检修).*"),electric=explicitEbike||all.contains("电动车");
        boolean car=all.matches(".*(汽车|汽修|轿车|货车|新能源车|新能源汽车|4S店).*"),motor=all.contains("摩托"),irrelevant=all.matches(".*(充电站|充电桩|换电柜|外卖柜|停车场|租赁|租车).*"),sales=all.matches(".*(销售|专卖|旗舰店|经销).*" );
        if(car&&!explicitEbike)return new Assessment(false,"排除：汽车维修","无两轮电动自行车维修依据");
        if(irrelevant&&!repair)return new Assessment(false,"排除：无关地点","未见维修服务依据");
        if(sales&&!repair)return new Assessment(false,"排除：纯销售","销售不等于维修");
        if(explicitEbike&&repair)return new Assessment(true,"两轮电动自行车维修线索","仅地图名称/分类线索，仍须客服核实实际能力");
        if((electric||motor||all.contains("自行车"))&&repair)return new Assessment(true,"维修类型待核实","不能据此认定可维修两轮电动自行车");
        return new Assessment(false,"排除：依据不足","未见两轮车维修服务线索；可人工补充经核实门店");
    }
}
