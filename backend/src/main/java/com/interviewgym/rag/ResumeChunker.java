package com.interviewgym.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 简历切块器（spec: resume-rag.md）：按空行分段、识别节标题，聚合为目标 300~500 字符的块，
 * 相邻块间重叠约 50 字符；超长单行按滑窗硬切。参数改动属 spec 修订范围。
 */
@Component
public class ResumeChunker {

    /** 聚合上限：超过则闭合当前块（spec 目标区间上界） */
    static final int TARGET_MAX = 500;

    /** 单块硬切上限：预留重叠余量，重叠拼接后单块仍 ≈ TARGET_MAX */
    static final int HARD_SPLIT_MAX = 450;

    /** 相邻块重叠字符数 */
    static final int OVERLAP = 50;

    /** 标题裸文本长度上限：超过视为正文而非节标题 */
    private static final int HEADING_MAX_LENGTH = 25;

    public List<ResumeChunk> chunk(String resumeText) {
        Objects.requireNonNull(resumeText, "resumeText");
        List<Piece> pieces = pieces(resumeText.replace("\r\n", "\n"));
        List<ResumeChunk> chunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        String bufferSection = "";
        for (Piece piece : pieces) {
            if (buffer.isEmpty()) {
                buffer.append(piece.text());
                bufferSection = piece.section();
                continue;
            }
            if (buffer.length() + 1 + piece.text().length() <= TARGET_MAX) {
                // 仍装得下：并入当前块，节标签沿用首块
                buffer.append('\n').append(piece.text());
                continue;
            }
            chunks.add(new ResumeChunk(chunks.size(), bufferSection, buffer.toString()));
            buffer = new StringBuilder(tail(buffer.toString(), OVERLAP))
                    .append('\n').append(piece.text());
            bufferSection = piece.section();
        }
        if (!buffer.isEmpty()) {
            chunks.add(new ResumeChunk(chunks.size(), bufferSection, buffer.toString()));
        }
        return List.copyOf(chunks);
    }

    private record Piece(String section, String text) {
    }

    /** 段落 → 节标题追踪 + 超长内容硬切 */
    private static List<Piece> pieces(String normalized) {
        List<Piece> pieces = new ArrayList<>();
        String section = "";
        for (String paragraph : normalized.trim().split("\n\\s*\n")) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String firstLine = trimmed.contains("\n")
                    ? trimmed.substring(0, trimmed.indexOf('\n')).trim()
                    : trimmed;
            if (isHeading(firstLine)) {
                section = stripHeadingMarker(firstLine);
                if (!trimmed.contains("\n")) {
                    // 独立成段的标题：只更新节上下文，自身不产生块
                    continue;
                }
                trimmed = trimmed.substring(trimmed.indexOf('\n') + 1).trim();
            }
            String currentSection = section;
            for (String part : hardSplit(trimmed)) {
                pieces.add(new Piece(currentSection, part));
            }
        }
        return pieces;
    }

    /** 标题判定：markdown # 开头，或 ≤25 字且不含标点的短行（允许 * 装饰与尾部冒号） */
    private static boolean isHeading(String line) {
        if (line.startsWith("#")) {
            return true;
        }
        String bare = stripHeadingMarker(line);
        return !bare.isBlank() && bare.length() <= HEADING_MAX_LENGTH
                && !bare.matches(".*[。，、.,;；:：?？!！].*");
    }

    private static String stripHeadingMarker(String line) {
        return line.replaceAll("^[#*\\s]+", "")
                .replaceAll("[*\\s]+$", "")
                .replaceAll("[:：]$", "")
                .trim();
    }

    /** 超长内容硬切：按行聚合到 HARD_SPLIT_MAX；单行超限按滑窗（步长 = 上限 - 重叠）切 */
    private static List<String> hardSplit(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String rawLine : content.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.length() > HARD_SPLIT_MAX) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                for (int start = 0; start < line.length(); start += HARD_SPLIT_MAX - OVERLAP) {
                    parts.add(line.substring(start, Math.min(line.length(), start + HARD_SPLIT_MAX)));
                    if (start + HARD_SPLIT_MAX >= line.length()) {
                        break;
                    }
                }
                continue;
            }
            if (!current.isEmpty() && current.length() + 1 + line.length() > HARD_SPLIT_MAX) {
                parts.add(current.toString());
                current.setLength(0);
            }
            if (current.isEmpty()) {
                current = new StringBuilder(line);
            } else {
                current.append('\n').append(line);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static String tail(String text, int length) {
        return text.length() <= length ? text : text.substring(text.length() - length);
    }
}
