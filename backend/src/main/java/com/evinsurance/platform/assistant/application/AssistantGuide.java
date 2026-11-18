package com.evinsurance.platform.assistant.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
class AssistantGuide {
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
        if (!question.matches("(?s).*(怎么|怎样|如何|哪里|在哪|不会|不能|不了|为什么|步骤|说明|指南|指引|操作|找不到|不显示).*")) {
            return List.of();
        }
        return topics.stream().filter(topic -> topic.score(question) > 0)
            .sorted(Comparator.comparingInt((Topic topic) -> topic.score(question)).reversed()).limit(2)
            .map(Topic::section).toList();
    }

    private static List<Topic> parse(String markdown) {
        var result = new ArrayList<Topic>();
        for (var block : markdown.split("(?m)^## ")) {
            var lines = block.strip().split("\\R", 3);
            if (lines.length == 3 && lines[1].startsWith("关键词：")) {
                result.add(new Topic(new Section(lines[0], lines[2]), List.of(lines[1].substring("关键词：".length()).split("、"))));
            }
        }
        if (result.isEmpty()) throw new IllegalStateException("Platform handbook has no searchable sections");
        return List.copyOf(result);
    }

    record Section(String title, String content) {}
    private record Topic(Section section, List<String> keywords) {
        int score(String question) {
            return keywords.stream().filter(keyword -> question.contains(keyword) ||
                question.toLowerCase(java.util.Locale.ROOT).contains(keyword.toLowerCase(java.util.Locale.ROOT)))
                .mapToInt(String::length).max().orElse(0);
        }
    }
}
