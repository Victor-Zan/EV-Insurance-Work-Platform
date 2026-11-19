package com.evinsurance.platform.assistant.application;

import com.evinsurance.platform.assistant.infrastructure.AssistantOrderReader;
import com.evinsurance.platform.assistant.infrastructure.BigModelClient.Turn;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import com.evinsurance.platform.foundation.api.ApiException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AssistantPrivacy {
    private static final Pattern FORMATTING = Pattern.compile("[\\p{Cf}\\p{Z}\\s]+");
    private static final Pattern ORDER_NUMBER = Pattern.compile("(?i)(?<![a-z0-9])EVR-[0-9]{8}-[0-9]{6}(?![a-z0-9])");
    private static final Pattern NUMBER = Pattern.compile("(?<![0-9])(?:(?:0086|86)?1[3-9][0-9]{9}|0[1-9][0-9]{8,10}|[0-9]{15,}[0-9Xx]?)(?![0-9])");
    private static final Pattern PLATE = Pattern.compile("[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼][A-Z][A-Z0-9]{4,7}", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIN = Pattern.compile("(?i)(?<![a-z0-9])(?=[a-z0-9]{0,16}[0-9])[a-hj-npr-z0-9]{17}(?![a-z0-9])");
    private static final Pattern LABELED = Pattern.compile(
        "(?:车主(?:的)?(?:姓名|名字)?|姓名|联系人)(?:叫|名为|为|是|[:=])(?!谁|什么|哪|否|必填|选填)\\p{IsHan}{1,}"
        + "|(?:住在|居住在|(?:家庭|详细|接车|停车|事故)?地址(?:为|是|[:=]))(?!哪里|什么|否|必填|选填)[\\p{L}0-9]+"
        + "|(?:流水(?:号|编号|单号)?|交易(?:参考号|编号|单号|号)|账号|银行卡号|报案号|保单号|车架号|车牌号?|证件号)"
        + "(?:是|为|[:=])?[a-z0-9][a-z0-9._/-]+"
        + "|[\\p{IsHan}]{2,}(?:路|街|巷|弄)[0-9一二三四五六七八九十]+号"
        + "|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}"
        + "|(?:API[_-]?KEY|ACCESS[_-]?TOKEN|PASSWORD|密码)[:=][^，。；,;]{3,}"
        + "|Bearer[A-Z0-9._-]{12,}|eyJ[A-Z0-9_-]+\\.eyJ[A-Z0-9_-]+\\.[A-Z0-9_-]+", Pattern.CASE_INSENSITIVE);
    private final AssistantOrderReader orders;

    public AssistantPrivacy(AssistantOrderReader orders) { this.orders = orders; }

    boolean blocks(String input, List<Turn> history) {
        var text = new StringBuilder();
        for (var turn : history) {
            if (appendAndCheck(text, turn.message()) || appendAndCheck(text, turn.answer())) return true;
        }
        if (appendAndCheck(text, input)) return true;
        // A protected value can also be split across messages.
        if (hasSensitiveFormat(text.toString())) return true;
        try {
            return orders.containsPrivateValue(text.toString().toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Punct}]", ""));
        } catch (DataAccessException unavailable) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_PRIVACY_UNAVAILABLE", "本地隐私检查暂不可用，本次发送已停止");
        }
    }

    private static boolean appendAndCheck(StringBuilder text, String message) {
        String normalized = FORMATTING.matcher(Normalizer.normalize(message, Normalizer.Form.NFKC)).replaceAll("");
        text.append(normalized);
        return hasSensitiveFormat(normalized);
    }

    private static boolean hasSensitiveFormat(String normalized) {
        String withoutOrders = ORDER_NUMBER.matcher(normalized).replaceAll(" ");
        String identifiers = withoutOrders.replaceAll("[-()·.]", "");
        return NUMBER.matcher(identifiers).find() || VIN.matcher(identifiers).find()
            || PLATE.matcher(identifiers).find() || LABELED.matcher(withoutOrders).find();
    }
}
