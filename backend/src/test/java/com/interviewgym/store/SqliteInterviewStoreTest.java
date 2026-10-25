package com.interviewgym.store;

import com.interviewgym.core.Answer;
import com.interviewgym.core.EvaluationReport;
import com.interviewgym.core.InterviewSession;
import com.interviewgym.core.Question;
import com.interviewgym.core.QuestionType;
import com.interviewgym.core.SessionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteInterviewStoreTest {

    private static final String LONG_JD = "J".repeat(80);

    @TempDir
    Path tempDir;

    private InterviewSession session(String id) {
        return new InterviewSession(id, "Java 后端 JD", SessionStatus.ACTIVE,
                List.of(new Question("q1", 1, QuestionType.FUNDAMENTAL, "BASIC", "Java 基础", "接口与抽象类的区别？")),
                Map.of(), Instant.parse("2026-10-25T10:00:00Z"));
    }

    @Test
    void saveThenFindReturnsSameSession() {
        SqliteInterviewStore store = open();
        InterviewSession saved = session(UUID.randomUUID().toString());
        store.saveSession(saved);

        assertThat(store.findSession(saved.id())).contains(saved);
    }

    @Test
    void saveReplacesPreviousSessionWithSameId() {
        SqliteInterviewStore store = open();
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
        SqliteInterviewStore store = open();
        assertThat(store.findSession(UUID.randomUUID().toString())).isEmpty();
        assertThat(store.findSession("not-a-uuid")).isEmpty();
    }

    @Test
    void reportStoredIndependentlyOfSession() {
        SqliteInterviewStore store = open();
        String id = UUID.randomUUID().toString();
        EvaluationReport report = new EvaluationReport(id,
                List.of(), 3.5, "总体扎实", List.of(), List.of());
        store.saveReport(report);

        assertThat(store.findReport(id)).contains(report);
        assertThat(store.findSession(id)).isEmpty();
    }

    @Test
    void nullArgumentsFailFast() {
        SqliteInterviewStore store = open();
        assertThatThrownBy(() -> store.saveSession(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.findSession(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void updateSessionAppliesUpdaterAndStoresResult() {
        SqliteInterviewStore store = open();
        InterviewSession saved = session(UUID.randomUUID().toString());
        store.saveSession(saved);

        Optional<InterviewSession> updated = store.updateSession(saved.id(), s ->
                s.withAnswer(new Answer("q1", "新答案", Instant.parse("2026-10-25T11:00:00Z"))));

        assertThat(updated).hasValueSatisfying(s -> assertThat(s.answers()).containsKey("q1"));
        assertThat(store.findSession(saved.id())).contains(updated.orElseThrow());
    }

    @Test
    void updateSessionReturnsEmptyForUnknownId() {
        SqliteInterviewStore store = open();
        assertThat(store.updateSession(UUID.randomUUID().toString(), s -> s)).isEmpty();
        assertThat(store.updateSession("not-a-uuid", s -> s)).isEmpty();
    }

    @Test
    void updateSessionLeavesEntryUnchangedWhenUpdaterThrows() {
        SqliteInterviewStore store = open();
        InterviewSession saved = session(UUID.randomUUID().toString());
        store.saveSession(saved);

        assertThatThrownBy(() -> store.updateSession(saved.id(), s -> {
            throw new IllegalStateException("updater 失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(store.findSession(saved.id())).contains(saved);
    }

    @Test
    void updateSessionRetriesWhenRowChangedUnderneath() {
        SqliteInterviewStore store = open();
        // 同一库文件的第二个句柄，模拟并发写者
        SqliteInterviewStore rival = open();
        InterviewSession saved = session(UUID.randomUUID().toString());
        store.saveSession(saved);
        AtomicBoolean raced = new AtomicBoolean();

        Optional<InterviewSession> result = store.updateSession(saved.id(), s -> {
            // 首轮读后、写前，被并发写顶掉 version → 本次 UPDATE 落空 → 必须重读重试
            if (raced.compareAndSet(false, true)) {
                rival.saveSession(s.withAnswer(new Answer("q1", "并发先到", Instant.now())));
            }
            return s.withAnswer(new Answer("q1", "重试后写", Instant.now()));
        });

        assertThat(result).isPresent();
        assertThat(store.findSession(saved.id())).hasValueSatisfying(s ->
                assertThat(s.answers().get("q1").text()).isEqualTo("重试后写"));
    }

    @Test
    void concurrentUpdatesBothLand() throws Exception {
        SqliteInterviewStore store = open();
        List<Question> questions = List.of(
                new Question("q1", 1, QuestionType.FUNDAMENTAL, "BASIC", "KP1", "题一"),
                new Question("q2", 2, QuestionType.FUNDAMENTAL, "BASIC", "KP2", "题二"));
        InterviewSession saved = new InterviewSession(UUID.randomUUID().toString(), "JD", SessionStatus.ACTIVE,
                questions, Map.of(), Instant.now());
        store.saveSession(saved);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Optional<InterviewSession>>> futures = List.of(
                    pool.submit(() -> updateWhenReady(store, barrier, saved.id(), "q1", "答案一")),
                    pool.submit(() -> updateWhenReady(store, barrier, saved.id(), "q2", "答案二")));
            for (Future<Optional<InterviewSession>> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS)).isPresent();
            }
        } finally {
            pool.shutdownNow();
        }

        // v1 语义（AI-FAILURES #6）：并发提交的两个答案都落地，互不覆盖
        assertThat(store.findSession(saved.id())).hasValueSatisfying(s -> {
            assertThat(s.answers().get("q1").text()).isEqualTo("答案一");
            assertThat(s.answers().get("q2").text()).isEqualTo("答案二");
        });
    }

    @Test
    void dataSurvivesReopenForCrossDayUse() {
        Path db = tempDir.resolve("reopen.db");
        SqliteInterviewStore first = SqliteTestSupport.open(db);
        InterviewSession saved = session(UUID.randomUUID().toString());
        first.saveSession(saved);
        first.saveReport(new EvaluationReport(saved.id(), List.of(), 4.0, "总评", List.of(), List.of()));

        // 模拟跨天：新进程新连接打开同一库文件
        SqliteInterviewStore reopened = SqliteTestSupport.open(db);
        assertThat(reopened.findSession(saved.id())).contains(saved);
        assertThat(reopened.findReport(saved.id())).isPresent();
        assertThat(reopened.listSessions()).hasSize(1);
    }

    @Test
    void listSessionsReturnsNewestFirstWithExcerptAndScore() {
        SqliteInterviewStore store = open();
        String id1 = UUID.randomUUID().toString();
        String id2 = UUID.randomUUID().toString();
        String id3 = UUID.randomUUID().toString();
        store.saveSession(new InterviewSession(id1, LONG_JD, SessionStatus.EVALUATED,
                session(id1).questions(), Map.of(), Instant.parse("2026-10-24T10:00:00Z")));
        store.saveSession(new InterviewSession(id2, "短 JD", SessionStatus.ACTIVE,
                session(id2).questions(), Map.of(), Instant.parse("2026-10-25T09:00:00Z"), true));
        store.saveSession(session(id3));
        store.saveReport(new EvaluationReport(id1, List.of(), 3.3, "总评", List.of(), List.of()));

        List<com.interviewgym.core.SessionSummary> summaries = store.listSessions();

        assertThat(summaries).extracting("id").containsExactly(id3, id2, id1);
        assertThat(summaries.get(2).jdExcerpt()).hasSize(SqliteInterviewStore.JD_EXCERPT_MAX + 1).endsWith("…");
        assertThat(summaries.get(1).resumeAssisted()).isTrue();
        assertThat(summaries.get(1).averageScore()).isNull();
        assertThat(summaries.get(2).averageScore()).isEqualTo(3.3);
        assertThat(summaries.get(2).status()).isEqualTo(SessionStatus.EVALUATED);
    }

    private SqliteInterviewStore open() {
        return SqliteTestSupport.open(tempDir.resolve("store.db"));
    }

    private static Optional<InterviewSession> updateWhenReady(SqliteInterviewStore store, CyclicBarrier barrier,
                                                              String sessionId, String questionId, String text)
            throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        return store.updateSession(sessionId, s ->
                s.withAnswer(new Answer(questionId, text, Instant.now())));
    }
}
