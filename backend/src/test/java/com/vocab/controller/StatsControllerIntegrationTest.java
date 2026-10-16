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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统计接口 HTTP 集成测试（阶段 9 闸口，契约 §7）。
 *
 * <p>与 {@code StatsServiceImplTest} 的分工：
 * <ul>
 *   <li>Service 单测证明<b>补零/分档/掌握度</b>的计算正确；</li>
 *   <li>本类证明<b>契约形状</b>正确——URL、query 参数名、响应字段名、
 *       日期字符串格式（{@code yyyy-MM-dd}，不带 {@code T}）、
 *       数组长度、鉴权失败时的 HTTP 401 + 1006。</li>
 * </ul>
 *
 * <p>尤其锁定「日期序列化格式」：{@code JacksonConfig} 若失效，
 * {@code LocalDate} 会输出 {@code 2026-10-16T00:00:00} 或 ISO 数组，
 * 前端日历格按字符串比较日期就会全部落空。这是纯 Service 单测
 * <b>看不到</b>的缺陷（Service 里还是 {@code LocalDate} 对象）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(TestClockConfig.class)
class StatsControllerIntegrationTest {

    private static final String HEATMAP = "/api/client/stats/heatmap";
    private static final String MASTERY = "/api/client/stats/mastery";

    private static final LocalDate TODAY = FixedClock.DEFAULT_DATE;

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
    private StudyRecordMapper studyRecordMapper;

    @Autowired
    private MistakeEntryMapper mistakeEntryMapper;

    private User user;
    private WordBook book;

    @BeforeEach
    void setUp() {
        FixedClock.reset();

        user = new User();
        user.setUsername("statsh_" + System.nanoTime());
        user.setPasswordHash("$2a$10$fakeHash");
        user.setNickname("统计接口测试");
        user.setAvatar(Constants.DEFAULT_AVATAR);
        user.setRole(Constants.ROLE_USER);
        user.setStatus(Constants.STATUS_ENABLED);
        userMapper.insert(user);

        book = new WordBook();
        book.setName("四级核心词汇");
        book.setCategory("CET4");
        book.setWordCount(0);
        wordBookMapper.insert(book);
    }

    @AfterEach
    void tearDown() {
        FixedClock.reset();
    }

    // ===============================================================
    //  §7.1 热力图
    // ===============================================================

