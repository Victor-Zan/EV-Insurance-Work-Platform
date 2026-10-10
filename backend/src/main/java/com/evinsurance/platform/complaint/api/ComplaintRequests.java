package com.evinsurance.platform.complaint.api;
import java.util.*;
public final class ComplaintRequests {
    private ComplaintRequests() {}
    public record Create(String description,List<UUID> photoIds) {}
    public record Handle(Integer expectedVersion,String status,String publicNote,String internalNote) {}
    public record Correct(Integer expectedVersion,String publicNote) {}
}
