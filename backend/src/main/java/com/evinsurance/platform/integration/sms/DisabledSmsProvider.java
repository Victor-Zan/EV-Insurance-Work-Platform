package com.evinsurance.platform.integration.sms;

import org.springframework.stereotype.Component;

/** No transport, credentials, logging of content, or external API calls. */
@Component
public class DisabledSmsProvider implements SmsProvider {
    @Override
    public Result send(String recipient, String text) {
        return new Result(Status.DISABLED);
    }
}
