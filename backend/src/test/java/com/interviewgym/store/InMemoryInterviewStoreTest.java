package com.interviewgym.store;

import com.interviewgym.core.Answer;
import com.interviewgym.core.EvaluationReport;
import com.interviewgym.core.InterviewSession;
import com.interviewgym.core.Question;
import com.interviewgym.core.QuestionType;
import com.interviewgym.core.SessionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryInterviewStoreTest {

    private final InMemoryInterviewStore store = new InMemoryInterviewStore();

    private InterviewSession session(String id) {
        return new InterviewSession(id, "Java 后端 JD", SessionStatus.ACTIVE,
                List.of(new Question("q1", 1, QuestionType.FUNDAMENTAL, "BASIC", "Java 基础", "接口与抽象类的区别？")),
                java.util.Map.of(), Instant.parse("2026-10-25T10:00:00Z"));
    }

    @Test
    void saveThenFindReturnsSameSession() {
        InterviewSession saved = session(UUID.randomUUID().toString());
        store.saveSession(saved);

        assertThat(store.findSession(saved.id())).contains(saved);
    }

    @Test
    void saveReplacesPreviousSessionWithSameId() {
        String id = UUID.randomUUID().toString();
        store.saveSession(session(id));

        InterviewSession answered = session(id)
                .withAnswer(new Answer("q1", "接口多继承……", Instant.parse("2026-10-25T10:05:00Z")))
                .withStatus(SessionStatus.EVALUATED);
        store.saveSession(answered);

        assertThat(store.findSession(id)).contains(answered);
        assertThat(store.findSession(id).orElseThrow().status()).isEqualTo(SessionStatus.EVALUATED);
    }

    @Test
    void findUnknownIdReturnsEmpty() {
        assertThat(store.findSession(UUID.randomUUID().toString())).isEmpty();
        assertThat(store.findSession("not-a-uuid")).isEmpty();
    }

    @Test
    void reportStoredIndependentlyOfSession() {
        String id = UUID.randomUUID().toString();
        EvaluationReport report = new EvaluationReport(id,
                List.of(), 3.5, "总体扎实", List.of(), List.of());
        store.saveReport(report);

        assertThat(store.findReport(id)).contains(report);
        assertThat(store.findSession(id)).isEmpty();
    }

    @Test
    void nullArgumentsFailFast() {
        assertThatThrownBy(() -> store.saveSession(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.findSession(null)).isInstanceOf(NullPointerException.class);
    }
}
