package com.vocab.controller.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocab.ai.AiMetrics;
import com.vocab.common.Constants;
import com.vocab.config.JwtUtil;
import com.vocab.entity.StudyRecord;
import com.vocab.entity.User;
import com.vocab.entity.Word;
import com.vocab.entity.WordBook;
import com.vocab.mapper.StudyRecordMapper;
import com.vocab.mapper.UserMapper;
import com.vocab.mapper.WordBookMapper;
import com.vocab.mapper.WordMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理模块集成测试（《接口详细设计》§8.1 – §8.10）。
 *
 * <p>覆盖契约每一条错误码与不变式：
 * <ul>
 *   <li><b>权限</b>：无 token → 401 + 1006；普通 USER → 403 + 1007；ADMIN 放行</li>
 *   <li>§8.1 导入：坏行 skipped / 全坏行 2004 / 分类白名单 1005</li>
 *   <li>§8.2 单词分页：分页结构 + 元素含 wordId</li>
 *   <li>§8.3/§8.4/§8.5 增改删：<b>wordCount 联动 ±1</b>、1005、2001</li>
 *   <li>§8.6 用户列表：totalCheckInDays 由聚合得出（非 N+1）、时间格式</li>
 *   <li>§8.7 状态：禁用后该用户 <b>立即</b> 401+1003；禁 ADMIN → 1005</li>
 *   <li>§8.8 词书列表：裸数组 + createTime，且 <b>不含</b> selected/learnedCount</li>
 *   <li>§8.9 删除词书：连带逻辑删除单词，行仍在库中（deleted=1）</li>
 *   <li>§8.10 ai-stats：降级计数、成功率口径、features 固定三项</li>
 * </ul>
 *
 * <p>{@code @Transactional}：每个用例结束回滚，避免污染 H2 中其它用例的数据
 * （泄漏会让「空的用户表」类断言在别的用例里假失败）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminControllerIntegrationTest {

    private static final String ADMIN_BOOKS = "/api/admin/books";
    private static final String ADMIN_WORDS = "/api/admin/words";
    private static final String ADMIN_USERS = "/api/admin/users";
    private static final String ADMIN_AI_STATS = "/api/admin/monitor/ai-stats";

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
    private AiMetrics aiMetrics;

    // ---------------------------------------------------------------
    // 夹具
    // ---------------------------------------------------------------

    private User newUser(String role) {
        User user = new User();
        user.setUsername("adm_" + System.nanoTime());
        user.setPasswordHash("$2a$10$fakeHashForAdminTest");
        user.setNickname("管理测试");
        user.setAvatar(Constants.DEFAULT_AVATAR);
        user.setRole(role);
        user.setStatus(Constants.STATUS_ENABLED);
        userMapper.insert(user);
        return user;
    }

    private String tokenFor(User user) {
        return jwtUtil.generateToken(user.getId(), user.getRole());
    }

    private WordBook newBook(String name, String category) {
        WordBook book = new WordBook();
        book.setName(name);
        book.setCategory(category);
        book.setWordCount(0);
        book.setDescription("测试词书");
        wordBookMapper.insert(book);
        return book;
    }

    private Word newWord(Long bookId, String text) {
        Word word = new Word();
        word.setBookId(bookId);
        word.setWord(text);
        word.setPhonetic("p");
        word.setTranslation("t");
        word.setExample("e");
        wordMapper.insert(word);
        return word;
    }

    private int wordCountOf(Long bookId) {
        return wordBookMapper.selectById(bookId).getWordCount();
    }

    // ---------------------------------------------------------------
    // 权限（§8 头部：/api/admin/** 需 ADMIN）
    // ---------------------------------------------------------------

    @Test
    @DisplayName("管理端：无 token → 401 + 1006")
    void noTokenGets401() throws Exception {
        mockMvc.perform(get(ADMIN_BOOKS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1006"));
    }

    @Test
    @DisplayName("管理端：普通 USER → 403 + 1007（不是 404，也不是 401）")
    void normalUserGets403() throws Exception {
        User user = newUser(Constants.ROLE_USER);
        mockMvc.perform(get(ADMIN_BOOKS).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1007"));
    }

    @Test
    @DisplayName("管理端：ADMIN 放行（词书列表返回 200）")
    void adminGets200() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        mockMvc.perform(get(ADMIN_BOOKS).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
    }

    // ---------------------------------------------------------------
    // §8.8 词书列表
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§8.8 词书列表：裸数组、含 createTime、不含 selected/learnedCount")
    void adminBookListHasNoUserDimension() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        WordBook book = newBook("四级核心词汇", "CET4");

        mockMvc.perform(get(ADMIN_BOOKS).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                // 裸数组：data 是 []，不是 {list:[...]}
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[?(@.bookId == " + book.getId() + ")].name")
                        .value("四级核心词汇"))
                .andExpect(jsonPath("$.data[?(@.bookId == " + book.getId() + ")].category")
                        .value("CET4"))
                .andExpect(jsonPath("$.data[?(@.bookId == " + book.getId() + ")].createTime")
                        .exists())
                // 管理端无用户维度，这两个字段必须不存在（不是 false/0）
                .andExpect(jsonPath("$.data[?(@.bookId == " + book.getId() + ")].selected")
                        .doesNotExist())
                .andExpect(jsonPath("$.data[?(@.bookId == " + book.getId() + ")].learnedCount")
                        .doesNotExist());
    }

    // ---------------------------------------------------------------
    // §8.1 词库导入
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§8.1 导入：3 行含 1 行缺 word → success=2、skipped=1")
    void importSkipsBadLines() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        String content = "{\"word\":\"alpha\",\"uk\":\"æ\",\"translations\":[{\"translation\":\"甲\",\"type\":\"n\"}]}\n"
                + "{\"uk\":\"no-word-field\"}\n"
                + "{\"word\":\"beta\",\"translations\":[{\"translation\":\"乙\",\"type\":\"n\"}]}\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.jsonl", "application/octet-stream",
                content.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart(ADMIN_BOOKS + "/import")
                        .file(file)
                        .param("name", "导入测试词书")
                        .param("category", "CET4")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.success").value(2))
                .andExpect(jsonPath("$.data.skipped").value(1))
                .andExpect(jsonPath("$.data.bookId").isNumber());
    }

    @Test
    @DisplayName("§8.1 导入：整份无合法行 → 2004，且不留下空壳词书")
    void importAllBadLinesReturns2004() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        long booksBefore = wordBookMapper.selectCount(null);

        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.jsonl", "application/octet-stream",
                "{\"no-word\":1}\n{\"also\":\"bad\"}\n".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart(ADMIN_BOOKS + "/import")
                        .file(file)
                        .param("name", "坏词书")
                        .param("category", "CET4")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("2004"));

        assertEquals(booksBefore, wordBookMapper.selectCount(null),
                "整份非法时不得留下 0 词的空壳词书");
    }

    @Test
    @DisplayName("§8.1 导入：category 不在白名单 → 1005")
    void importInvalidCategoryReturns1005() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        MockMultipartFile file = new MockMultipartFile(
                "file", "ok.jsonl", "application/octet-stream",
                "{\"word\":\"alpha\"}\n".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart(ADMIN_BOOKS + "/import")
                        .file(file)
                        .param("name", "分类错误词书")
                        .param("category", "TOEFL")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));
    }

    // ---------------------------------------------------------------
    // §8.2 – §8.5 单词管理
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§8.2 单词分页：结构为 {list,total,page,size}，元素含 wordId")
    void listWordsIsPaged() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        WordBook book = newBook("分页词书", "CET6");
        newWord(book.getId(), "apple");
        newWord(book.getId(), "banana");

        mockMvc.perform(get(ADMIN_WORDS)
                        .param("bookId", String.valueOf(book.getId()))
                        .param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].wordId").isNumber())
                .andExpect(jsonPath("$.data.list[0].word").value("apple"));
    }

    @Test
    @DisplayName("§8.2 单词分页：keyword 模糊搜索命中")
    void listWordsFiltersByKeyword() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        WordBook book = newBook("搜索词书", "CET4");
        newWord(book.getId(), "abandon");
        newWord(book.getId(), "zebra");

        mockMvc.perform(get(ADMIN_WORDS)
                        .param("keyword", "abando")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].word").value("abandon"));
    }

    @Test
    @DisplayName("§8.3 新增单词：成功且词书 wordCount +1")
    void createWordIncrementsWordCount() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        WordBook book = newBook("新增词书", "CET4");
        assertEquals(0, wordCountOf(book.getId()));

        String body = "{\"bookId\":" + book.getId()
                + ",\"word\":\"apple\",\"phonetic\":\"ˈæpl\","
                + "\"translation\":\"n. 苹果\",\"example\":\"An apple a day.\"}";

        mockMvc.perform(post(ADMIN_WORDS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.wordId").isNumber())
                .andExpect(jsonPath("$.data.word").value("apple"))
                .andExpect(jsonPath("$.data.translation").value("n. 苹果"));

        assertEquals(1, wordCountOf(book.getId()), "新增后 wordCount 必须 +1");
    }

    @Test
    @DisplayName("§8.3 新增单词：词书不存在 → 2001")
    void createWordMissingBookReturns2001() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        String body = "{\"bookId\":999999,\"word\":\"ghost\"}";

        mockMvc.perform(post(ADMIN_WORDS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("2001"));
    }

    @Test
    @DisplayName("§8.3 新增单词：word 空白 → 1005（不写入、不改计数）")
    void createWordBlankReturns1005() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        WordBook book = newBook("空白词书", "CET4");
        String body = "{\"bookId\":" + book.getId() + ",\"word\":\"   \"}";

        mockMvc.perform(post(ADMIN_WORDS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));

        assertEquals(0, wordCountOf(book.getId()), "参数非法不得改动 wordCount");
    }

    @Test
    @DisplayName("§8.4 修改单词：只改传入字段，未传字段保持原值；不影响 wordCount")
    void updateWordOnlyTouchesProvidedFields() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        WordBook book = newBook("修改词书", "CET4");
        Word word = newWord(book.getId(), "apple");

        mockMvc.perform(put(ADMIN_WORDS + "/" + word.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"translation\":\"n. 新释义\"}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.translation").value("n. 新释义"))
                // 未传的字段必须原样保留（不是被清空）
                .andExpect(jsonPath("$.data.word").value("apple"))
                .andExpect(jsonPath("$.data.phonetic").value("p"));

        assertEquals(0, wordCountOf(book.getId()), "改词不改变 wordCount");
    }

    @Test
    @DisplayName("§8.4 修改单词：换书时两本书的 wordCount 各自 -1/+1")
    void updateWordMovesBookCounts() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        WordBook from = newBook("源词书", "CET4");
        WordBook to = newBook("目标词书", "CET6");
        Word word = newWord(from.getId(), "apple");
        wordBookMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<WordBook>lambdaUpdate().set(WordBook::getWordCount, 1).eq(WordBook::getId, from.getId()));

        mockMvc.perform(put(ADMIN_WORDS + "/" + word.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":" + to.getId() + "}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        assertEquals(0, wordCountOf(from.getId()), "源词书 wordCount 必须 -1");
        assertEquals(1, wordCountOf(to.getId()), "目标词书 wordCount 必须 +1");
    }

    @Test
    @DisplayName("§8.4 修改单词：一个字面量都没传 → 1005")
    void updateWordWithNoFieldsReturns1005() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        WordBook book = newBook("空更新词书", "CET4");
        Word word = newWord(book.getId(), "apple");

        mockMvc.perform(put(ADMIN_WORDS + "/" + word.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));
    }

    @Test
    @DisplayName("§8.4 修改单词：单词不存在 → 1005（契约无 2005）")
    void updateMissingWordReturns1005() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        mockMvc.perform(put(ADMIN_WORDS + "/999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"translation\":\"x\"}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));
    }

    @Test
    @DisplayName("§8.5 删除单词：逻辑删除（行仍在、deleted=1）且 wordCount −1")
    void deleteWordIsLogicalAndDecrements() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        WordBook book = newBook("删除词书", "CET4");
        Word word = newWord(book.getId(), "apple");
        wordBookMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<WordBook>lambdaUpdate().set(WordBook::getWordCount, 1).eq(WordBook::getId, book.getId()));

        mockMvc.perform(delete(ADMIN_WORDS + "/" + word.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.wordId").value(word.getId()))
                .andExpect(jsonPath("$.data.deleted").value(true));

        assertNull(wordMapper.selectById(word.getId()), "逻辑删除后正常查询不可见");
        assertEquals(0, wordCountOf(book.getId()), "删除后 wordCount 必须 -1");
    }

    @Test
    @DisplayName("§8.5 删除单词：不存在 → 1005")
    void deleteMissingWordReturns1005() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        mockMvc.perform(delete(ADMIN_WORDS + "/999999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));
    }

    // ---------------------------------------------------------------
    // §8.6 / §8.7 用户管理
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§8.6 用户列表：totalCheckInDays 为实际打卡天数，未打卡用户为 0")
    void listUsersComputesCheckInDays() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        User active = newUser(Constants.ROLE_USER);
        User lazy = newUser(Constants.ROLE_USER);
        // 造 2 天打卡（同一天两条流水只算 1 天——用 COUNT(DISTINCT check_in_date)）
        insertStudyRecord(active.getId(), 11L, LocalDate.of(2026, 10, 15));
        insertStudyRecord(active.getId(), 12L, LocalDate.of(2026, 10, 16));
        insertStudyRecord(active.getId(), 13L, LocalDate.of(2026, 10, 16));

        mockMvc.perform(get(ADMIN_USERS)
                        .param("keyword", active.getUsername())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].userId").value(active.getId()))
                .andExpect(jsonPath("$.data.list[0].username").value(active.getUsername()))
                .andExpect(jsonPath("$.data.list[0].role").value("USER"))
                .andExpect(jsonPath("$.data.list[0].status").value(1))
                // 同一天 2 条流水只算 1 天 → 2
                .andExpect(jsonPath("$.data.list[0].totalCheckInDays").value(2))
                .andExpect(jsonPath("$.data.list[0].createTime").exists());
    }

    @Test
    @DisplayName("§8.6 用户列表：分页结构 + 未打卡用户 totalCheckInDays 为 0")
    void listUsersPaginatesAndDefaultsZero() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        User lazy = newUser(Constants.ROLE_USER);

        mockMvc.perform(get(ADMIN_USERS)
                        .param("keyword", lazy.getUsername())
                        .param("page", "1")
                        .param("size", "20")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.list[0].totalCheckInDays").value(0));
    }

    @Test
    @DisplayName("§8.6 用户列表：绝不返回 passwordHash / deleted")
    void listUsersNeverLeaksSecrets() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        mockMvc.perform(get(ADMIN_USERS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.list[0].password").doesNotExist())
                .andExpect(jsonPath("$.data.list[0].deleted").doesNotExist());
    }

    @Test
    @DisplayName("§8.7 禁用用户：返回回显状态，且该用户 token 立即 401 + 1003")
    void disableUserTakesEffectImmediately() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        User victim = newUser(Constants.ROLE_USER);
        String victimToken = tokenFor(victim);

        // 禁用前该 token 可用
        mockMvc.perform(get("/api/client/auth/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + victimToken))
                .andExpect(status().isOk());

        mockMvc.perform(put(ADMIN_USERS + "/" + victim.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.userId").value(victim.getId()))
                .andExpect(jsonPath("$.data.status").value(0));

        // 禁用后同一 token 立即失效（拦截器每请求查库，不是等 token 过期）
        mockMvc.perform(get("/api/client/auth/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + victimToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1003"));
    }

    @Test
    @DisplayName("§8.7 启用用户：已禁用用户可被重新启用")
    void enableUserRestoresAccess() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        User user = newUser(Constants.ROLE_USER);
        User disabled = new User();
        disabled.setId(user.getId());
        disabled.setStatus(Constants.STATUS_DISABLED);
        userMapper.updateById(disabled);

        mockMvc.perform(put(ADMIN_USERS + "/" + user.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":1}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(1));

        assertEquals(Constants.STATUS_ENABLED, userMapper.selectById(user.getId()).getStatus());
    }

    @Test
    @DisplayName("§8.7 禁用 ADMIN 账号 → 1005，且状态未被改动")
    void cannotDisableAdmin() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        User otherAdmin = newUser(Constants.ROLE_ADMIN);

        mockMvc.perform(put(ADMIN_USERS + "/" + otherAdmin.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));

        assertEquals(Constants.STATUS_ENABLED, userMapper.selectById(otherAdmin.getId()).getStatus());
    }

    @Test
    @DisplayName("§8.7 status 缺失 → 1005（不被默认为 0 而误禁用）")
    void missingStatusReturns1005() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        User user = newUser(Constants.ROLE_USER);

        mockMvc.perform(put(ADMIN_USERS + "/" + user.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));

        assertEquals(Constants.STATUS_ENABLED, userMapper.selectById(user.getId()).getStatus(),
                "漏传 status 绝不能把用户禁用掉");
    }

    @Test
    @DisplayName("§8.7 status 取非法值（如 2）→ 1005")
    void invalidStatusReturns1005() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        User user = newUser(Constants.ROLE_USER);

        mockMvc.perform(put(ADMIN_USERS + "/" + user.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":2}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));
    }

    @Test
    @DisplayName("§8.7 目标用户不存在 → 1005")
    void statusOnMissingUserReturns1005() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        mockMvc.perform(put(ADMIN_USERS + "/999999/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));
    }

    // ---------------------------------------------------------------
    // §8.9 删除词书
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§8.9 删除词书：连带逻辑删除其单词（行仍在库中），响应 {bookId,deleted}")
    void deleteBookCascadesLogically() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        WordBook book = newBook("待删词书", "KAOYAN");
        Word word = newWord(book.getId(), "abandon");

        mockMvc.perform(delete(ADMIN_BOOKS + "/" + book.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.bookId").value(book.getId()))
                .andExpect(jsonPath("$.data.deleted").value(true));

        assertNull(wordBookMapper.selectById(book.getId()), "词书逻辑删除后正常查询不可见");
        assertNull(wordMapper.selectById(word.getId()), "单词应连带逻辑删除");
    }

    @Test
    @DisplayName("§8.9 删除词书：不存在 → 2001")
    void deleteMissingBookReturns2001() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        mockMvc.perform(delete(ADMIN_BOOKS + "/999999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("2001"));
    }

    // ---------------------------------------------------------------
    // §8.10 AI 监控
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§8.10 ai-stats：features 固定按 example/explain/report 三项返回（即使零调用）")
    void aiStatsExposesFixedFeatureOrder() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);

        mockMvc.perform(get(ADMIN_AI_STATS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                // 顺序固定：即便某功能零调用也占位，前端表格列不会错位
                .andExpect(jsonPath("$.data.features.length()").value(3))
                .andExpect(jsonPath("$.data.features[0].feature").value("example"))
                .andExpect(jsonPath("$.data.features[1].feature").value("explain"))
                .andExpect(jsonPath("$.data.features[2].feature").value("report"))
                .andExpect(jsonPath("$.data.recentEvents").isArray())
                .andExpect(jsonPath("$.data.registeredUsers").value(userMapper.selectCount(null)));
    }

    @Test
    @DisplayName("§8.10 ai-stats：降级也计入 totalCalls，successRate 分母含降级，recentEvents 最新在前")
    void aiStatsReflectsDegrades() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);

        // AiMetrics 是单例内存计数器，同一 Spring 上下文中其它用例可能已写入。
        // 故此处取「前 / 后 两次快照的差值」断言，而不是绝对值——否则用例间
        // 会产生隐式顺序依赖（换个执行顺序就红）。
        int baseTotal = readTotalCalls(admin);
        int baseExplainTotal = readExplainTotal(admin);
        int baseDegraded = readDegradedCount(admin);

        // 2 次成功 + 1 次超时降级 → 该项 total += 3
        aiMetrics.recordSuccess(AiMetrics.FEATURE_EXPLAIN, 100);
        aiMetrics.recordSuccess(AiMetrics.FEATURE_EXPLAIN, 200);
        aiMetrics.recordDegrade(AiMetrics.FEATURE_EXPLAIN, 30000,
                AiMetrics.REASON_TIMEOUT, "抛 5001", java.time.LocalDateTime.now());

        mockMvc.perform(get(ADMIN_AI_STATS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCalls").value(baseTotal + 3))
                .andExpect(jsonPath("$.data.degradedCount").value(baseDegraded + 1))
                .andExpect(jsonPath("$.data.features[1].total").value(baseExplainTotal + 3))
                .andExpect(jsonPath("$.data.features[1].timeout").value(1))
                .andExpect(jsonPath("$.data.features[1].degraded").value(1))
                // 最新事件在前：刚写入的这条必须在队首
                .andExpect(jsonPath("$.data.recentEvents[0].feature").value("explain"))
                .andExpect(jsonPath("$.data.recentEvents[0].reason").value("TIMEOUT"))
                .andExpect(jsonPath("$.data.recentEvents[0].costMs").value(30000))
                .andExpect(jsonPath("$.data.recentEvents[0].result").value("抛 5001"))
                .andExpect(jsonPath("$.data.recentEvents[0].time").exists());
    }

    /** 读 ai-stats 响应的一个整数（用于差值断言）。 */
    private int readAiStats(User admin, String pointer) throws Exception {
        String body = mockMvc.perform(get(ADMIN_AI_STATS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at(pointer).asInt();
    }

    private int readTotalCalls(User admin) throws Exception {
        return readAiStats(admin, "/data/totalCalls");
    }

    private int readExplainTotal(User admin) throws Exception {
        return readAiStats(admin, "/data/features/1/total");
    }

    private int readDegradedCount(User admin) throws Exception {
        return readAiStats(admin, "/data/degradedCount");
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    private void insertStudyRecord(Long userId, Long wordId, LocalDate date) {
        StudyRecord record = new StudyRecord();
        record.setUserId(userId);
        record.setBookId(1L);
        record.setWordId(wordId);
        // study_date 与 check_in_date 恒等（阶段 7 口径：打卡当日才写 check_in_date）
        record.setStudyDate(date);
        record.setCheckInDate(date);
        record.setStudyType(Constants.STUDY_TYPE_STUDY);
        record.setStreakDays(1);
        studyRecordMapper.insert(record);
        assertNotNull(record.getId());
    }
}
