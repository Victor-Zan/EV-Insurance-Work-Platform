package com.evinsurance.platform.workorder.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.*;
import org.junit.jupiter.api.Test;

class CaseTextCandidatesTest {
    private final CaseTextCandidates extractor=new CaseTextCandidates();
    @SuppressWarnings("unchecked")
    @Test void surveyorPhoneLabelIsNeverTreatedAsTheSurveyorsName(){
        var result=extractor.extract("·车险查勘合成\n查勘员电话：13926018473");
        assertThat((Map<String,String>)result.get("fields")).containsOnlyKeys("surveyorPhone");
        var evidence=(Map<String,List<Map<String,Object>>>)result.get("evidence");
        assertThat(evidence.get("surveyorName")).hasSize(1);assertThat(evidence.get("surveyorName").getFirst()).containsEntry("value","合成").containsEntry("requiresConfirmation",true);
    }
    @SuppressWarnings("unchecked")
    @Test void damagedPartyFieldsNeverTakeInsuredPlateOrPhoneAndNicknameNeedsReview(){
        var result=extractor.extract("·车险现场查勘合成\n20261015000000000001\n地址：合成接车地点\n查勘员合成:13926018473\n物主方电话：13826379142\n司机电话：13724861953\n标的车牌：粤B测试号\n车辆颜色：蓝色\n车辆品牌：合成品牌\n车型：合成车型");
        var fields=(Map<String,String>)result.get("fields");
        assertThat(fields).containsEntry("claimNo","20261015000000000001").containsEntry("ownerPhone","13826379142").containsEntry("surveyorName","合成").containsEntry("surveyorPhone","13926018473").containsEntry("vehicleColor","蓝色").containsEntry("accidentAddress","合成接车地点");
        assertThat(fields).doesNotContainKeys("vehiclePlate","vehicleVin");
        assertThat(result.get("lines").toString()).contains("司机电话","标的车牌");
    }
    @SuppressWarnings("unchecked")
    @Test void conflictsAndOnlyNicknamesStayCandidatesMissingFieldsAreNotInvented(){
        var result=extractor.extract("·车险查勘合成\n20261015000000000002\n三者电话13826379142\n三者电话13926018473\n标的电话13724861953\n标的车牌粤B测试号");
        var fields=(Map<String,String>)result.get("fields");
        assertThat(fields).containsOnlyKeys("claimNo");
        var evidence=(Map<String,List<Map<String,Object>>>)result.get("evidence");
        assertThat(evidence.get("ownerPhone")).hasSize(2);
        assertThat(evidence.get("surveyorName").getFirst()).containsEntry("requiresConfirmation",true).containsEntry("line",0);
    }
}
