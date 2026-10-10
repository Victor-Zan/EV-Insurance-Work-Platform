package com.evinsurance.platform.repair.domain;

import com.evinsurance.platform.document.infrastructure.FileRow;
import com.evinsurance.platform.foundation.api.ApiException;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RepairPhotoPolicyTest {
    private final UUID caseId=UUID.randomUUID();
    private FileRow photo(UUID order,long shop,int assignment,String category,String state,String mime){
        return new FileRow(UUID.randomUUID(),order,UUID.randomUUID(),category,1,state,"private","synthetic.png",mime,8,"0".repeat(64),shop,assignment,1,Instant.now());
    }
    @Test void requiresAnActualShopUploadRatherThanAssignmentMetadataAlone(){
        var file=photo(caseId,10,2,"COMPLETION_PHOTO","ACTIVE","image/png");
        assertDoesNotThrow(()->RepairPhotoPolicy.require(file,caseId,10,2,"COMPLETION_PHOTO",true));
        assertThrows(ApiException.class,()->RepairPhotoPolicy.require(file,caseId,10,2,"COMPLETION_PHOTO",false));
    }
    @Test void rejectsCrossCaseShopAssignmentHistoricalAndNonImageEvidence(){
        for(var file:java.util.List.of(
            photo(UUID.randomUUID(),10,2,"COMPLETION_PHOTO","ACTIVE","image/png"),
            photo(caseId,11,2,"COMPLETION_PHOTO","ACTIVE","image/png"),
            photo(caseId,10,1,"COMPLETION_PHOTO","ACTIVE","image/png"),
            photo(caseId,10,2,"PROGRESS_PHOTO","ACTIVE","image/png"),
            photo(caseId,10,2,"COMPLETION_PHOTO","VOID","image/png"),
            photo(caseId,10,2,"COMPLETION_PHOTO","SUPERSEDED","image/png"),
            photo(caseId,10,2,"COMPLETION_PHOTO","ACTIVE","application/pdf")))
            assertThrows(ApiException.class,()->RepairPhotoPolicy.require(file,caseId,10,2,"COMPLETION_PHOTO",true));
        assertThrows(ApiException.class,()->RepairPhotoPolicy.require(null,caseId,10,2,"COMPLETION_PHOTO",true));
    }
}
