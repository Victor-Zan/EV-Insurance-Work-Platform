package com.evinsurance.platform.integration.sms;

/** Replaceable SMS boundary. The MVP only records in-app notifications. */
public interface SmsProvider {
    enum Status { DISABLED }
    record Result(Status status) {}
    Result send(String recipient, String text);
}
