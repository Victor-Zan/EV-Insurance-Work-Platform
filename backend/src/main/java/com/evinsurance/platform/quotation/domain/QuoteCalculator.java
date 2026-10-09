package com.evinsurance.platform.quotation.domain;

import com.evinsurance.platform.foundation.api.ApiException;
import java.math.*;
import java.util.*;

/** Calculation version PRO_RATA_LARGEST_REMAINDER_V1; money authority is the two-decimal line amount. */
public final class QuoteCalculator {
    private QuoteCalculator() {}
    public enum Mode { FIXED_AMOUNT, PERCENTAGE }
    public record Line(int sequence,String description,int quantity,BigDecimal unitPrice) {}
    public record AllocatedLine(int sequence,String description,int quantity,BigDecimal originalUnitPrice,
        BigDecimal originalAmount,BigDecimal allocatedMarkup,BigDecimal externalUnitPrice,BigDecimal externalAmount) {}
    public record Result(BigDecimal originalTotal,BigDecimal markupAmount,BigDecimal total,List<AllocatedLine> lines) {}

    public static BigDecimal input(String value) {
        if(value==null || !value.matches("[0-9]{1,36}(\\.[0-9]{1,2})?")) throw ApiException.invalid("Use a non-negative decimal string in CNY yuan with at most two decimal places");
        return capacity(new BigDecimal(value).setScale(2));
    }
    public static BigDecimal capacity(BigDecimal value) {
        if(value.signum()<0 || value.precision()-value.scale()>36) throw ApiException.invalid("Amount exceeds NUMERIC(38,2) storage capacity");
        return value.setScale(2,RoundingMode.UNNECESSARY);
    }
    public static Result calculate(List<Line> lines,Mode mode,BigDecimal markupValue) {
        if(lines==null || lines.isEmpty() || lines.size()>1000 || mode==null || markupValue==null) throw ApiException.invalid("A quote requires 1..1000 lines and one markup mode");
        capacity(markupValue);
        if(mode==Mode.PERCENTAGE && markupValue.compareTo(new BigDecimal("100.00"))>0) throw ApiException.invalid("Percentage must be in 0..100");
        var amounts=new ArrayList<BigDecimal>(); var weights=new ArrayList<BigInteger>();
        BigDecimal original=BigDecimal.ZERO.setScale(2);
        var sequences=new HashSet<Integer>();
        for(var line:lines) {
            if(line.quantity()<1 || line.sequence()<1 || !sequences.add(line.sequence()) || line.description()==null || line.description().isBlank() || line.description().length()>255) throw ApiException.invalid("Invalid quote line");
            capacity(line.unitPrice());
            BigDecimal amount=capacity(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())));
            original=capacity(original.add(amount)); amounts.add(amount);weights.add(amount.movePointRight(2).toBigIntegerExact());
        }
        BigDecimal markup=mode==Mode.FIXED_AMOUNT?markupValue.setScale(2):capacity(original.multiply(markupValue).movePointLeft(2).setScale(2,RoundingMode.HALF_UP));
        if(original.signum()==0 && markup.signum()!=0) throw ApiException.invalid("A zero original total only supports zero markup");
        BigDecimal total=capacity(original.add(markup));
        BigInteger base=original.movePointRight(2).toBigIntegerExact(), extra=markup.movePointRight(2).toBigIntegerExact(), assigned=BigInteger.ZERO;
        var shares=new ArrayList<BigInteger>();var remainders=new ArrayList<BigInteger>();var ranking=new ArrayList<Integer>();
        for(int i=0;i<lines.size();i++) {
            BigInteger[] division=base.signum()==0?new BigInteger[]{BigInteger.ZERO,BigInteger.ZERO}:extra.multiply(weights.get(i)).divideAndRemainder(base);
            shares.add(division[0]);remainders.add(division[1]);assigned=assigned.add(division[0]);ranking.add(i);
        }
        ranking.sort(Comparator.<Integer,BigInteger>comparing(remainders::get).reversed().thenComparingInt(i->lines.get(i).sequence()));
        int residual=extra.subtract(assigned).intValueExact();
        for(int i=0;i<residual;i++){int index=ranking.get(i);shares.set(index,shares.get(index).add(BigInteger.ONE));}
        var output=new ArrayList<AllocatedLine>();BigDecimal check=BigDecimal.ZERO.setScale(2);
        for(int i=0;i<lines.size();i++) {
            var line=lines.get(i);BigDecimal share=new BigDecimal(shares.get(i),2), external=capacity(amounts.get(i).add(share));
            output.add(new AllocatedLine(line.sequence(),line.description(),line.quantity(),line.unitPrice().setScale(2),amounts.get(i),share,external.divide(BigDecimal.valueOf(line.quantity()),6,RoundingMode.HALF_UP),external));check=check.add(external);
        }
        if(check.compareTo(total)!=0) throw new IllegalStateException("External quote total invariant failed");
        return new Result(original,markup,total,List.copyOf(output));
    }
}
