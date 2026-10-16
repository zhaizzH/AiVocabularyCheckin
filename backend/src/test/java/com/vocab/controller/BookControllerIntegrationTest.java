package com.vocab.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocab.common.Constants;
import com.vocab.config.JwtUtil;
import com.vocab.entity.User;
import com.vocab.entity.UserWordBook;
import com.vocab.entity.Word;
import com.vocab.entity.WordBook;
import com.vocab.entity.WordProgress;
import com.vocab.mapper.StatsMapper;
import com.vocab.mapper.UserMapper;
import com.vocab.mapper.UserWordBookMapper;
import com.vocab.mapper.WordBookMapper;
import com.vocab.mapper.WordMapper;
import com.vocab.mapper.WordProgressMapper;
import com.vocab.service.WordImportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 词库模块集成测试（阶段 6 闸口：三本词书计数正确 + 词书接口按契约工作）。
 *
 * <p>覆盖《接口详细设计》§2 的每条错误码与边界：
 * <ul>
 *   <li>§2.1 列表：分类过滤 / {@code selected} / {@code learnedCount}</li>
 *   <li>§2.2 详情：单词分页 / 2001 / 分页结构为 {list,total,page,size}</li>
 *   <li>§2.3 选择：成功 / 2003 已加入 / 2001 不存在</li>
 *   <li>§2.4 移出：成功 / 2002 未选择 / <b>历史学习记录保留</b></li>
 *   <li>§8.1 导入：坏行跳过计数 / 全坏行 → 2004 / 分类白名单 → 1005</li>
 * </ul>
 *
 * <p>{@code @Transactional} 让每个用例结束回滚，避免污染 H2 中其它用例的数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BookControllerIntegrationTest {

    private static final String BOOKS = "/api/client/books";

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
    private UserWordBookMapper userWordBookMapper;

    @Autowired
    private StatsMapper statsMapper;

    @Autowired
    private WordProgressMapper wordProgressMapper;

    @Autowired
    private WordImportService wordImportService;

    // ---------------------------------------------------------------
    // 测试夹具
    // ---------------------------------------------------------------

    private User newUser() {
        User user = new User();
        user.setUsername("bk_" + System.nanoTime());
        user.setPasswordHash("$2a$10$fakeHashForBookTest");
        user.setNickname("词库测试");
        user.setAvatar(Constants.DEFAULT_AVATAR);
        user.setRole(Constants.ROLE_USER);
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

    // ---------------------------------------------------------------
    // §2.1 词库列表
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§2.1 列表：未选时 selected=false、learnedCount=0；选中并学过后分别为 true / 实际数")
    void listBooksReflectsUserState() throws Exception {
        User user = newUser();
        WordBook book = newBook("四级核心词汇", "CET4");
        Word first = newWord(book.getId(), "abandon");
        newWord(book.getId(), "absorb");
        // 词书元数据里的 wordCount 是导入时校正写入的，不由业务接口实时计算；
        // 此处显式设为 2，与库中实际单词数一致。
        wordBookUpdateCount(book.getId(), 2);

        // 未选、未学
        mockMvc.perform(get(BOOKS).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data[?(@.bookId == " + book.getId() + ")].selected")
                        .value(false))
                .andExpect(jsonPath("$.data[?(@.bookId == " + book.getId() + ")].learnedCount")
                        .value(0));

        // 选书 + 造一条 learned 进度
        mockMvc.perform(post(BOOKS + "/" + book.getId() + "/select")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        insertLearnedProgress(user.getId(), book.getId(), first.getId());

        mockMvc.perform(get(BOOKS).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.bookId == " + book.getId() + ")].selected")
                        .value(true))
                .andExpect(jsonPath("$.data[?(@.bookId == " + book.getId() + ")].learnedCount")
                        .value(1))
                // 卡片的字段一个不少
                .andExpect(jsonPath("$.data[?(@.bookId == " + book.getId() + ")].wordCount")
                        .value(2))
                .andExpect(jsonPath("$.data[?(@.bookId == " + book.getId() + ")].category")
                        .value("CET4"))
                .andExpect(jsonPath("$.data[?(@.bookId == " + book.getId() + ")].name")
                        .value("四级核心词汇"));
    }

    /** 直接造一条 word_progress，避免依赖阶段 7 的学习接口。 */
    private WordProgress insertLearnedProgress(Long userId, Long bookId, Long wordId) {
        WordProgress progress = new WordProgress();
        progress.setUserId(userId);
        progress.setBookId(bookId);
        progress.setWordId(wordId);
        progress.setStage(Constants.MIN_STAGE);
        progress.setNextReviewDate(LocalDate.now().plusDays(1));
        progress.setLearned(1);
        progress.setWrongCount(0);
        wordProgressMapper.insert(progress);
        return progress;
    }

    @Test
    @DisplayName("§2.1 列表：wordCount 取自词书元数据（导入时校正写入的值）")
    void listBooksExposesBookWordCount() throws Exception {
        User user = newUser();
        WordBook book = newBook("考研核心词汇", "KAOYAN");
        wordBookUpdateCount(book.getId(), 9602);

        mockMvc.perform(get(BOOKS).queryParam("category", "KAOYAN")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].bookId").value(book.getId()))
                .andExpect(jsonPath("$.data[0].wordCount").value(9602));
    }

    @Test
    @DisplayName("§2.1 category 过滤：只返回指定分类，非法分类返回空数组而非报错")
    void listBooksFiltersByCategory() throws Exception {
        User user = newUser();
        newBook("四级核心词汇", "CET4");
        newBook("六级核心词汇", "CET6");

        MvcResult result = mockMvc.perform(get(BOOKS).queryParam("category", "cet6")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        var data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertEquals(1, data.size(), "只应返回 CET6 一本");
        assertEquals("CET6", data.get(0).path("category").asText(), "category 应大小写不敏感");

        mockMvc.perform(get(BOOKS).queryParam("category", "NOT_EXIST")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("§2.1 无 token → 401（词库接口需登录）")
    void listBooksRequiresAuth() throws Exception {
        mockMvc.perform(get(BOOKS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1006"));
    }

    // ---------------------------------------------------------------
    // §2.2 词书详情
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§2.2 详情：返回 wordCount + 单词分页结构 {list,total,page,size}，元素字段与契约一致")
    void bookDetailReturnsPagedWords() throws Exception {
        User user = newUser();
        WordBook book = newBook("四级核心词汇", "CET4");
        newWord(book.getId(), "abandon");
        newWord(book.getId(), "absorb");
        newWord(book.getId(), "abuse");
        wordBookUpdateCount(book.getId(), 3);

        mockMvc.perform(get(BOOKS + "/" + book.getId())
                        .queryParam("page", "1").queryParam("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.bookId").value(book.getId()))
                .andExpect(jsonPath("$.data.wordCount").value(3))
                // 分页结构四项齐全
                .andExpect(jsonPath("$.data.words.list.length()").value(2))
                .andExpect(jsonPath("$.data.words.total").value(3))
                .andExpect(jsonPath("$.data.words.page").value(1))
                .andExpect(jsonPath("$.data.words.size").value(2))
                // 元素字段（契约 §2.2）：wordId/word/phonetic/translation/example
                .andExpect(jsonPath("$.data.words.list[0].wordId").isNumber())
                .andExpect(jsonPath("$.data.words.list[0].word").value("abandon"))
                .andExpect(jsonPath("$.data.words.list[0].phonetic").value("p"))
                .andExpect(jsonPath("$.data.words.list[0].translation").value("t"))
                .andExpect(jsonPath("$.data.words.list[0].example").value("e"));
    }

    private void wordBookUpdateCount(Long bookId, int count) {
        WordBook update = new WordBook();
        update.setId(bookId);
        update.setWordCount(count);
        wordBookMapper.updateById(update);
    }

    @Test
    @DisplayName("§2.2 词书不存在 → 2001")
    void bookDetailNotFound() throws Exception {
        User user = newUser();
        mockMvc.perform(get(BOOKS + "/99999999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("2001"))
                .andExpect(jsonPath("$.message").value("词书不存在"));
    }

    // ---------------------------------------------------------------
    // §2.3 / §2.4 选择与移出
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§2.3 选择词书 → {bookId, selected:true}；重复选择 → 2003")
    void selectBookThenDuplicate() throws Exception {
        User user = newUser();
        WordBook book = newBook("考研核心词汇", "KAOYAN");
        String token = tokenFor(user);

        mockMvc.perform(post(BOOKS + "/" + book.getId() + "/select")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.bookId").value(book.getId()))
                .andExpect(jsonPath("$.data.selected").value(true));

        mockMvc.perform(post(BOOKS + "/" + book.getId() + "/select")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("2003"))
                .andExpect(jsonPath("$.message").value("词书已加入"));

        // 关联表只应有一行
        Long count = userWordBookMapper.selectCount(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<UserWordBook>lambdaQuery()
                        .eq(UserWordBook::getUserId, user.getId())
                        .eq(UserWordBook::getBookId, book.getId()));
        assertEquals(1L, count);
    }

    @Test
    @DisplayName("§2.3 选择不存在的词书 → 2001")
    void selectUnknownBook() throws Exception {
        User user = newUser();
        mockMvc.perform(post(BOOKS + "/99999999/select")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("2001"));
    }

    @Test
    @DisplayName("§2.4 移出词书 → selected:false，且**历史学习记录保留**")
    void deselectKeepsHistory() throws Exception {
        User user = newUser();
        WordBook book = newBook("四级核心词汇", "CET4");
        Word word = newWord(book.getId(), "abandon");
        String token = tokenFor(user);

        mockMvc.perform(post(BOOKS + "/" + book.getId() + "/select")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value("0"));
        insertLearnedProgress(user.getId(), book.getId(), word.getId());

        mockMvc.perform(delete(BOOKS + "/" + book.getId() + "/select")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.selected").value(false));

        // 关联行已删
        Long relations = userWordBookMapper.selectCount(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<UserWordBook>lambdaQuery()
                        .eq(UserWordBook::getUserId, user.getId())
                        .eq(UserWordBook::getBookId, book.getId()));
        assertEquals(0L, relations, "关联行应物理删除");

        // 契约 §2.4「历史学习记录保留」：word_progress 仍在
        Integer learned = statsMapper.countLearnedWordsInBook(user.getId(), book.getId());
        assertEquals(1, learned, "移出词书不得删除学习进度");
    }

    @Test
    @DisplayName("§2.4 未选择就移出 → 2002")
    void deselectWithoutSelect() throws Exception {
        User user = newUser();
        WordBook book = newBook("四级核心词汇", "CET4");

        mockMvc.perform(delete(BOOKS + "/" + book.getId() + "/select")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("2002"))
                .andExpect(jsonPath("$.message").value("未选择该词书"));
    }

    // ---------------------------------------------------------------
    // §8.1 导入（唯一解析实现的端到端验证）
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§8.1 导入：3 行含 1 行缺 word → success=2、skipped=1，wordCount 校正为 2")
    void importCountsBadLinesAsSkipped() {
        String content = "{\"word\":\"alpha\",\"uk\":\"æ\",\"translations\":[{\"translation\":\"甲\",\"type\":\"n\"}]}\n"
                + "{\"uk\":\"no-word-field\",\"translations\":[{\"translation\":\"无词\",\"type\":\"n\"}]}\n"
                + "{\"word\":\"beta\",\"translations\":[{\"translation\":\"乙\",\"type\":\"n\"}]}\n";
        var result = wordImportService.importFromStream(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                "导入测试词书", "CET4");

        assertNotNull(result.getBookId());
        assertEquals(2, result.getSuccess());
        assertEquals(1, result.getSkipped());

        WordBook saved = wordBookMapper.selectById(result.getBookId());
        assertEquals(2, saved.getWordCount(), "word_count 必须按真实行数校正");
        assertEquals("导入测试词书", saved.getName());
        assertEquals("CET4", saved.getCategory());
        assertTrue(wordMapper.selectCount(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Word>lambdaQuery()
                        .eq(Word::getBookId, result.getBookId())) == 2L);
    }

    @Test
    @DisplayName("§8.1 导入：映射与种子入口一致（uk→phonetic、translations 拼接）")
    void importUsesSameMappingAsSeed() {
        String content = "{\"word\":\"absorb\",\"us\":\"æbˈsɔrb\",\"uk\":\"əb'sɔ:b\","
                + "\"translations\":[{\"translation\":\"吸收\",\"type\":\"v\"},"
                + "{\"translation\":\"吸引\",\"type\":\"v\"}],"
                + "\"sentences\":[{\"sentence\":\"Plants absorb nutrients.\"}]}\n";
        var result = wordImportService.importFromStream(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                "映射测试", "CET6");

        List<Word> words = wordMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Word>lambdaQuery()
                        .eq(Word::getBookId, result.getBookId()));
        assertEquals(1, words.size());
        Word word = words.get(0);
        assertEquals("absorb", word.getWord());
        assertEquals("əb'sɔ:b", word.getPhonetic(), "必须取 uk");
        assertEquals("v. 吸收；v. 吸引", word.getTranslation());
        assertEquals("Plants absorb nutrients.", word.getExample());
    }

    @Test
    @DisplayName("§8.1 全部行为坏行 → 2004（整体格式非法），且不留下空词书")
    void importAllBadLinesReturns2004() {
        String content = "not json at all\n{\"no\":\"word\"}\n";
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.vocab.common.BizException.class,
                () -> wordImportService.importFromStream(
                        new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                        "全坏行", "CET4"));
        assertEquals("2004", ex.getCode());

        // 事务回滚：不应残留词书
        Long count = wordBookMapper.selectCount(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<WordBook>lambdaQuery()
                        .eq(WordBook::getName, "全坏行"));
        assertEquals(0L, count, "整体导入失败不得留下空词书");
    }

    @Test
    @DisplayName("§8.1 分类不在白名单 → 1005 而非 2004（参数错误 ≠ 文件格式错误）")
    void importRejectsUnknownCategory() {
        String content = "{\"word\":\"alpha\"}\n";
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.vocab.common.BizException.class,
                () -> wordImportService.importFromStream(
                        new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                        "分类非法", "TOEFL"));
        assertEquals("1005", ex.getCode());
    }
}
