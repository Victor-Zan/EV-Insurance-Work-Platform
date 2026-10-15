package com.evinsurance.platform.workorder.domain;

public enum WorkflowNode {
    CREATE("建单"), DISPATCH_ARRIVAL("派单到店"), SHOP_QUOTE("网点报价"),
    QUOTE_REVIEW("报价审核"), INSURER_ASSESSMENT("保险核损"), REPAIR_AUTHORIZATION("维修授权"),
    REPAIR("维修"), RECEIPT("收车"), COMPLETE("完成");

    public final String label;
    WorkflowNode(String label) { this.label=label; }
    public static WorkflowNode current(String status,boolean amountPresent,boolean lossFilePresent) {
        return switch(status) {
            case "DRAFT" -> CREATE;
            case "PENDING_DISPATCH","PENDING_ACCEPTANCE","PENDING_ARRIVAL","ARRIVAL_EXCEPTION" -> DISPATCH_ARRIVAL;
            case "ARRIVED","WAITING_QUOTE" -> SHOP_QUOTE;
            case "QUOTE_REVIEWING" -> QUOTE_REVIEW;
            case "WAITING_INSURER_ASSESSMENT" -> INSURER_ASSESSMENT;
            case "WAITING_REPAIR_AUTHORIZATION" -> amountPresent&&lossFilePresent?REPAIR_AUTHORIZATION:INSURER_ASSESSMENT;
            case "REPAIRING" -> REPAIR;
            case "WAITING_OWNER_CONFIRMATION" -> RECEIPT;
            case "COMPLETED" -> COMPLETE;
            default -> null;
        };
    }
}
