package com.vocab.service;

import com.vocab.ai.AiMetrics;
import com.vocab.ai.AiServiceClient;
import com.vocab.common.BizException;
import com.vocab.common.Constants;
import com.vocab.common.ErrorCode;
import com.vocab.dto.response.AiExampleResponse;
import com.vocab.dto.response.AiExplainResponse;
import com.vocab.dto.response.AiReportResponse;
import com.vocab.entity.MistakeEntry;
import com.vocab.entity.StudyRecord;
import com.vocab.entity.User;
import com.vocab.entity.Word;
import com.vocab.entity.WordBook;
import com.vocab.mapper.MistakeEntryMapper;
import com.vocab.mapper.StatsMapper;
import com.vocab.mapper.StudyRecordMapper;
import com.vocab.mapper.UserMapper;
import com.vocab.mapper.WordBookMapper;
import com.vocab.mapper.WordMapper;
import com.vocab.service.impl.AiServiceImpl;
import com.vocab.testsupport.FixedClock;
import com.vocab.testsupport.SilentHttpServer;
import com.vocab.testsupport.StubAiServer;
import com.vocab.testsupport.TestClockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 中转与降级集成测试（阶段 8 闸口 G5，契约 §6）。
 *
 * <p><b>本类刻意不用 Mock 伪造 AI 故障。</b>G5 的一等命题是
 * 「ai-service 未启动/不响应时，降级路径全绿」。若用 Mock 让
 * {@code RestTemplate} 抛一个手工构造的异常，验证的只是「我对超时的想象」，
 * 而不是真实网络下的行为。因此对端全部是真的：
 * <ul>
 *   <li><b>端口无人监听</b> → 真实连接被拒 → 5002；</li>
 *   <li><b>{@link SilentHttpServer} 收下连接但永不响应</b> → 真实
 *       {@code SocketTimeoutException} → 5001；</li>
 *   <li><b>{@link StubAiServer} 真返回 JSON</b> → 验证请求/响应字段名与契约 §9 一致。</li>
 * </ul>
 *
 * <p><b>为什么在测试里手工 new {@code AiServiceImpl}</b>：
 * {@code @DynamicPropertySource} 的值在<b>上下文首次创建时</b>解析一次，
 * 之后 Spring 会复用缓存的上下文——所以「在 {@code @BeforeEach} 里改静态
 * baseUrl 字段」<b>不会</b>生效（本类最早就是这么写错的：4 个用例全部打到了
 * 同一个端口，超时用例假失败）。改为按用例构造注入的 {@code AiServiceClient}，
 * 每个用例都能指向自己想用的对端，且不必为每种故障各起一个 Spring 上下文。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestClockConfig.class)
class AiServiceImplTest {

    /** 无人监听的端口：连接立即被拒，等价于「ai-service 未启动」。 */
    private static final String DOWN_URL = "http://127.0.0.1:1";

    @Autowired
    private AiMetrics aiMetrics;

    @Autowired
    private Clock clock;

    @Autowired
    private StatsMapper statsMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private WordBookMapper wordBookMapper;

    @Autowired
    private WordMapper wordMapper;

    @Autowired
    private MistakeEntryMapper mistakeEntryMapper;

    @Autowired
    private StudyRecordMapper studyRecordMapper;

    private User user;
    private WordBook book;
    private Word word;

    @BeforeEach
    void setUp() {
        FixedClock.reset();

        user = new User();
        user.setUsername("ai_" + System.nanoTime());
        user.setPasswordHash("$2a$10$fakeHash");
        user.setNickname("AI测试");
        user.setAvatar(Constants.DEFAULT_AVATAR);
        user.setRole(Constants.ROLE_USER);
        user.setStatus(Constants.STATUS_ENABLED);
        userMapper.insert(user);

        book = new WordBook();
        book.setName("四级核心词汇");
        book.setCategory("CET4");
        book.setWordCount(0);
        wordBookMapper.insert(book);

        word = new Word();
        word.setBookId(book.getId());
        word.setWord("abandon");
        word.setPhonetic("əˈbændən");
        word.setTranslation("v. 放弃");
        word.setExample("He decided to abandon the plan.");
        wordMapper.insert(word);
    }

    @AfterEach
    void tearDown() {
        FixedClock.reset();
    }

    /**
     * 按用例指定的对端与超时装配被测服务。
     *
     * @param baseUrl      AI 服务地址
     * @param timeoutMs    三个接口的统一读超时（测试里压到几百毫秒）
     */
    private AiService service(String baseUrl, long timeoutMs) {
        AiServiceClient client = new AiServiceClient(
                new RestTemplateBuilder(), baseUrl, timeoutMs, timeoutMs, timeoutMs);
        return new AiServiceImpl(wordMapper, wordBookMapper, mistakeEntryMapper,
                statsMapper, client, aiMetrics, clock);
    }

    // ===============================================================
    //  §6.1 example：降级【不抛异常】
    // ===============================================================

