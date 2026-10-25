package com.interviewgym.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeChunkerTest {

    private final ResumeChunker chunker = new ResumeChunker();

    @Test
    void splitsIntoChunksWithSectionsAndOverlap() {
        String skills = "技".repeat(200);
        String projects = "项".repeat(300);
        String education = "教".repeat(100);
        String resume = "# 技能\n\n" + skills + "\n\n# 项目经历\n\n" + projects + "\n\n# 教育\n\n" + education;

        List<ResumeChunk> chunks = chunker.chunk(resume);

        assertThat(chunks).hasSize(2);
        // 块 0：技能节（200 字），块 1：以技能尾部 50 字重叠开头，接项目 + 教育聚合（452 字）
        assertThat(chunks.get(0).section()).isEqualTo("技能");
        assertThat(chunks.get(0).text()).isEqualTo(skills);
        assertThat(chunks.get(1).section()).isEqualTo("项目经历");
        assertThat(chunks.get(1).text())
                .startsWith("技".repeat(ResumeChunker.OVERLAP))
                .contains(projects)
                .contains(education);
        assertThat(chunks.get(0).chunkIndex()).isEqualTo(0);
        assertThat(chunks.get(1).chunkIndex()).isEqualTo(1);
    }

    @Test
    void allChunksStayWithinTargetBound() {
        String monsterLine = "长".repeat(1_000);
        String resume = "# 项目\n\n" + monsterLine;

        List<ResumeChunk> chunks = chunker.chunk(resume);

        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        assertThat(chunks).allSatisfy(c -> assertThat(c.text().length())
                .isLessThanOrEqualTo(ResumeChunker.TARGET_MAX + 1));
        assertThat(chunks).allSatisfy(c -> assertThat(c.section()).isEqualTo("项目"));
    }

    @Test
    void shortResumeYieldsSingleChunk() {
        String resume = "# 技能\n\nJava、Spring Boot、MySQL、Redis。";

        List<ResumeChunk> chunks = chunker.chunk(resume);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).section()).isEqualTo("技能");
        assertThat(chunks.get(0).text()).isEqualTo("Java、Spring Boot、MySQL、Redis。");
    }

    @Test
    void headingWithTrailingContentCarriesSection() {
        // 标题与内容同段：# 标题\n内容
        String resume = "# 实习经历\n在某某公司负责后端开发，使用 Java 与 Spring Boot 完成订单模块。";

        List<ResumeChunk> chunks = chunker.chunk(resume);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).section()).isEqualTo("实习经历");
        assertThat(chunks.get(0).text()).doesNotStartWith("#");
    }

    @Test
    void noHeadingFallsBackToBlankSection() {
        List<ResumeChunk> chunks = chunker.chunk("一段没有标题的简历内容，".repeat(10));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).section()).isEmpty();
    }

    @Test
    void decoratedShortLineIsTreatedAsHeading() {
        String resume = "**项目经历**\n\n内容一：交易平台，负责撮合核心模块。\n\n**技能**\n\nJava 并发与 JVM。";

        List<ResumeChunk> chunks = chunker.chunk(resume);

        // 两段内容合计远小于 500 → 聚合为一块，节标签取首块所在节
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).section()).isEqualTo("项目经历");
        assertThat(chunks.get(0).text())
                .contains("交易平台")
                .contains("Java 并发");
    }
}
