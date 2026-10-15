package com.evinsurance.platform.workorder.domain;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class VehiclePlateTest {
    @Test void mainlandMotorAndNewEnergyStructuresAreRecognisedAndSeparatorsNormalised(){
        for(String plate:new String[]{"粤B8Z71M","京AD27391","沪A12345F"}){var result=VehiclePlate.check(plate);assertThat(result.acceptable()).isTrue();assertThat(result.review()).isFalse();}
        assertThat(VehiclePlate.check("粤 ｂ·8ｚ71ｍ").value()).isEqualTo("粤B8Z71M");
    }
    @Test void localBicycleFormatsNeedReviewAndClearlyInvalidDataIsRejected(){
        for(String plate:new String[]{"北京2739186","深圳A273918","2739186","A273918"}){var result=VehiclePlate.check(plate);assertThat(result.acceptable()).isTrue();assertThat(result.review()).isTrue();}
        for(String plate:new String[]{"无牌","???","粤B***","138263791423456789012345"})assertThat(VehiclePlate.check(plate).acceptable()).isFalse();
    }
}
