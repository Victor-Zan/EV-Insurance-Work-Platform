package com.evinsurance.platform.quotation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import com.evinsurance.platform.quotation.domain.RepairAuthorizationGate.Evidence;
import com.evinsurance.platform.quotation.domain.RepairAuthorizationGate.MissingCondition;

class RepairAuthorizationGateTest {
    @Test void onlyTheAllSatisfiedCombinationAuthorizesRepair() {
        for (int combination=0; combination<16; combination++) {
            var evidence=new Evidence((combination&1)!=0,(combination&2)!=0,(combination&4)!=0,(combination&8)!=0);
            assertThat(RepairAuthorizationGate.allowed(evidence)).as("four-condition combination %s",combination).isEqualTo(combination==15);
        }
    }
    @Test void eachMissingPrerequisiteIsReportedWithoutInventingPaymentOrShopConfirmation() {
        assertThat(RepairAuthorizationGate.missing(new Evidence(false,true,true,true))).containsExactly(MissingCondition.LOSS_ASSESSMENT_FILE);
        assertThat(RepairAuthorizationGate.missing(new Evidence(true,false,true,true))).containsExactly(MissingCondition.FINAL_ASSESSMENT_AMOUNT);
        assertThat(RepairAuthorizationGate.missing(new Evidence(true,true,false,true))).containsExactly(MissingCondition.INSURER_CONFIRMATION);
        assertThat(RepairAuthorizationGate.missing(new Evidence(true,true,true,false))).containsExactly(MissingCondition.CUSTOMER_SERVICE_CONFIRMATION);
        assertThat(RepairAuthorizationGate.missing(new Evidence(true,true,true,true))).isEmpty();
    }
    @Test void absentEvidenceIsNotImplicitlyAuthorized() {
        assertThatThrownBy(()->RepairAuthorizationGate.allowed(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
