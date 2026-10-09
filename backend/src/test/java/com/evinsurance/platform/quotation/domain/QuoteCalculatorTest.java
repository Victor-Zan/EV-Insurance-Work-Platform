package com.evinsurance.platform.quotation.domain;
import static org.assertj.core.api.Assertions.*;
import com.evinsurance.platform.quotation.domain.QuoteCalculator.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
class QuoteCalculatorTest {
    private Line line(int number,int quantity,String price){return new Line(number,"测试明细"+number,quantity,QuoteCalculator.input(price));}
    @Test void fixedAndPercentageUseOriginalQuantityAmounts() {
        var lines=List.of(line(1,2,"30.00"),line(2,1,"40.00"));
        var fixed=QuoteCalculator.calculate(lines,Mode.FIXED_AMOUNT,QuoteCalculator.input("10"));
        assertThat(fixed.originalTotal()).isEqualByComparingTo("100");assertThat(fixed.total()).isEqualByComparingTo("110");
        assertThat(fixed.lines().get(0).externalAmount()).isEqualByComparingTo("66");assertThat(fixed.lines().get(1).externalAmount()).isEqualByComparingTo("44");
        var percentage=QuoteCalculator.calculate(lines,Mode.PERCENTAGE,QuoteCalculator.input("1.23"));assertThat(percentage.markupAmount()).isEqualByComparingTo("1.23");
        assertThat(QuoteCalculator.calculate(List.of(line(1,1,"0.05")),Mode.PERCENTAGE,QuoteCalculator.input("10")).markupAmount()).isEqualByComparingTo("0.01");
        assertThat(lines.get(0).unitPrice()).isEqualByComparingTo("30.00");
    }
    @Test void residualCentsUseSequenceAndZeroLinesNeverReceiveMarkup() {
        var result=QuoteCalculator.calculate(List.of(line(1,1,"0.01"),line(2,1,"0.01"),line(3,1,"0.01"),line(4,5,"0")),Mode.FIXED_AMOUNT,QuoteCalculator.input("0.02"));
        assertThat(result.lines()).extracting(x->x.externalAmount().toPlainString()).containsExactly("0.02","0.02","0.01","0.00");
        var quantity=QuoteCalculator.calculate(List.of(line(1,3,"0.01")),Mode.FIXED_AMOUNT,QuoteCalculator.input("0.01"));
        assertThat(quantity.lines().getFirst().externalAmount()).isEqualByComparingTo("0.04");assertThat(quantity.lines().getFirst().externalUnitPrice()).isEqualByComparingTo("0.013333");
    }
    @Test void zeroAndInvalidInputsAreHandledWithoutSilentRounding() {
        assertThat(QuoteCalculator.calculate(List.of(line(1,1,"0")),Mode.FIXED_AMOUNT,QuoteCalculator.input("0")).total()).isEqualByComparingTo("0");
        assertThatThrownBy(()->QuoteCalculator.calculate(List.of(line(1,1,"0")),Mode.FIXED_AMOUNT,QuoteCalculator.input("1"))).isInstanceOf(RuntimeException.class);
        for(String invalid:List.of("1.001","-1","1e2","NaN"," 1",""))assertThatThrownBy(()->QuoteCalculator.input(invalid)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->QuoteCalculator.calculate(List.of(line(1,1,"1")),Mode.PERCENTAGE,QuoteCalculator.input("100.01"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->QuoteCalculator.calculate(List.of(line(1,0,"1")),Mode.FIXED_AMOUNT,QuoteCalculator.input("0"))).isInstanceOf(RuntimeException.class);
    }
    @Test void variedQuantitiesAndWeightsAlwaysConserveTheExactCentsAndInternalPrices() {
        var random=new Random(3905);
        for(int test=0;test<200;test++) {
            var lines=new java.util.ArrayList<Line>();for(int i=1;i<=15;i++)lines.add(line(i,1+random.nextInt(10),BigDecimal.valueOf(random.nextInt(10000),2).toPlainString()));
            var markup=BigDecimal.valueOf(random.nextInt(10000),2);var result=QuoteCalculator.calculate(lines,Mode.FIXED_AMOUNT,markup);
            assertThat(result.lines().stream().map(AllocatedLine::externalAmount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(result.total());
            assertThat(result.lines().stream().map(AllocatedLine::allocatedMarkup).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(markup);
            for(int i=0;i<lines.size();i++){assertThat(result.lines().get(i).originalUnitPrice()).isEqualByComparingTo(lines.get(i).unitPrice());assertThat(result.lines().get(i).allocatedMarkup()).isGreaterThanOrEqualTo(BigDecimal.ZERO);}
        }
    }
}
