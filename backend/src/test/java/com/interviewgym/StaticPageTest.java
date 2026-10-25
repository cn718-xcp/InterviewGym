package com.interviewgym;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 静态首页由 Boot 托管（D008：零构建前端单页）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StaticPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void indexPageIsServed() throws Exception {
        // 直接断言静态资源本身；"/" 欢迎页 forward 行为由真实启动冒烟验证（MockMvc 不跟进 forward）
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("InterviewGym")))
                .andExpect(content().string(containsString("api/interviews")));
    }
}
