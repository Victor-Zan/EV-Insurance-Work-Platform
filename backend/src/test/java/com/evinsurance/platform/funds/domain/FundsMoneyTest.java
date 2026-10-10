package com.evinsurance.platform.funds.domain;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
class FundsMoneyTest {
    private final JsonMapper json=JsonMapper.builder().build();
    @Test void decimalStringsAreExactAndActualFlowsMustBePositive(){
        assertThat(FundsMoney.parse(json.valueToTree("100.01"),true)).isEqualByComparingTo("100.01");
        for(String invalid:new String[]{"-1.00","1.001","1e2","NaN","0.00"})assertThatThrownBy(()->FundsMoney.parse(json.valueToTree(invalid),true)).isInstanceOf(com.evinsurance.platform.foundation.api.ApiException.class);
        assertThatThrownBy(()->FundsMoney.parse(json.valueToTree(1),true)).isInstanceOf(com.evinsurance.platform.foundation.api.ApiException.class);
        assertThat(FundsMoney.parse(json.valueToTree("0"),false)).isEqualByComparingTo("0.00");
    }
    @Test void unknownAndZeroTargetsAreDifferentFromPartialSettlement(){
        assertThat(FundsMoney.status(null,new BigDecimal("0.00"))).isEqualTo("UNSET");
        assertThat(FundsMoney.status(new BigDecimal("0.00"),new BigDecimal("0.00"))).isEqualTo("SETTLED");
        assertThat(FundsMoney.status(new BigDecimal("100.00"),new BigDecimal("30.01"))).isEqualTo("PARTIAL");
        assertThat(FundsMoney.status(new BigDecimal("100.00"),new BigDecimal("100.00"))).isEqualTo("SETTLED");
    }
}