    @Test
    @DisplayName("§7.1 GET /stats/heatmap 契约形状：data.days 长度 84、日期为 yyyy-MM-dd、含 level")
    void heatmapShapeMatchesContract() throws Exception {
        seedCheckedInWords(TODAY, 5);

        mockMvc.perform(get(HEATMAP).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.days.length()").value(84))
                .andExpect(jsonPath("$.data.days[0].date").value("2026-07-25"))
                .andExpect(jsonPath("$.data.days[83].date").value("2026-10-16"))
                .andExpect(jsonPath("$.data.days[83].wordCount").value(5))
                .andExpect(jsonPath("$.data.days[83].level").value(1))
                .andExpect(jsonPath("$.data.days[0].level").value(0));
    }

    @Test
    @DisplayName("§7.1 日期必须是裸 'yyyy-MM-dd'（不带 T）——JacksonConfig 失效会在此暴露")
    void heatmapDateIsPlainDateString() throws Exception {
        String body = mockMvc.perform(get(HEATMAP).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode date = objectMapper.readTree(body).at("/data/days/83/date");
        assertTrue(date.isTextual(), "date 必须是 JSON 字符串");
        assertFalse(date.asText().contains("T"), "date 不得含 T 分隔符，实际：" + date.asText());
        assertEquals("2026-10-16", date.asText());
    }

    @Test
    @DisplayName("§7.1 weeks=1 → data.days 长度 7；weeks 非法值不报错，回落 84")
    void heatmapHonoursWeeksParamAndFallsBack() throws Exception {
        mockMvc.perform(get(HEATMAP).param("weeks", "1")
                        .header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(jsonPath("$.data.days.length()").value(7));

        mockMvc.perform(get(HEATMAP).param("weeks", "0")
                        .header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.days.length()").value(84));
    }

    @Test
    @DisplayName("§7.1 新用户无数据 → HTTP 200 + code 0 + 84 个 level=0 的点（空态非错误）")
    void heatmapOnEmptyDataIsNotAnError() throws Exception {
        mockMvc.perform(get(HEATMAP).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.days.length()").value(84))
                .andExpect(jsonPath("$.data.days[83].wordCount").value(0))
                .andExpect(jsonPath("$.data.days[83].level").value(0));
    }

    // ===============================================================
    //  §7.2 掌握度曲线
    // ===============================================================

    @Test
    @DisplayName("§7.2 GET /stats/mastery 契约形状：data.points 长度 30、字段名齐全")
    void masteryShapeMatchesContract() throws Exception {
        seedStudyRecord(TODAY, 501L, Constants.STUDY_TYPE_STUDY);

        mockMvc.perform(get(MASTERY).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.points.length()").value(30))
                .andExpect(jsonPath("$.data.points[0].date").value("2026-09-17"))
                .andExpect(jsonPath("$.data.points[29].date").value("2026-10-16"))
                .andExpect(jsonPath("$.data.points[29].learnedCount").value(1))
                .andExpect(jsonPath("$.data.points[29].reviewCount").value(0))
                .andExpect(jsonPath("$.data.points[29].mistakeCount").value(0))
                .andExpect(jsonPath("$.data.points[29].masteryRate").value(100));
    }

    @Test
    @DisplayName("§7.2 masteryRate 与错题掌握状态一致（2 已掌握 / 4 总数 = 50）")
    void masteryRateReflectsMistakeMastery() throws Exception {
        seedStudyRecord(TODAY, 601L, Constants.STUDY_TYPE_STUDY);
        seedMistake(601L, true);
        seedMistake(602L, true);
        seedMistake(603L, false);
        seedMistake(604L, false);

        mockMvc.perform(get(MASTERY).param("days", "7")
                        .header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(jsonPath("$.data.points.length()").value(7))
                .andExpect(jsonPath("$.data.points[6].masteryRate").value(50));
    }

    @Test
    @DisplayName("§7.2 days=7 → 7 个点；days 非法值回落 30")
    void masteryHonoursDaysParamAndFallsBack() throws Exception {
        mockMvc.perform(get(MASTERY).param("days", "7")
                        .header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(jsonPath("$.data.points.length()").value(7));

        mockMvc.perform(get(MASTERY).param("days", "-5")
                        .header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.points.length()").value(30));
    }

    // ===============================================================
    //  鉴权
    // ===============================================================

    @Test
    @DisplayName("无 token 访问统计接口 → HTTP 401 + code 1006（响应体仍是 Result 结构）")
    void statsRequiresToken() throws Exception {
        mockMvc.perform(get(HEATMAP))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1006"));

        mockMvc.perform(get(MASTERY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1006"));
    }

    @Test
    @DisplayName("非数字 weeks → HTTP 200 + 1005（参数类型不匹配走业务错误码，不是 400）")
    void nonNumericWeeksIsParamInvalid() throws Exception {
        mockMvc.perform(get(HEATMAP).param("weeks", "abc")
                        .header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));
    }

    // ===============================================================
    //  夹具
    // ===============================================================

    private void seedCheckedInWords(LocalDate date, int count) {
        for (int i = 0; i < count; i++) {
            Long wordId = insertWord("w" + date + "_" + i);
            StudyRecord record = new StudyRecord();
            record.setUserId(user.getId());
            record.setBookId(book.getId());
            record.setWordId(wordId);
            record.setStudyType(Constants.STUDY_TYPE_STUDY);
            record.setStudyDate(date);
            record.setCheckInDate(date);
            record.setStreakDays(1);
            studyRecordMapper.insert(record);
        }
    }

    private void seedStudyRecord(LocalDate date, Long wordId, String studyType) {
        StudyRecord record = new StudyRecord();
        record.setUserId(user.getId());
        record.setBookId(book.getId());
        record.setWordId(wordId);
        record.setStudyType(studyType);
        record.setStudyDate(date);
        record.setStreakDays(0);
        studyRecordMapper.insert(record);
    }

    private void seedMistake(Long wordId, boolean mastered) {
        MistakeEntry entry = new MistakeEntry();
        entry.setUserId(user.getId());
        entry.setWordId(wordId);
        entry.setSource(Constants.SOURCE_STUDY);
        entry.setWrongCount(1);
        entry.setMastered(mastered ? 1 : 0);
        mistakeEntryMapper.insert(entry);
    }

    private Long insertWord(String text) {
        Word word = new Word();
        word.setBookId(book.getId());
        word.setWord(text);
        word.setPhonetic("p");
        word.setTranslation("释义");
        word.setExample("example");
        wordMapper.insert(word);
        return word.getId();
    }

    private String token() {
        return "Bearer " + jwtUtil.generateToken(user.getId(), user.getRole());
    }
}
