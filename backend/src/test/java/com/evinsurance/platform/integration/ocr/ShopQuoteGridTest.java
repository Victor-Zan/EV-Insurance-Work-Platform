package com.evinsurance.platform.integration.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ShopQuoteGridTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void locatesSlantedGridWithMissingAmountHeaderAndExcludesFooter() throws Exception {
        var raw = json.createObjectNode();
        var lines = raw.putArray("words_result");
        box(lines, "客户名称", 150, 52, 90);
        box(lines, "配件／维修项目", 156, y(0, 156), 110);
        box(lines, "数 量", 306, y(0, 306), 40);
        box(lines, "单价", 398, y(0, 398), 40);
        box(lines, "合成配件甲", 153, y(1, 153), 95);
        box(lines, "l", 308, y(1, 308), 12);
        box(lines, "140.", 403, y(1, 403), 42);
        box(lines, "合成配件乙", 150, y(2, 150), 95);
        var merged = box(lines, "２ 180.25", 364, y(2, 364), 148).putArray("chars");
        character(merged, "２", 309, 2);
        for (int i = 0; i < "180.25".length(); i++) character(merged, "180.25".substring(i, i + 1), 382 + 8 * i, 2);
        box(lines, "合成配件丙", 153, y(3, 153), 96);
        box(lines, "1.5", 310, y(3, 310), 30);
        box(lines, "20", 407, y(3, 407), 22);
        box(lines, "30", 436, y(3, 436), 22);
        box(lines, "合成配件丁", 159, y(4, 159), 100);
        box(lines, "22 60", 368, y(4, 368), 150);
        box(lines, "拾", 157, y(5, 157), 15);
        box(lines, "维修金额：", 80, y(5, 80), 90);
        box(lines, "备注", 155, y(6, 155), 38);

        var base = json.<ObjectNode>valueToTree(new HandwritingCandidates().extract(raw, "SHOP_QUOTE"));
        var result = new ShopQuoteCandidates().organize(base, json, photo(), json.writeValueAsBytes(raw));
        assertThat(result.path("items")).hasSize(4);
        assertThat(result.at("/items/0/origin").asString()).isEqualTo("PROGRAM_QUOTE_GRID");
        assertThat(result.at("/items/0/quantity").asString()).isEmpty();
        assertThat(result.at("/items/0/quantityOriginal").asString()).isEqualTo("l");
        assertThat(result.at("/items/0/unitPrice").asString()).isEqualTo("140");
        assertThat(result.at("/items/0/evidence/2/text").asString()).isEqualTo("140.");
        assertThat(result.at("/items/1/quantity").asString()).isEqualTo("2");
        assertThat(result.at("/items/1/unitPrice").asString()).isEqualTo("180.25");
        assertThat(result.at("/items/1/lineAmount").asString()).isEmpty();
        assertThat(result.at("/items/2/quantity").asString()).isEmpty();
        assertThat(result.at("/items/2/unitPrice").asString()).isEmpty();
        assertThat(result.at("/items/3/quantity").asString()).isEmpty();
        assertThat(result.at("/items/3/unitPrice").asString()).isEmpty();
        assertThat(result.path("lines")).isEqualTo(base.path("lines"));
    }

    private static byte[] photo() throws Exception {
        var image = new BufferedImage(570, 440, BufferedImage.TYPE_INT_RGB);
        var draw = image.createGraphics();
        try {
            draw.setColor(Color.WHITE);
            draw.fillRect(0, 0, image.getWidth(), image.getHeight());
            draw.setColor(Color.BLACK);
            draw.setStroke(new BasicStroke(2));
            for (int row = 0; row <= 7; row++) draw.drawLine(32, y(row, 32) - 24, 554, y(row, 554) - 24);
            int[] x = {36, 260, 351, 442, 540};
            int[] drift = {-13, 4, 15, 35, 9};
            for (int i = 0; i < x.length; i++) {
                draw.drawLine(x[i], y(0, x[i]) - 24, x[i] + drift[i], y(5, x[i] + drift[i]) - 24);
            }
        } finally {
            draw.dispose();
        }
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static int y(int row, int x) { return 116 + row * 48 - (x - 350) / 10; }

    private static ObjectNode box(ArrayNode lines, String text, int x, int y, int width) {
        var line = lines.addObject().put("words", text);
        location(line, x, y, width);
        return line;
    }

    private static void character(ArrayNode characters, String text, int x, int row) {
        location(characters.addObject().put("char", text), x, y(row, x), 7);
    }

    private static void location(ObjectNode node, int x, int y, int width) {
        node.putObject("location").put("left", x - width / 2).put("top", y - 10).put("width", width).put("height", 20);
    }
}
