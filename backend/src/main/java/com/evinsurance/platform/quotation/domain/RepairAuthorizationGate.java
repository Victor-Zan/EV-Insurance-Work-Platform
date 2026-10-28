package com.evinsurance.platform.quotation.domain;

import java.util.ArrayList;
import java.util.List;

/** The three current business prerequisites; the legacy file flag is informational; role/assignment/state checks remain in the application layer. */
public final class RepairAuthorizationGate {
    private RepairAuthorizationGate() {}

    public enum MissingCondition {
        FINAL_ASSESSMENT_AMOUNT, INSURER_CONFIRMATION, CUSTOMER_SERVICE_CONFIRMATION
    }

    public record Evidence(boolean lossAssessmentFileUploaded, boolean finalAssessmentAmountRecorded,
                           boolean insurerConfirmationRecorded, boolean customerServiceConfirmationRecorded) {}

    public static List<MissingCondition> missing(Evidence evidence) {
        if (evidence == null) throw new IllegalArgumentException("Current authorization evidence is required");
        var missing = new ArrayList<MissingCondition>();
        if (!evidence.finalAssessmentAmountRecorded()) missing.add(MissingCondition.FINAL_ASSESSMENT_AMOUNT);
        if (!evidence.insurerConfirmationRecorded()) missing.add(MissingCondition.INSURER_CONFIRMATION);
        if (!evidence.customerServiceConfirmationRecorded()) missing.add(MissingCondition.CUSTOMER_SERVICE_CONFIRMATION);
        return List.copyOf(missing);
    }

    public static boolean allowed(Evidence evidence) { return missing(evidence).isEmpty(); }
}
