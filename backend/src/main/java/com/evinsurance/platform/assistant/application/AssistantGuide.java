package com.evinsurance.platform.assistant.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
class AssistantGuide {
    private static final Pattern INSTRUCTIONS = Pattern.compile(
        "怎么|怎样|如何|哪里|在哪|从哪|去哪|找不到|看不到|不会|不能|不了|不显示|为什么|步骤|说明|指南|指引|操作|必填|权限|要不要|能否|还能|谁能|谁可以|需要什么|至少几|至少多少");
    private final List<Topic> topics;

    AssistantGuide() {
        try {
            var handbook = new ClassPathResource("assistant/platform-guide.md");
            topics = parse(handbook.getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new UncheckedIOException("Platform assistant handbook is unavailable", error);
        }
    }

    List<Section> find(String question) {
        String text = normalize(question);
        boolean instructions = asksForInstructions(text);
        return topics.stream().map(topic -> new Match(topic.section(), topic.score(text, instructions)))
            .filter(match -> match.score() > 0)
            .sorted(Comparator.comparingInt(Match::score).reversed()).limit(2)
            .map(Match::section).toList();
    }

    static boolean asksForInstructions(String input) { return INSTRUCTIONS.matcher(input).find(); }

    private static String normalize(String input) {
        return input.replaceAll("[\\s\\u3000]+", "").replaceFirst("[?？!！。]+$", "").toLowerCase(Locale.ROOT);
    }

    private static List<Topic> parse(String markdown) {
        var result = new ArrayList<Topic>();
        for (var block : markdown.split("(?m)^## ")) {
            var lines = block.strip().split("\\R", 3);
            if (lines.length == 3 && lines[1].startsWith("关键词：")) {
                var keywords = List.of(lines[1].substring("关键词：".length()).split("、")).stream()
                    .map(AssistantGuide::normalize).filter(word -> !word.isEmpty()).distinct().toList();
                result.add(new Topic(new Section(lines[0], lines[2].strip()), keywords));
            }
        }
        if (result.isEmpty()) throw new IllegalStateException("Platform handbook has no searchable sections");
        return List.copyOf(result);
    }

    record Section(String title, String content) {}
    private record Match(Section section, int score) {}
    private record Topic(Section section, List<String> keywords) {
        int score(String question, boolean instructions) {
            if (keywords.contains(question)) return Integer.MAX_VALUE;
            if (!instructions) return 0;
            var matches = keywords.stream().filter(question::contains).toList();
            int longest = matches.stream().mapToInt(String::length).max().orElse(0);
            return longest * 10 + Math.min(9, matches.size());
        }
    }
}
