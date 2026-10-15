package com.evinsurance.platform.workorder.domain;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class WorkflowNodeTest {
    @Test void incompleteEvidenceKeepsTheCaseAtAssessment() {
        assertThat(WorkflowNode.current("WAITING_REPAIR_AUTHORIZATION",true,false)).isEqualTo(WorkflowNode.INSURER_ASSESSMENT);
        assertThat(WorkflowNode.current("WAITING_REPAIR_AUTHORIZATION",false,true)).isEqualTo(WorkflowNode.INSURER_ASSESSMENT);
        assertThat(WorkflowNode.current("WAITING_REPAIR_AUTHORIZATION",true,true)).isEqualTo(WorkflowNode.REPAIR_AUTHORIZATION);
    }
    @Test void receiptCompletesTheRepairFlowWithoutPaymentOrRating() {
        assertThat(WorkflowNode.current("COMPLETED",true,true)).isEqualTo(WorkflowNode.COMPLETE);
        assertThat(WorkflowNode.current("WAITING_INSURER_PAYMENT",true,true)).isNull();
        assertThat(WorkflowNode.current("WAITING_SHOP_SETTLEMENT",true,true)).isNull();
        assertThat(WorkflowNode.values()).hasSize(9);
    }
}
