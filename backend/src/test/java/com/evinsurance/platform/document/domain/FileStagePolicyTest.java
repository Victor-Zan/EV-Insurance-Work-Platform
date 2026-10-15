package com.evinsurance.platform.document.domain;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.evinsurance.platform.foundation.api.ApiException;

class FileStagePolicyTest {
    @Test void futureEvidenceCannotBeUploadedEarly() {
        assertThat(FileStagePolicy.allowed("DRAFT",FileCategory.NOTICE,false)).isTrue();
        assertThat(FileStagePolicy.allowed("PENDING_ACCEPTANCE",FileCategory.ARRIVAL_PHOTO,false)).isFalse();
        assertThat(FileStagePolicy.allowed("PENDING_ARRIVAL",FileCategory.ARRIVAL_PHOTO,false)).isTrue();
        assertThat(FileStagePolicy.allowed("ARRIVED",FileCategory.SHOP_QUOTE,false)).isTrue();
        assertThat(FileStagePolicy.allowed("ARRIVED",FileCategory.LOSS_ASSESSMENT,false)).isFalse();
        assertThat(FileStagePolicy.allowed("WAITING_INSURER_ASSESSMENT",FileCategory.LOSS_ASSESSMENT,true)).isTrue();
        assertThat(FileStagePolicy.allowed("WAITING_REPAIR_AUTHORIZATION",FileCategory.COMPLETION_PHOTO,true)).isFalse();
        assertThatThrownBy(()->FileStagePolicy.require("ARRIVED",FileCategory.PROGRESS_PHOTO,false)).isInstanceOf(ApiException.class);
    }
    @Test void supplementationDoesNotUnfreezeQuotesOrTerminalCases() {
        assertThat(FileStagePolicy.allowed("REPAIRING",FileCategory.ARRIVAL_PHOTO,true)).isTrue();
        assertThat(FileStagePolicy.allowed("REPAIRING",FileCategory.PROGRESS_PHOTO,true)).isTrue();
        assertThat(FileStagePolicy.allowed("REPAIRING",FileCategory.COMPLETION_PHOTO,true)).isTrue();
        assertThat(FileStagePolicy.allowed("REPAIRING",FileCategory.SHOP_QUOTE,true)).isFalse();
        assertThat(FileStagePolicy.allowed("WAITING_SHOP_SETTLEMENT",FileCategory.NOTICE,true)).isFalse();
        for(String status:new String[]{"COMPLETED","CANCELLED","CLOSED"})for(var category:FileCategory.values())assertThat(FileStagePolicy.allowed(status,category,true)).isFalse();
    }
}