    @Test
    @DisplayName("§6.1 ai-service 未启动（连接被拒）→ example 仍返回 degraded:true + 库内例句，不抛异常")
    void exampleDegradesWithoutThrowingWhenServiceDown() {
        AiExampleResponse response = service(DOWN_URL, 800).example(user.getId(), word.getId());

        assertNotNull(response, "降级必须返回对象，而不是抛异常");
        assertTrue(response.isDegraded(), "降级标志必须为 true");
        assertEquals(word.getId(), response.getWordId());
        assertEquals(word.getExample(), response.getExample(), "example 应回退到库内例句");
        assertEquals("", response.getMnemonic(), "mnemonic 降级时为空串");
    }

    @Test
    @DisplayName("§6.1 ai-service 挂起不响应 → example 真实读超时仍 degraded:true，绝不抛 5001")
    void exampleDegradesOnRealTimeoutWithoutThrowing() throws Exception {
        try (SilentHttpServer server = new SilentHttpServer()) {
            AiExampleResponse response =
                    service(server.baseUrl(), 500).example(user.getId(), word.getId());

            assertNotNull(response, "超时后必须返回降级对象，绝不能抛 5001");
            assertTrue(response.isDegraded(), "超时降级 degraded 必须为 true");
            assertEquals(word.getExample(), response.getExample());
        }
    }

    @Test
    @DisplayName("§6.1 ai-service 正常返回 → degraded:false 且采用 AI 内容")
    void exampleUsesAiContentOnSuccess() throws Exception {
        String json = "{\"example\":\"AI example\",\"exampleTranslation\":\"AI 译文\","
                + "\"mnemonic\":\"联想记忆\"}";
        try (StubAiServer server = new StubAiServer(Map.of("/ai/v1/example", json), 200)) {
            AiExampleResponse response =
                    service(server.baseUrl(), 5000).example(user.getId(), word.getId());

            assertFalse(response.isDegraded(), "成功时 degraded 必须是 false");
            assertEquals("AI example", response.getExample());
            assertEquals("AI 译文", response.getExampleTranslation());
            assertEquals("联想记忆", response.getMnemonic());
        }
    }

