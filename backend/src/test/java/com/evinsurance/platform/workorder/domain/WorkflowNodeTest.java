package com.evinsurance.platform.workorder.domain;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class WorkflowNodeTest {
    @Test void incompleteEvidenceKeepsTheCaseAtAssessment() {
        assertThat(WorkflowNode.current("WAITING_REPAIR_AUTHORIZATION",true,false)).isEqualTo(WorkflowNode.INSURER_ASSESSMENT);
        assertThat(WorkflowNode.current("WAITING_REPAIR_AUTHORIZATION",false,true)).isEqualTo(WorkflowNode.INSURER_ASSESSMENT);
        assertThat(WorkflowNode.current("WAITING_REPAIR_AUTHORIZATION",true,true)).isEqualTo(WorkflowNode.REPAIR);
        assertThat(WorkflowNode.current("REPAIRING",true,true)).isEqualTo(WorkflowNode.REPAIR);
    }
    @Test void receiptEntersFundsAndBothFundsStatusesShareOneNode() {
        assertThat(WorkflowNode.current("COMPLETED",true,true)).isEqualTo(WorkflowNode.COMPLETE);
        assertThat(WorkflowNode.current("WAITING_OWNER_CONFIRMATION",true,true)).isEqualTo(WorkflowNode.RECEIPT);
        assertThat(WorkflowNode.current("WAITING_INSURER_PAYMENT",true,true)).isEqualTo(WorkflowNode.FUNDS);
        assertThat(WorkflowNode.current("WAITING_SHOP_SETTLEMENT",true,true)).isEqualTo(WorkflowNode.FUNDS);
        assertThat(WorkflowNode.values()).hasSize(9);
    }
}
