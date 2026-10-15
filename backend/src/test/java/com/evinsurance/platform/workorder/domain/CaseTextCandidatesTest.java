package com.evinsurance.platform.workorder.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.*;
import org.junit.jupiter.api.Test;

class CaseTextCandidatesTest {
    private final CaseTextCandidates extractor=new CaseTextCandidates();
    @SuppressWarnings("unchecked")
    @Test void surveyorPhoneLabelIsNeverTreatedAsTheSurveyorsName(){
        var result=extractor.extract("·车险查勘合成\n查勘员电话：13926018473");
        assertThat((Map<String,String>)result.get("fields")).containsEntry("surveyorPhone","13926018473").containsEntry("surveyorName","合成");
        var evidence=(Map<String,List<Map<String,Object>>>)result.get("evidence");
        assertThat(evidence.get("surveyorName")).hasSize(1);assertThat(evidence.get("surveyorName").getFirst()).containsEntry("value","合成").doesNotContainKey("requiresConfirmation");
    }
    @SuppressWarnings("unchecked")
    @Test void insuredPlateIsAdoptedButDriverPhoneNeverReplacesDamagedPartyPhone(){
        var result=extractor.extract("·车险现场查勘合成\n20261015000000000001\n地址：合成接车地点\n查勘员合成:13926018473\n物主方电话：13826379142\n司机电话：13724861953\n标的车牌：粤B8Z71M\n车辆颜色：蓝色\n车辆品牌：合成品牌\n车型：合成车型");
        var fields=(Map<String,String>)result.get("fields");
        assertThat(fields).containsEntry("claimNo","20261015000000000001").containsEntry("ownerPhone","13826379142").containsEntry("surveyorName","合成").containsEntry("surveyorPhone","13926018473").containsEntry("vehicleColor","蓝色").containsEntry("accidentAddress","合成接车地点");
        assertThat(fields).containsEntry("vehiclePlate","粤B8Z71M").doesNotContainKey("vehicleVin");
        assertThat(result.get("lines").toString()).contains("司机电话","标的车牌");
    }
    @SuppressWarnings("unchecked")
    @Test void plateWithoutColonAndPhoneWithoutNameStillPrefillButBadPlateDoesNot(){
        var result=extractor.extract("标的车牌粤BD27391\n查勘员电话：13926018473");
        assertThat((Map<String,String>)result.get("fields")).containsEntry("vehiclePlate","粤BD27391").containsEntry("surveyorPhone","13926018473").doesNotContainKey("surveyorName");
        assertThat((Map<String,String>)extractor.extract("标的车牌：待定???").get("fields")).doesNotContainKey("vehiclePlate");
    }
    @SuppressWarnings("unchecked")
    @Test void phoneConflictsStayCandidatesButSurveyorNamePrefillsWithoutConfirmation(){
        var result=extractor.extract("·车险查勘合成\n20261015000000000002\n三者电话13826379142\n三者电话13926018473\n标的电话13724861953\n标的车牌粤B测试号");
        var fields=(Map<String,String>)result.get("fields");
        assertThat(fields).containsOnlyKeys("claimNo","surveyorName").containsEntry("surveyorName","合成");
        var evidence=(Map<String,List<Map<String,Object>>>)result.get("evidence");
        assertThat(evidence.get("ownerPhone")).hasSize(2);
        assertThat(evidence.get("surveyorName").getFirst()).doesNotContainKey("requiresConfirmation").containsEntry("line",0);
    }

    @SuppressWarnings("unchecked")
    @Test void explicitSurveyorNameTakesPriorityOverChatNickname(){
        var result=extractor.extract("·车险查勘合成\n查勘员姓名：测试姓名\n·车险查勘另名\n标的车牌：粤BD27391");
        assertThat((Map<String,String>)result.get("fields")).containsEntry("surveyorName","测试姓名").containsEntry("vehiclePlate","粤BD27391");
    }
}
