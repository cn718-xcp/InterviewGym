package com.interviewgym.core;

/**
 * 从 LLM 回复文本中提取 JSON 的容错工具（spec: question-generation.md / evaluation.md）。
 * 只做边界提取，不做 schema 校验——校验规则在各引擎且不得放宽（红线 3）。
 */
final class LlmJsonExtractor {

    private LlmJsonExtractor() {
    }

    /**
     * 剥掉 markdown 围栏（\`\`\`json ... \`\`\`）与围栏外的杂文字，返回最外层 JSON 对象文本。
     *
     * @throws IllegalArgumentException 文本中不存在 JSON 对象
     */
    static String extractJsonObject(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("```")) {
            int firstLineBreak = text.indexOf('\n');
            if (firstLineBreak >= 0) {
                text = text.substring(firstLineBreak + 1);
            }
            int closingFence = text.lastIndexOf("```");
            if (closingFence >= 0) {
                text = text.substring(0, closingFence);
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("输出不是合法 JSON");
        }
        return text.substring(start, end + 1);
    }
}
