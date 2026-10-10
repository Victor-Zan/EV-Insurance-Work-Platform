package com.evinsurance.platform.funds.domain;
import java.math.BigDecimal;
import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.quotation.domain.QuoteCalculator;
import tools.jackson.databind.JsonNode;
public final class FundsMoney {
    private FundsMoney() {}
    public static BigDecimal parse(JsonNode input,boolean positive) {
        if(input==null||!input.isTextual())throw ApiException.invalid("Use a decimal string in CNY yuan");
        BigDecimal value=QuoteCalculator.input(input.asText());
        if(positive&&value.signum()==0)throw ApiException.invalid("An actual receipt/payment must be positive");
        return value;
    }
    public static String status(BigDecimal target,BigDecimal net) {
        if(target==null)return "UNSET";
        if(net.compareTo(target)==0)return "SETTLED";
        return net.signum()==0?"UNPAID":"PARTIAL";
    }
}
