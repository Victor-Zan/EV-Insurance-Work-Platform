package com.evinsurance.platform.integration.ocr;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Maps original OCR boxes into the cells of a photographed, ruled repair table. */
final class ShopQuoteGrid {
    private static final Set<String> NAME_HEADERS = Set.of("配件维修项目", "配件名称", "维修项目", "项目名称", "商品名称", "品名");
    // A rule in original image coordinates, expressed at a common reference axis.
    private record Rule(double intercept, double slope, double reference, int votes) {
        double at(double coordinate) { return intercept + (coordinate - reference) * slope; }
    }
    private record Box(JsonNode source, String text, double x, double y, double width, double height) {
        double left() { return x - width / 2; }
        double right() { return x + width / 2; }
    }
    private record Layout(List<Rule> rows, List<Rule> columns) {}

    static boolean extract(JsonNode lines, JsonNode rawLines, byte[] original, ArrayNode items, Set<String> warnings) {
        var boxes = new ArrayList<Box>();
        for (var line : lines) {
            var location = line.path("location");
            int width = location.path("width").asInt(), height = location.path("height").asInt();
            if (width > 0 && height > 0 && !line.path("text").asString("").isBlank()) {
                boxes.add(new Box(line, line.path("text").asString("").strip(), location.path("left").asInt() + width / 2.0,
                    location.path("top").asInt() + height / 2.0, width, height));
            }
        }
        var names = boxes.stream().filter(box -> NAME_HEADERS.contains(header(box.text()))).toList();
        var quantities = boxes.stream().filter(box -> header(box.text()).equals("数量")).toList();
        var prices = boxes.stream().filter(box -> header(box.text()).equals("单价")).toList();
        // The fixed layout has no separate unit column. Keep the general parser for those sheets.
        if (boxes.stream().anyMatch(box -> header(box.text()).equals("单位"))) return false;
        if (names.size() != 1 || quantities.size() != 1 || prices.size() != 1) return false;
        var name = names.getFirst();
        var quantity = quantities.getFirst();
        var price = prices.getFirst();
        double pitch = price.x() - quantity.x();
        if (pitch < quantity.width() || name.x() >= quantity.left()) return false;
        double slope = (price.y() - name.y()) / (price.x() - name.x());
        double height = Math.max(quantity.height(), price.height());
        if (Math.abs(slope) > .35 || Math.abs(quantity.y() - price.y() - slope * (quantity.x() - price.x())) > height / 2) return false;

        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(original));
        } catch (IOException failure) {
            throw new OcrFailure("BAIDU_IMAGE_DAMAGED");
        }
        if (image == null) throw new OcrFailure("BAIDU_IMAGE_DAMAGED");
        var layout = locate(new Ink(image), name, quantity, price, pitch, height, slope, boxes);
        if (layout == null) return false;
        for (int row = 1; row < layout.rows().size(); row++) {
            var top = layout.rows().get(row - 1);
            var bottom = layout.rows().get(row);
            var cells = List.of(new ArrayList<Box>(), new ArrayList<Box>(), new ArrayList<Box>(), new ArrayList<Box>());
            for (var box : boxes) {
                if (box.y() <= top.at(box.x()) || box.y() >= bottom.at(box.x()) || ShopQuoteCandidates.footer(box.text())) continue;
                int column = column(box, layout.columns(), pitch);
                if (column >= 0) cells.get(column).add(box);
                else split(box, rawLines, top, bottom, layout.columns(), pitch, cells);
            }
            if (cells.getFirst().isEmpty()) {
                if (!cells.get(1).isEmpty() || !cells.get(2).isEmpty() || !cells.get(3).isEmpty()) {
                    warnings.add("有单元格未识别出项目名称，请对照原图补录该行");
                }
                continue;
            }
            var descriptions = cells.getFirst();
            descriptions.removeIf(box -> box.text().matches("[0-9０-９.．]+"));
            if (descriptions.isEmpty()) {
                warnings.add("有单元格未识别出项目名称，请对照原图补录该行");
                continue;
            }
            descriptions.sort(Comparator.comparingDouble(Box::x));
            var item = items.addObject().put("description", String.join(" ", descriptions.stream().map(Box::text).toList()))
                .put("origin", "PROGRAM_QUOTE_GRID").put("unit", "");
            var evidence = item.putArray("evidence");
            descriptions.forEach(box -> evidence.add(box.source()));
            readNumber(item, evidence, cells.get(1), "quantity", warnings);
            readNumber(item, evidence, cells.get(2), "unitPrice", warnings);
            readNumber(item, evidence, cells.get(3), "lineAmount", warnings);
        }
        if (items.isEmpty()) warnings.add("已定位维修表格，但未识别到项目名称，请对照原图手填");
        return true;
    }

    private static String header(String text) { return text.replaceAll("[\\s\\p{Z}/／]", ""); }

    private static int column(Box box, List<Rule> columns, double pitch) {
        double left = columns.get(0).at(box.y());
        double middle = columns.get(1).at(box.y());
        double right = columns.get(2).at(box.y());
        if (box.text().isBlank()) return -1;
        if (box.x() < left && box.right() <= left + .18 * pitch) return 0;
        // A numeric box crossing a divider has no reliable column assignment.
        double tolerance = .05 * pitch;
        if (box.x() > left && box.x() < middle && box.left() >= left - tolerance && box.right() <= middle + tolerance) return 1;
        if (box.x() > middle && box.x() < right && box.left() >= middle - tolerance && box.right() <= right + tolerance) return 2;
        if (box.x() > right && box.x() < right + pitch && box.left() >= right - tolerance && box.right() < right + 1.25 * pitch) return 3;
        return -1;
    }

    private static void split(Box original, JsonNode rawLines, Rule top, Rule bottom, List<Rule> columns, double pitch,
                              List<ArrayList<Box>> cells) {
        int line = original.source().path("line").asInt(-1);
        var characters = rawLines.path(line).path("chars");
        if (!characters.isArray()) return;
        var groups = List.of(new ArrayList<Box>(), new ArrayList<Box>(), new ArrayList<Box>(), new ArrayList<Box>());
        var combined = new StringBuilder();
        int previous = -1;
        for (var character : characters) {
            String text = character.path("char").asString("").strip();
            if (text.isEmpty()) continue;
            var location = character.path("location");
            int width = location.path("width").asInt(), height = location.path("height").asInt();
            if (width <= 0 || height <= 0) return;
            var box = new Box(character, text, location.path("left").asInt() + width / 2.0,
                location.path("top").asInt() + height / 2.0, width, height);
            if (box.y() <= top.at(box.x()) || box.y() >= bottom.at(box.x())) return;
            int column = column(box, columns, pitch);
            if (column < 0 || column < previous) return;
            groups.get(column).add(box);
            combined.append(text);
            previous = column;
        }
        if (groups.stream().filter(group -> !group.isEmpty()).count() < 2
            || !combined.toString().equals(original.text().replaceAll("[\\s\\p{Z}]", ""))) return;
        for (int column = 0; column < groups.size(); column++) {
            var group = groups.get(column);
            if (group.isEmpty()) continue;
            String value = String.join("", group.stream().map(Box::text).toList());
            var source = ((ObjectNode) original.source()).objectNode().put("line", line).put("text", value)
                .put("origin", "BAIDU_CHARACTERS_IN_CELL");
            var parts = source.putArray("characters");
            for (var box : group) {
                parts.addObject().put("text", box.text()).set("location", box.source().path("location"));
            }
            cells.get(column).add(new Box(source, value, group.getFirst().x(), original.y(), original.width(), original.height()));
        }
    }

    private static void readNumber(ObjectNode item, ArrayNode evidence, List<Box> boxes, String field, Set<String> warnings) {
        boxes.forEach(box -> evidence.add(box.source()));
        if (boxes.size() == 1) {
            var original = boxes.getFirst().text();
            if (field.equals("quantity")) item.put("quantityOriginal", original);
            item.put(field, ShopQuoteCandidates.number(field, original, warnings));
        } else {
            item.put(field, "");
            if (boxes.size() > 1) warnings.add("数字单元格有多个识别结果，留空待核对");
        }
    }

    private static Layout locate(Ink ink, Box name, Box quantity, Box price, double pitch, double height, double slope, List<Box> boxes) {
        int referenceY = (int) quantity.y();
        int scanEnd = Math.min(ink.height - 1, referenceY + (int) (pitch * 8));
        if (scanEnd - referenceY < height * 2) return null;
        var columns = new ArrayList<Rule>();
        for (double expected : new double[] {quantity.x() - pitch / 2, (quantity.x() + price.x()) / 2, price.x() + pitch / 2}) {
            var rule = ink.vertical(expected, pitch * .30, referenceY, scanEnd);
            if (rule == null) return null;
            columns.add(rule);
        }
        int startX = Math.max(2, (int) (name.x() - pitch * .3));
        int endX = Math.min(ink.width - 2, (int) (price.x() + pitch * .25));
        int referenceX = (int) price.x();
        int endY = Math.min(ink.height - 2, scanEnd + (int) pitch);
        var horizontal = ink.horizontal(startX, endX, referenceX, (int) price.y(), endY, slope, height);
        var rows = new ArrayList<Rule>();
        for (var rule : horizontal) {
            if (rows.isEmpty()) {
                if (rule.at(name.x()) <= name.y() || rule.at(quantity.x()) <= quantity.y()) continue;
                if (rule.intercept() - price.y() > height * 1.5) return null;
                rows.add(rule);
                continue;
            }
            Rule previous = rows.getLast();
            double gap = rule.intercept() - previous.intercept();
            if (gap < height * .6) continue;
            if (gap > height * 4 || boxes.stream().anyMatch(box -> ShopQuoteCandidates.footer(box.text())
                && box.y() > previous.at(box.x()) && box.y() <= rule.at(box.x()))) break;
            if (columns.stream().anyMatch(divider -> !ink.connects(divider, previous, rule))) break;
            rows.add(rule);
        }
        // A detected header without a connected detail row is not enough to classify cells.
        return rows.size() > 1 ? new Layout(rows, columns) : null;
    }

    private static final class Ink {
        private final int width, height;
        private final boolean[] dark;

        Ink(BufferedImage image) {
            width = image.getWidth();
            height = image.getHeight();
            dark = new boolean[width * height];
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                image.getRGB(0, y, width, 1, row, 0, width);
                for (int x = 0; x < width; x++) {
                    int rgb = row[x];
                    int red = rgb >> 16 & 255, green = rgb >> 8 & 255, blue = rgb & 255;
                    dark[y * width + x] = (rgb >>> 24) > 127 && (red * 3 + green * 6 + blue) < 1300;
                }
            }
        }

        private boolean near(int x, int y, boolean vertical) {
            if (x < 2 || x >= width - 2 || y < 2 || y >= height - 2) return false;
            int stride = vertical ? 1 : width;
            int index = y * width + x;
            for (int offset = -2; offset <= 2; offset++) if (dark[index + offset * stride]) return true;
            return false;
        }

        Rule vertical(double expected, double radius, int start, int end) {
            Rule best = null;
            for (int x = (int) (expected - radius); x <= expected + radius; x += 2) {
                for (int tilt = -25; tilt <= 25; tilt++) {
                    double slope = tilt / 100.0;
                    int votes = 0;
                    for (int y = start; y < end; y += 4) if (near((int) Math.round(x + (y - start) * slope), y, true)) votes++;
                    if (best == null || votes > best.votes()) best = new Rule(x, slope, start, votes);
                }
            }
            // Refine the best long line to follow narrow rules even on a high resolution photo.
            var coarse = best;
            for (int offset = -2; offset <= 2; offset++) {
                for (int tilt = -5; tilt <= 5; tilt++) {
                    double x = coarse.intercept() + offset, slope = coarse.slope() + tilt / 500.0;
                    int votes = 0;
                    for (int y = start; y < end; y += 4) if (near((int) Math.round(x + (y - start) * slope), y, true)) votes++;
                    if (votes > best.votes()) best = new Rule(x, slope, start, votes);
                }
            }
            return best.votes() * 4 >= (end - start) * .33 ? best : null;
        }

        List<Rule> horizontal(int start, int end, int reference, int from, int to, double slope, double height) {
            var peaks = new ArrayList<Rule>();
            for (int y = from; y <= to; y++) {
                Rule best = null;
                for (int tilt = -12; tilt <= 12; tilt++) {
                    double direction = slope + tilt / 100.0;
                    int votes = 0;
                    for (int x = start; x < end; x += 4) if (near(x, (int) Math.round(y + (x - reference) * direction), false)) votes++;
                    if (best == null || votes > best.votes()) best = new Rule(y, direction, reference, votes);
                }
                if (best.votes() * 4 >= (end - start) * .55) peaks.add(best);
            }
            var rules = new ArrayList<Rule>();
            peaks.sort(Comparator.comparingInt(Rule::votes).reversed());
            for (var peak : peaks) {
                if (rules.stream().noneMatch(rule -> Math.abs(rule.intercept() - peak.intercept()) < height * .5)) rules.add(peak);
            }
            rules.sort(Comparator.comparingDouble(Rule::intercept));
            return rules;
        }

        boolean connects(Rule column, Rule top, Rule bottom) {
            double x = column.at((top.intercept() + bottom.intercept()) / 2);
            int start = (int) top.at(x) + 3, end = (int) bottom.at(x) - 3;
            if (start >= end) return false;
            int votes = 0;
            for (int y = start; y < end; y++) if (near((int) Math.round(column.at(y)), y, true)) votes++;
            return votes >= (end - start) * .35;
        }
    }
}
