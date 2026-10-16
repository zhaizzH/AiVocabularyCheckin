package com.vocab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocab.common.Constants;
import com.vocab.config.JwtUtil;
import com.vocab.entity.User;
import com.vocab.entity.UserWordBook;
import com.vocab.entity.Word;
import com.vocab.entity.WordBook;
import com.vocab.entity.WordProgress;
import com.vocab.mapper.UserMapper;
import com.vocab.mapper.UserWordBookMapper;
import com.vocab.mapper.WordBookMapper;
import com.vocab.mapper.WordMapper;
import com.vocab.mapper.WordProgressMapper;
import com.vocab.testsupport.FixedClock;
import com.vocab.testsupport.TestClockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 学习闭环 HTTP 集成测试（阶段 7 闸口 G4 的一部分，契约 §3/§4/§5）。
 *
 * <p>与 Service 层单测的分工：
 * <ul>
 *   <li>Service 单测证明<b>算法</b>正确（stage 转移、streak、去重、越权）；</li>
 *   <li>本类证明<b>契约形状</b>正确——URL、HTTP 方法、请求体字段名、
 *       响应 JSON 字段名与类型、错误码经 {@code Result} 包裹后仍为 HTTP 200。</li>
 * </ul>
 * 这两层必须分开，否则「字段名写错」这类错误会被算法测试掩盖。
 *
 * <p>尤其锁定三处易错点：
 * <ol>
 *   <li>{@code mastered} 在响应里是<b>布尔</b>（契约 §5.1）而在 query 里是 0/1；</li>
 *   <li>业务失败（3001/3002/3003/4001/4002）<b>HTTP 状态仍是 200</b>；</li>
 *   <li>分批词表 {@code words} 的元素字段名（isReview / nextReviewDate）大小写敏感。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(TestClockConfig.class)
class StudyFlowControllerIntegrationTest {

    private static final String PLAN = "/api/client/study/plan";
    private static final String STUDY_RECORD = "/api/client/study/record";
    private static final String CHECKIN = "/api/client/study/checkin";
    private static final String STREAK = "/api/client/study/streak";
    private static final String REVIEW_TODAY = "/api/client/review/today";
    private static final String REVIEW_RECORD = "/api/client/review/record";
    private static final String MISTAKES = "/api/client/mistakes";

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
    private WordProgressMapper wordProgressMapper;

    @Autowired
    private UserWordBookMapper userWordBookMapper;

    private User user;
    private WordBook book;

    @AfterEach
    void tearDown() {
        FixedClock.reset();
    }

    // ---------------------------------------------------------------
    // 夹具
    // ---------------------------------------------------------------

    private void prepareUserAndBook() {
        FixedClock.reset();
        user = newUser();
        book = newWordBook("四级核心词汇", "CET4");
        selectBook(user.getId(), book.getId());
    }

    private User newUser() {
        User u = new User();
        u.setUsername("sf_" + System.nanoTime());
        u.setPasswordHash("$2a$10$fakeHash");
        u.setNickname("闭环测试");
        u.setAvatar(Constants.DEFAULT_AVATAR);
        u.setRole(Constants.ROLE_USER);
        u.setStatus(Constants.STATUS_ENABLED);
        userMapper.insert(u);
        return u;
    }

    private WordBook newWordBook(String name, String category) {
        WordBook b = new WordBook();
        b.setName(name);
        b.setCategory(category);
        b.setWordCount(0);
        wordBookMapper.insert(b);
        return b;
    }

    private void selectBook(Long userId, Long bookId) {
        UserWordBook relation = new UserWordBook();
        relation.setUserId(userId);
        relation.setBookId(bookId);
        userWordBookMapper.insert(relation);
    }

