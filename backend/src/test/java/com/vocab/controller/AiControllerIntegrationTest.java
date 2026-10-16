package com.vocab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocab.common.Constants;
import com.vocab.config.JwtUtil;
import com.vocab.entity.MistakeEntry;
import com.vocab.entity.StudyRecord;
import com.vocab.entity.User;
import com.vocab.entity.Word;
import com.vocab.entity.WordBook;
import com.vocab.mapper.MistakeEntryMapper;
import com.vocab.mapper.StudyRecordMapper;
import com.vocab.mapper.UserMapper;
import com.vocab.mapper.WordBookMapper;
import com.vocab.mapper.WordMapper;
import com.vocab.testsupport.FixedClock;
import com.vocab.testsupport.TestClockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 接口 HTTP 契约测试（阶段 8 闸口 G5，契约 §6）。
 *
 * <p>与 {@code AiServiceImplTest} 的分工：Service 单测证明降级<b>决策</b>正确；
 * 本类证明降级结果经 {@code Result} 包装后<b>HTTP 契约形状</b>正确——
 * 尤其 {@code example} 降级时必须 HTTP 200 + {@code code:"0"}（<b>不是</b> 5001），
 * 以及错误路径的 HTTP 状态码仍是 200。
 *
 * <p>{@code ai.service.base-url} 指向一个<b>无人监听的端口</b>，
 * 即「ai-service 未启动」——这正是 G5 的一等约束场景。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(TestClockConfig.class)
class AiControllerIntegrationTest {

    private static final String EXAMPLE = "/api/client/ai/example";
    private static final String EXPLAIN = "/api/client/ai/explain";
    private static final String REPORT = "/api/client/ai/report";

    /** 127.0.0.1:1 无监听 → 连接立即被拒，等价于「ai-service 未启动」。 */
    @DynamicPropertySource
    static void aiProperties(DynamicPropertyRegistry registry) {
        registry.add("ai.service.base-url", () -> "http://127.0.0.1:1");
        registry.add("ai.service.example-timeout-ms", () -> 500L);
        registry.add("ai.service.explain-timeout-ms", () -> 500L);
        registry.add("ai.service.report-timeout-ms", () -> 500L);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

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
    private String token;

    @BeforeEach
    void setUp() {
        FixedClock.reset();

        user = new User();
        user.setUsername("aic_" + System.nanoTime());
        user.setPasswordHash("$2a$10$fakeHash");
        user.setNickname("AI契约");
        user.setAvatar(Constants.DEFAULT_AVATAR);
        user.setRole(Constants.ROLE_USER);
        user.setStatus(Constants.STATUS_ENABLED);
        userMapper.insert(user);
        token = jwtUtil.generateToken(user.getId(), Constants.ROLE_USER);

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

    // ---------------------------------------------------------------

    @Test
    @DisplayName("§6.1 ai-service 未启动 → HTTP 200 + code:0 + degraded:true + 库内例句（不是 5001）")
    void exampleDegradesInHttpContract() throws Exception {
        MvcResult result = mockMvc.perform(post(EXAMPLE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"wordId\":" + word.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.degraded").value(true))
                .andExpect(jsonPath("$.data.example").value(word.getExample()))
                .andExpect(jsonPath("$.data.mnemonic").value(""))
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("0", node.get("code").asText(),
                "降级绝不能返回 5001/5002——前端按 degraded 显示黄条，不当作错误");
    }

    @Test
    @DisplayName("§6.2 ai-service 未启动 → explain HTTP 200（业务失败）+ code:5002")
    void explainFailsWith5002InHttpContract() throws Exception {
        MistakeEntry entry = newMistake();

        mockMvc.perform(post(EXPLAIN)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mistakeId\":" + entry.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("5002"));
    }

    @Test
    @DisplayName("§6.3 学习天数 < 3 → 5003（HTTP 200）")
    void reportRejectsInsufficientData() throws Exception {
        mockMvc.perform(post(REPORT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("5003"));
    }

    @Test
    @DisplayName("§6.3 学习天数 >= 3 且 ai-service 未启动 → 5002（HTTP 200）")
    void reportFailsWith5002WhenServiceDown() throws Exception {
        seedCheckInDays(3);

        mockMvc.perform(post(REPORT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("5002"));
    }

    @Test
    @DisplayName("§6 无 token 访问 AI 接口 → HTTP 401 + 1006")
    void aiRequiresAuth() throws Exception {
        mockMvc.perform(post(EXAMPLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"wordId\":" + word.getId() + "}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1006"));
    }

    @Test
    @DisplayName("§6.1 缺 wordId → 1005（@Valid 生效）")
    void exampleRequiresWordId() throws Exception {
        mockMvc.perform(post(EXAMPLE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));
    }

    // ---------------------------------------------------------------

    private MistakeEntry newMistake() {
        MistakeEntry entry = new MistakeEntry();
        entry.setUserId(user.getId());
        entry.setWordId(word.getId());
        entry.setSource(Constants.SOURCE_STUDY);
        entry.setWrongCount(1);
        entry.setMastered(0);
        mistakeEntryMapper.insert(entry);
        return entry;
    }

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
