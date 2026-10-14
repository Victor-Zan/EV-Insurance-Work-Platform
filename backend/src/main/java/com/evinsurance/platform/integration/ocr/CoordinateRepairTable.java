package com.evinsurance.platform.integration.ocr;

import java.math.BigDecimal;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Conservative program candidates, never a vendor-native table. Ambiguous cells stay unassigned. */
final class CoordinateRepairTable {
    private record Box(Map<String,Object> source,String text,int x,int y,int width,int height){
        int centerX(){return x+width/2;}int centerY(){return y+height/2;}int bottom(){return y+height;}
    }
    private CoordinateRepairTable(){}
    static List<Map<String,Object>> extract(List<Map<String,Object>> lines,Set<String> warnings){
        var boxes=new ArrayList<Box>();
        for(var line:lines)if(line.get("location") instanceof JsonNode b&&b.path("width").asInt()>0&&b.path("height").asInt()>0)
            boxes.add(new Box(line,line.get("text").toString().strip(),b.path("left").asInt(),b.path("top").asInt(),b.path("width").asInt(),b.path("height").asInt()));
        var headers=new ArrayList<Box>();
        for(String label:List.of("数量","单价","金额")){var found=boxes.stream().filter(b->b.text().equals(label)).toList();if(found.size()!=1)return List.of();headers.add(found.getFirst());}
        int height=headers.stream().mapToInt(Box::height).max().orElse(1),rowY=headers.stream().mapToInt(Box::centerY).sum()/3;
        if(headers.stream().anyMatch(b->Math.abs(b.centerY()-rowY)>height))return List.of();
        var names=boxes.stream().filter(b->(b.text().endsWith("名称")||Set.of("维修项目","品名").contains(b.text()))&&b.centerX()<headers.getFirst().centerX()&&Math.abs(b.centerY()-rowY)<height*1.5).toList();
        if(names.size()!=1){warnings.add("维修表格名称表头不唯一，未强行整理行列");return List.of();}
        headers.addFirst(names.getFirst());
        for(int n=1;n<4;n++)if(headers.get(n).centerX()<=headers.get(n-1).centerX())return List.of();
        int headerBottom=headers.stream().mapToInt(Box::bottom).max().orElse(0);
        var body=boxes.stream().filter(b->b.y()>=headerBottom).sorted(Comparator.comparingInt(Box::y).thenComparingInt(Box::x)).toList();
        var bounded=new ArrayList<Box>();int covered=headerBottom;
        for(var box:body){if(box.y()-covered>height*6||footer(box.text()))break;bounded.add(box);covered=Math.max(covered,box.bottom());}
        double[] edges={headers.getFirst().x()-headers.getFirst().width(),(headers.get(0).centerX()+headers.get(1).centerX())/2.0,(headers.get(1).centerX()+headers.get(2).centerX())/2.0,(headers.get(2).centerX()+headers.get(3).centerX())/2.0,headers.get(3).centerX()+(headers.get(3).centerX()-headers.get(2).centerX())/2.0};
        var columns=new ArrayList<List<Box>>();for(int n=0;n<4;n++)columns.add(new ArrayList<>());
        for(var b:bounded)for(int c=0;c<4;c++)if(b.centerX()>edges[c]&&b.centerX()<edges[c+1]&&b.width()<1.6*(edges[c+1]-edges[c])){columns.get(c).add(b);break;}
        var descriptions=columns.getFirst().stream().filter(b->!b.text().isBlank()&&!b.text().matches("[0-9\\s.]+")&&!b.text().contains("合计")&&!b.text().contains("总计")).toList();
        if(descriptions.size()>200)throw new OcrFailure("BAIDU_RESULT_TOO_LARGE");
        var result=new ArrayList<Map<String,Object>>();
        for(var description:descriptions){
            var item=new LinkedHashMap<String,Object>();item.put("description",description.text());item.put("origin","PROGRAM_COORDINATE_TABLE_V1");
            var evidence=new ArrayList<Map<String,Object>>();evidence.add(description.source());
            String[] fields={"quantity","unitPrice","lineAmount"};
            for(int column=1;column<4;column++){
                String field=fields[column-1];var candidates=columns.get(column).stream().filter(b->aligned(description,b)).toList();String value="";
                if(candidates.size()==1&&descriptions.stream().filter(b->aligned(b,candidates.getFirst())).count()==1){
                    var cell=candidates.getFirst();evidence.add(cell.source());
                    if(column==1)item.put("quantityOriginal",cell.text());
                    if(cell.text().matches("[0-9]{1,16}(?:\\.[0-9]{1,2})?"))value=new BigDecimal(cell.text()).toPlainString();else warnings.add("明细数字单元格模糊，保持空值；字母不转换为数字");
                }else warnings.add("明细存在缺失或跨行歧义，数量/单价/金额保持空值待核对");
                item.put(field,value);
            }
            item.put("evidence",evidence);
            if(!item.get("quantity").equals("")&&!item.get("unitPrice").equals("")){
                BigDecimal computed=new BigDecimal(item.get("quantity").toString()).multiply(new BigDecimal(item.get("unitPrice").toString()));item.put("calculatedAmount",computed.toPlainString());
                if(!item.get("lineAmount").equals("")&&computed.compareTo(new BigDecimal(item.get("lineAmount").toString()))!=0)warnings.add("明细数量乘单价与单据金额不符，须核对");
            }
            result.add(item);
        }
        if(!result.isEmpty())warnings.add("维修表格为程序按坐标整理，须逐行核对，不是百度原生结构化表格");return result;
    }
    private static boolean aligned(Box a,Box b){int overlap=Math.min(a.bottom(),b.bottom())-Math.max(a.y(),b.y());return overlap>=0.6*Math.min(a.height(),b.height());}
    private static boolean footer(String text){return text.matches("^(?:维修金额|付款方式|客户签名|客户签字|经手人|维修人|修理人|修人|备注|注)[：:].*")||text.matches("^(?:合计|共计|总计|金额大写)(?:[：:\\s￥¥0-9].*)?$");}
}