    private List<Word> newWords(int count, String prefix) {
        List<Word> words = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Word word = new Word();
            word.setBookId(book.getId());
            word.setWord(prefix + i);
            word.setPhonetic("p");
            word.setTranslation("释义" + i);
            word.setExample("example " + i);
            wordMapper.insert(word);
            words.add(word);
        }
        return words;
    }

    private WordProgress dueProgress(Long wordId, int stage, LocalDate nextReviewDate) {
        WordProgress progress = new WordProgress();
        progress.setUserId(user.getId());
        progress.setBookId(book.getId());
        progress.setWordId(wordId);
        progress.setStage(stage);
        progress.setNextReviewDate(nextReviewDate);
        progress.setLearned(1);
        progress.setWrongCount(0);
        progress.setCreateTime(nextReviewDate.minusDays(1).atStartOfDay());
        progress.setUpdateTime(nextReviewDate.minusDays(1).atStartOfDay());
        wordProgressMapper.insert(progress);
        return progress;
    }

    private String token() {
        return "Bearer " + jwtUtil.generateToken(user.getId(), user.getRole());
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    // ---------------------------------------------------------------
    // §3.1 今日学习计划
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§3.1 契约形状：data 含 date/total/finished/checkedIn/words，词项字段齐全")
    void planShapeMatchesContract() throws Exception {
        prepareUserAndBook();
        newWords(3, "p");

        mockMvc.perform(get(PLAN).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.date").value("2026-10-16"))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.finished").value(0))
                .andExpect(jsonPath("$.data.checkedIn").value(false))
                .andExpect(jsonPath("$.data.words.length()").value(3))
                .andExpect(jsonPath("$.data.words[0].wordId").isNumber())
                .andExpect(jsonPath("$.data.words[0].bookId").value(book.getId()))
                .andExpect(jsonPath("$.data.words[0].word").isString())
                .andExpect(jsonPath("$.data.words[0].phonetic").isString())
                .andExpect(jsonPath("$.data.words[0].translation").isString())
                .andExpect(jsonPath("$.data.words[0].example").isString())
                .andExpect(jsonPath("$.data.words[0].isReview").value(false))
                .andExpect(jsonPath("$.data.words[0].learned").value(false));
    }

    @Test
    @DisplayName("§3.1 未选词书 → total=0、words=[]（不报错）")
    void planWithoutBookReturnsEmpty() throws Exception {
        FixedClock.reset();
        user = newUser();

        mockMvc.perform(get(PLAN).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.words.length()").value(0));
    }

    @Test
    @DisplayName("§0 无 token → 401 + 1006（token 无效；不是 1001）")
    void planWithoutTokenThrows1006() throws Exception {
        mockMvc.perform(get(PLAN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1006"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ---------------------------------------------------------------
    // §3.2 学习上报
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§3.2 成功：data={wordId,learned:true,addedToMistakes}")
    void studyRecordShape() throws Exception {
        prepareUserAndBook();
        List<Word> words = newWords(1, "r");

        mockMvc.perform(post(STUDY_RECORD)
                        .header(HttpHeaders.AUTHORIZATION, token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("wordId", words.get(0).getId(),
                                "bookId", book.getId(), "known", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.wordId").value(words.get(0).getId()))
                .andExpect(jsonPath("$.data.learned").value(true))
                .andExpect(jsonPath("$.data.addedToMistakes").value(true));
    }

    @Test
    @DisplayName("§3.2 缺 wordId → 1005（@Valid 生效）且 HTTP 200")
    void studyRecordMissingWordIdThrows1005() throws Exception {
        prepareUserAndBook();

        mockMvc.perform(post(STUDY_RECORD)
                        .header(HttpHeaders.AUTHORIZATION, token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("bookId", book.getId(), "known", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));
    }

    @Test
    @DisplayName("§3.2 单词不属于该词书 → 1005")
    void studyRecordMismatchedBookThrows1005() throws Exception {
        prepareUserAndBook();
        WordBook other = newWordBook("考研核心词汇", "KAOYAN");
        List<Word> words = newWords(1, "m");

        mockMvc.perform(post(STUDY_RECORD)
                        .header(HttpHeaders.AUTHORIZATION, token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("wordId", words.get(0).getId(),
                                "bookId", other.getId(), "known", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));
    }

    // ---------------------------------------------------------------
    // §3.3 打卡 + §3.4 streak：端到端
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§3.3/§3.4 端到端：计划 → 逐词上报 → 打卡 → streak 全链路")
    void endToEndStudyAndCheckIn() throws Exception {
        prepareUserAndBook();
        List<Word> words = newWords(2, "e");

        // 1) 取计划
        String planBody = mockMvc.perform(get(PLAN).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andReturn().getResponse().getContentAsString();
        JsonNode plan = objectMapper.readTree(planBody);

        // 2) 逐词上报（第 2 个标不认识，验证错题数）
        for (int i = 0; i < 2; i++) {
            long wordId = plan.at("/data/words/" + i + "/wordId").asLong();
            mockMvc.perform(post(STUDY_RECORD)
                            .header(HttpHeaders.AUTHORIZATION, token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("wordId", wordId, "bookId", book.getId(),
                                    "known", i == 0))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("0"));
        }

        // 3) 上报后 plan.finished 追上 total，checkedIn 仍为 false
        mockMvc.perform(get(PLAN).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(jsonPath("$.data.finished").value(2))
                .andExpect(jsonPath("$.data.checkedIn").value(false));

        // 4) 打卡
        mockMvc.perform(post(CHECKIN).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.checkInDate").value("2026-10-16"))
                .andExpect(jsonPath("$.data.streakDays").value(1))
                .andExpect(jsonPath("$.data.todayLearned").value(2))
                .andExpect(jsonPath("$.data.todayMistakes").value(1));

        // 5) 打卡后 plan.checkedIn=true
        mockMvc.perform(get(PLAN).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(jsonPath("$.data.checkedIn").value(true));

        // 6) streak
        mockMvc.perform(get(STREAK).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.streakDays").value(1))
                .andExpect(jsonPath("$.data.totalLearned").value(2))
                .andExpect(jsonPath("$.data.todayReviewCount").value(0))
                .andExpect(jsonPath("$.data.lastCheckInDate").value("2026-10-16"));
    }

    @Test
    @DisplayName("§3.3 重复打卡 → 3001 且 HTTP 200")
    void checkInTwiceThrows3001() throws Exception {
        prepareUserAndBook();
        List<Word> words = newWords(1, "c");
        mockMvc.perform(post(STUDY_RECORD)
                        .header(HttpHeaders.AUTHORIZATION, token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("wordId", words.get(0).getId(),
                                "bookId", book.getId(), "known", true))))
                .andExpect(status().isOk());
        mockMvc.perform(post(CHECKIN).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(jsonPath("$.code").value("0"));

        mockMvc.perform(post(CHECKIN).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("3001"));
    }

    @Test
    @DisplayName("§3.3 无学习计划打卡 → 3002")
    void checkInWithoutPlanThrows3002() throws Exception {
        FixedClock.reset();
        user = newUser();

        mockMvc.perform(post(CHECKIN).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("3002"));
    }

    @Test
    @DisplayName("§3.3 计划未完成打卡 → 3003")
    void checkInBeforeFinishingThrows3003() throws Exception {
        prepareUserAndBook();
        newWords(3, "u"); // 计划 3 个词，一个都不上报

        mockMvc.perform(post(CHECKIN).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("3003"));
    }

    // ---------------------------------------------------------------
    // §4 复习
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§4.1 契约形状：data={total,words:[{wordId,word,phonetic,translation,example,stage,wrongCount}]}")
    void reviewTodayShape() throws Exception {
        prepareUserAndBook();
        List<Word> words = newWords(1, "rv");
        dueProgress(words.get(0).getId(), 2, TODAY.minusDays(1));

        mockMvc.perform(get(REVIEW_TODAY).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.words.length()").value(1))
                .andExpect(jsonPath("$.data.words[0].wordId").value(words.get(0).getId()))
                .andExpect(jsonPath("$.data.words[0].word").value("rv0"))
                .andExpect(jsonPath("$.data.words[0].phonetic").value("p"))
                .andExpect(jsonPath("$.data.words[0].translation").value("释义0"))
                .andExpect(jsonPath("$.data.words[0].example").value("example 0"))
                .andExpect(jsonPath("$.data.words[0].stage").value(2))
                .andExpect(jsonPath("$.data.words[0].wrongCount").value(0))
                // §4.1 响应刻意不带分页字段（规模受「今日到期」约束）
                .andExpect(jsonPath("$.data.list").doesNotExist())
                .andExpect(jsonPath("$.data.page").doesNotExist());
    }

    @Test
    @DisplayName("§4.2 契约形状：data={wordId,stage,nextReviewDate}，答对 stage+1")
    void reviewRecordShape() throws Exception {
        prepareUserAndBook();
        List<Word> words = newWords(1, "rr");
        dueProgress(words.get(0).getId(), 1, TODAY.minusDays(1));

        mockMvc.perform(post(REVIEW_RECORD)
                        .header(HttpHeaders.AUTHORIZATION, token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("wordId", words.get(0).getId(), "correct", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.wordId").value(words.get(0).getId()))
                .andExpect(jsonPath("$.data.stage").value(2))
                // 契约 §4.2 nextReviewDate 是日期（YYYY-MM-DD）
                .andExpect(jsonPath("$.data.nextReviewDate").value("2026-10-19"));
    }

    @Test
    @DisplayName("§4.2 未学习就复习 → 1005")
    void reviewRecordUnknownWordThrows1005() throws Exception {
        prepareUserAndBook();
        List<Word> words = newWords(1, "nr");

        mockMvc.perform(post(REVIEW_RECORD)
                        .header(HttpHeaders.AUTHORIZATION, token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("wordId", words.get(0).getId(), "correct", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));
    }

    // ---------------------------------------------------------------
    // §5 错题
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§5.1 契约形状：分页 {list,total,page,size}，mastered 为布尔")
    void mistakesListShape() throws Exception {
        prepareUserAndBook();
        List<Word> words = newWords(1, "ln");
        // 通过「不认识」上报来生成一条错题（走真实链路，不直接插库）
        mockMvc.perform(post(STUDY_RECORD)
                        .header(HttpHeaders.AUTHORIZATION, token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("wordId", words.get(0).getId(),
                                "bookId", book.getId(), "known", false))))
                .andExpect(status().isOk());

        String body = mockMvc.perform(get(MISTAKES)
                        .header(HttpHeaders.AUTHORIZATION, token())
                        .param("mastered", "0").param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].mistakeId").isNumber())
                .andExpect(jsonPath("$.data.list[0].wordId").value(words.get(0).getId()))
                .andExpect(jsonPath("$.data.list[0].word").value("ln0"))
                .andExpect(jsonPath("$.data.list[0].source").value("STUDY"))
                .andExpect(jsonPath("$.data.list[0].wrongCount").value(1))
                // 关键：mastered 是布尔，不是 0/1
                .andExpect(jsonPath("$.data.list[0].mastered").value(false))
                .andExpect(jsonPath("$.data.list[0].createTime").isString())
                .andReturn().getResponse().getContentAsString();

        // 用 JsonNode 类型再确认一次，避免 JSON 里把 false 写成 0 仍能通过 value(false)
        JsonNode node = objectMapper.readTree(body);
        assertTrue(node.at("/data/list/0/mastered").isBoolean(),
                "mastered 必须是 JSON boolean，而不能是数字");
    }

    @Test
    @DisplayName("§5.2+§5.3 标记已掌握 → 4002 重复；移除 → 4001 再次移除")
    void mistakeLifecycleErrors() throws Exception {
        prepareUserAndBook();
        List<Word> words = newWords(1, "lc");
        mockMvc.perform(post(STUDY_RECORD)
                        .header(HttpHeaders.AUTHORIZATION, token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("wordId", words.get(0).getId(),
                                "bookId", book.getId(), "known", false))));

        String listBody = mockMvc.perform(get(MISTAKES).header(HttpHeaders.AUTHORIZATION, token()))
                .andReturn().getResponse().getContentAsString();
        long mistakeId = objectMapper.readTree(listBody).at("/data/list/0/mistakeId").asLong();

        // 标记已掌握
        mockMvc.perform(post(MISTAKES + "/" + mistakeId + "/mastered")
                        .header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.mistakeId").value(mistakeId))
                .andExpect(jsonPath("$.data.mastered").value(true));

        // 重复标记 → 4002
        mockMvc.perform(post(MISTAKES + "/" + mistakeId + "/mastered")
                        .header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("4002"));

        // 移除
        mockMvc.perform(delete(MISTAKES + "/" + mistakeId)
                        .header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.mistakeId").value(mistakeId))
                .andExpect(jsonPath("$.data.removed").value(true));

        // 再次移除 → 4001
        mockMvc.perform(delete(MISTAKES + "/" + mistakeId)
                        .header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("4001"));
    }

    @Test
    @DisplayName("§5.2 标记不存在的错题 → 4001")
    void markMasteredUnknownThrows4001() throws Exception {
        prepareUserAndBook();

        mockMvc.perform(post(MISTAKES + "/99999999/mastered")
                        .header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("4001"));
    }

    @Test
    @DisplayName("§5.1 mastered 传非法值 → 1005")
    void mistakesInvalidMasteredThrows1005() throws Exception {
        prepareUserAndBook();

        mockMvc.perform(get(MISTAKES)
                        .header(HttpHeaders.AUTHORIZATION, token())
                        .param("mastered", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));
    }
}
