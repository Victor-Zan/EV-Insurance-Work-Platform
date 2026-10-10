package com.evinsurance.platform.repair.api;
import java.util.*;
public final class RepairRequests {
    private RepairRequests() {}
    public record Progress(Integer expectedVersion,Integer assignmentVersion,String note,List<UUID> photoIds) {}
    public record Complete(Integer expectedVersion,Integer assignmentVersion,List<UUID> photoIds) {}
    public record Receive(Integer expectedVersion) {}
    public record Withdraw(Integer expectedVersion,String reason) {}
    public record Review(Integer expectedVersion,String text,tools.jackson.databind.JsonNode score,String reason) {}
}