    @Test
    @DisplayName("§6.1 单词不存在 → 1005（且不调 AI）")
    void exampleRejectsUnknownWord() {
        BizException e = assertThrows(BizException.class,
                () -> service(DOWN_URL, 800).example(user.getId(), 99999999L));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), e.getCode());
    }

    @Test
    @DisplayName("§6.1 降级计入 AiMetrics（degraded +1 且事件入队，含真实超时原因）")
    void exampleDegradeIsCounted() {
        long before = aiMetrics.stat(AiMetrics.FEATURE_EXAMPLE).getDegraded();

        service(DOWN_URL, 800).example(user.getId(), word.getId());

        assertEquals(before + 1, aiMetrics.stat(AiMetrics.FEATURE_EXAMPLE).getDegraded(),
                "降级计数应 +1");
        assertFalse(aiMetrics.recentEvents().isEmpty(), "降级事件应入队");
        assertEquals(AiMetrics.FEATURE_EXAMPLE, aiMetrics.recentEvents().get(0).getFeature());
    }

    // ===============================================================
    //  §6.2 explain：降级抛 5001/5002
    // ===============================================================

    @Test
    @DisplayName("§6.2 ai-service 未启动（连接被拒）→ explain 抛 5002，而非 5001")
    void explainThrows5002WhenServiceDown() {
        MistakeEntry entry = newMistake();
        BizException e = assertThrows(BizException.class,
                () -> service(DOWN_URL, 800).explain(user.getId(), entry.getId()));
        assertEquals(ErrorCode.AI_ERROR.getCode(), e.getCode(),
                "连接被拒是「服务异常」5002；把它当超时 5001 会与契约 §6.2 不符");
    }

    @Test
    @DisplayName("§6.2 ai-service 挂起 → explain 真实读超时抛 5001（非 5002）")
    void explainThrows5001OnRealTimeout() throws Exception {
        MistakeEntry entry = newMistake();
        try (SilentHttpServer server = new SilentHttpServer()) {
            BizException e = assertThrows(BizException.class,
                    () -> service(server.baseUrl(), 500).explain(user.getId(), entry.getId()));
            assertEquals(ErrorCode.AI_TIMEOUT.getCode(), e.getCode(),
                    "真实读超时必须映射为 5001，而不是 5002");
        }
    }

    @Test
    @DisplayName("§6.2 错题不存在 → 4001（不调 AI）")
    void explainRejectsUnknownMistake() {
        BizException e = assertThrows(BizException.class,
                () -> service(DOWN_URL, 800).explain(user.getId(), 99999999L));
        assertEquals(ErrorCode.MISTAKE_NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    @DisplayName("§6.2 他人的错题 → 4001（不可越权）")
    void explainRejectsOtherUsersMistake() {
        MistakeEntry other = newMistake();
        User another = newUser();
        BizException e = assertThrows(BizException.class,
                () -> service(DOWN_URL, 800).explain(another.getId(), other.getId()));
        assertEquals(ErrorCode.MISTAKE_NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    @DisplayName("§6.2 ai-service 正常返回 → degraded:false 且采用 AI 内容")
    void explainUsesAiContentOnSuccess() throws Exception {
        MistakeEntry entry = newMistake();
        String json = "{\"explanation\":\"讲解内容\",\"advice\":\"建议内容\"}";
        try (StubAiServer server = new StubAiServer(Map.of("/ai/v1/explain", json), 200)) {
            AiExplainResponse response =
                    service(server.baseUrl(), 5000).explain(user.getId(), entry.getId());

            assertFalse(response.isDegraded());
            assertEquals(entry.getId(), response.getMistakeId());
            assertEquals("讲解内容", response.getExplanation());
            assertEquals("建议内容", response.getAdvice());
        }
    }

    // ===============================================================
    //  §6.3 report：前置校验 + 降级
    // ===============================================================

    @Test
    @DisplayName("§6.3 学习天数 < 3 → 5003 且【不发起 HTTP 调用】")
    void reportRejectsInsufficientDataWithoutCallingAi() throws Exception {
        seedCheckInDays(2);

        String json = "{\"overview\":\"o\",\"weakness\":\"w\",\"suggestion\":\"s\"}";
        try (StubAiServer server = new StubAiServer(Map.of("/ai/v1/report", json), 200)) {
            long totalBefore = aiMetrics.stat(AiMetrics.FEATURE_REPORT).getTotal();

            BizException e = assertThrows(BizException.class,
                    () -> service(server.baseUrl(), 5000).report(user.getId()));

            assertEquals(ErrorCode.AI_DATA_INSUFFICIENT.getCode(), e.getCode());
            assertEquals(totalBefore, aiMetrics.stat(AiMetrics.FEATURE_REPORT).getTotal(),
                    "5003 必须在校验阶段返回，绝不能先发起 AI 调用（total 不应变化）");
        }
    }

    @Test
    @DisplayName("§6.3 学习天数 >= 3 且 ai-service 未启动 → 5002")
    void reportThrows5002WhenServiceDown() {
        seedCheckInDays(3);
        BizException e = assertThrows(BizException.class,
                () -> service(DOWN_URL, 800).report(user.getId()));
        assertEquals(ErrorCode.AI_ERROR.getCode(), e.getCode());
    }

    @Test
    @DisplayName("§6.3 学习天数 >= 3 且正常返回 → 三段报告 + generatedAt 取自注入的 Clock")
    void reportReturnsContentOnSuccess() throws Exception {
        seedCheckInDays(3);
        String json = "{\"overview\":\"总览\",\"weakness\":\"薄弱\",\"suggestion\":\"建议\"}";
        try (StubAiServer server = new StubAiServer(Map.of("/ai/v1/report", json), 200)) {
            AiReportResponse response = service(server.baseUrl(), 5000).report(user.getId());

            assertFalse(response.isDegraded());
            assertEquals("总览", response.getReport().getOverview());
            assertEquals("薄弱", response.getReport().getWeakness());
            assertEquals("建议", response.getReport().getSuggestion());
            assertNotNull(response.getGeneratedAt(), "generatedAt 应有值");
            assertEquals(FixedClock.DEFAULT_DATE, response.getGeneratedAt().toLocalDate(),
                    "generatedAt 必须取自注入的 Clock（固定日期），不得用系统时钟");
        }
    }

    // ===============================================================
    //  夹具
    // ===============================================================

    private MistakeEntry newMistake() {
        MistakeEntry entry = new MistakeEntry();
        entry.setUserId(user.getId());
        entry.setWordId(word.getId());
        entry.setSource(Constants.SOURCE_STUDY);
        entry.setWrongCount(2);
        entry.setMastered(0);
        mistakeEntryMapper.insert(entry);
        return entry;
    }

    private User newUser() {
        User another = new User();
        another.setUsername("ai2_" + System.nanoTime());
        another.setPasswordHash("$2a$10$fakeHash");
        another.setNickname("另一个");
        another.setAvatar(Constants.DEFAULT_AVATAR);
        another.setRole(Constants.ROLE_USER);
        another.setStatus(Constants.STATUS_ENABLED);
        userMapper.insert(another);
        return another;
    }

    /**
     * 造 {@code days} 个不同日期的打卡记录，使「学习天数」达到阈值。
     *
     * <p>直接写 {@code study_record} 而不走打卡流程：本用例只关心统计口径，
     * 不必重复验证打卡逻辑。
     *
     * <p>刻意<b>不</b>写 {@code word_progress}：该表唯一键是
     * {@code (user_id, word_id)}，循环插同一天的多行会撞唯一索引；
     * 而 {@code countCheckInDays} 只读 {@code study_record}，无需进度行。
     */
    private void seedCheckInDays(int days) {
        LocalDate today = FixedClock.DEFAULT_DATE;
        for (int i = 0; i < days; i++) {
            LocalDate date = today.minusDays(i);

            StudyRecord record = new StudyRecord();
            record.setUserId(user.getId());
            record.setBookId(book.getId());
            record.setWordId(word.getId());
            record.setStudyType(Constants.STUDY_TYPE_STUDY);
            record.setStudyDate(date);
            record.setCheckInDate(date);
            record.setStreakDays(i + 1);
            studyRecordMapper.insert(record);
        }
    }
}
