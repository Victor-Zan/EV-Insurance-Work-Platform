package com.evinsurance.platform.document.domain;

public enum FileCategory {
    NOTICE, SHOP_QUOTE, ASSESSMENT, LOSS_ASSESSMENT, ARRIVAL_PHOTO, PROGRESS_PHOTO, COMPLETION_PHOTO;
    public boolean photo() { return name().endsWith("_PHOTO"); }
    public boolean shopVisible() { return photo() || this == SHOP_QUOTE; }
    public boolean ocrSupported() { return !photo(); }
}
