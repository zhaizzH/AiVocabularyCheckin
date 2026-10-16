package com.vocab.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vocab.common.BizException;
import com.vocab.common.Constants;
import com.vocab.common.ErrorCode;
import com.vocab.common.PageResult;
import com.vocab.dto.response.MistakeMasteredResponse;
import com.vocab.dto.response.MistakeRemoveResponse;
import com.vocab.dto.response.MistakeResponse;
import com.vocab.entity.MistakeEntry;
import com.vocab.entity.User;
import com.vocab.entity.UserWordBook;
import com.vocab.entity.Word;
import com.vocab.entity.WordBook;
import com.vocab.mapper.MistakeEntryMapper;
import com.vocab.mapper.UserMapper;
import com.vocab.mapper.UserWordBookMapper;
import com.vocab.mapper.WordBookMapper;
import com.vocab.mapper.WordMapper;
import com.vocab.testsupport.FixedClock;
import com.vocab.testsupport.TestClockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错题服务集成测试（阶段 7 闸口，契约 §5）。
 *
 * <p>本类覆盖两类关注点：
 * <ol>
 *   <li><b>收录原语 {@code addOrIncrement}</b>：学习上报与复习答错共用的去重累加规则。
 *       这条规则若漂移，两个入口的错题口径会不一致，故单独覆盖。</li>
 *   <li><b>学生端列表与状态流转</b>：分页、mastered 过滤、4001/4002、
 *       逻辑删除（移除后重新收录不受历史行影响）。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestClockConfig.class)
class MistakeServiceImplTest {

    @Autowired
    private MistakeService mistakeService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private WordBookMapper wordBookMapper;

    @Autowired
    private WordMapper wordMapper;

    @Autowired
    private MistakeEntryMapper mistakeEntryMapper;

    @Autowired
    private UserWordBookMapper userWordBookMapper;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private User user;
    private WordBook book;

    @BeforeEach
    void setUp() {
        FixedClock.reset();
        user = new User();
        user.setUsername("mk_" + System.nanoTime());
        user.setPasswordHash("$2a$10$fakeHash");
        user.setNickname("错题测试");
        user.setAvatar(Constants.DEFAULT_AVATAR);
        user.setRole(Constants.ROLE_USER);
        user.setStatus(Constants.STATUS_ENABLED);
        userMapper.insert(user);

        book = new WordBook();
        book.setName("四级核心词汇");
        book.setCategory("CET4");
        book.setWordCount(0);
        wordBookMapper.insert(book);

        UserWordBook relation = new UserWordBook();
        relation.setUserId(user.getId());
        relation.setBookId(book.getId());
        userWordBookMapper.insert(relation);
    }

    @AfterEach
    void tearDown() {
        FixedClock.reset();
    }

    // ---------------------------------------------------------------
    // 夹具
    // ---------------------------------------------------------------

    private Word newWord(String text) {
        Word word = new Word();
        word.setBookId(book.getId());
        word.setWord(text);
        word.setPhonetic("p-" + text);
        word.setTranslation("释义-" + text);
        word.setExample("example " + text);
        wordMapper.insert(word);
        return word;
    }

    /** 造一个属于另一个用户的错题（用于越权用例）。 */
    private MistakeEntry otherUserMistake() {
        User other = new User();
        other.setUsername("other_" + System.nanoTime());
        other.setPasswordHash("$2a$10$fakeHash");
        other.setNickname("他人");
        other.setAvatar(Constants.DEFAULT_AVATAR);
        other.setRole(Constants.ROLE_USER);
        other.setStatus(Constants.STATUS_ENABLED);
        userMapper.insert(other);

        Word word = newWord("otherword");
        MistakeEntry entry = new MistakeEntry();
        entry.setUserId(other.getId());
        entry.setWordId(word.getId());
        entry.setSource(Constants.SOURCE_STUDY);
        entry.setWrongCount(1);
        entry.setMastered(0);
        mistakeEntryMapper.insert(entry);
        return entry;
    }

    // ---------------------------------------------------------------
    // 收录原语：addOrIncrement
    // ---------------------------------------------------------------

    @Test
    @DisplayName("收录原语：首次收录建一条 wrong_count=1、mastered=0 的记录")
    void addOrIncrementFirstTime() {
        Word word = newWord("first");

        mistakeService.addOrIncrement(user.getId(), word.getId(), Constants.SOURCE_STUDY);

        MistakeEntry entry = selectByWord(word.getId());
        assertNotNull(entry);
        assertEquals(1, entry.getWrongCount());
        assertEquals(0, entry.getMastered());
        assertEquals(Constants.SOURCE_STUDY, entry.getSource());
    }

    @Test
    @DisplayName("收录原语：同词重复收录 → 累加不重建（wrong_count=3，仍只一条）")
    void addOrIncrementAccumulates() {
        Word word = newWord("dup");

        mistakeService.addOrIncrement(user.getId(), word.getId(), Constants.SOURCE_STUDY);
        mistakeService.addOrIncrement(user.getId(), word.getId(), Constants.SOURCE_STUDY);
        mistakeService.addOrIncrement(user.getId(), word.getId(), Constants.SOURCE_REVIEW);

        assertEquals(3, selectByWord(word.getId()).getWrongCount());
        assertEquals(1L, mistakeEntryMapper.selectCount(Wrappers.<MistakeEntry>lambdaQuery()
                .eq(MistakeEntry::getUserId, user.getId())
                .eq(MistakeEntry::getWordId, word.getId())));
        // 来源刷新为最后一次
        assertEquals(Constants.SOURCE_REVIEW, selectByWord(word.getId()).getSource());
    }

    @Test
    @DisplayName("收录原语：已掌握的错题再答错 → mastered 重置为 0")
    void addOrIncrementResetsMastered() {
        Word word = newWord("reopen");
        MistakeEntry entry = new MistakeEntry();
        entry.setUserId(user.getId());
        entry.setWordId(word.getId());
        entry.setSource(Constants.SOURCE_STUDY);
        entry.setWrongCount(1);
        entry.setMastered(1);
        mistakeEntryMapper.insert(entry);

        mistakeService.addOrIncrement(user.getId(), word.getId(), Constants.SOURCE_REVIEW);

        MistakeEntry after = selectByWord(word.getId());
        assertEquals(0, after.getMastered());
        assertEquals(2, after.getWrongCount());
    }

    @Test
    @DisplayName("收录原语：不同词的错题互不干扰")
    void addOrIncrementKeepsWordsSeparate() {
        Word a = newWord("a");
        Word b = newWord("b");

        mistakeService.addOrIncrement(user.getId(), a.getId(), Constants.SOURCE_STUDY);
        mistakeService.addOrIncrement(user.getId(), b.getId(), Constants.SOURCE_STUDY);
        mistakeService.addOrIncrement(user.getId(), b.getId(), Constants.SOURCE_STUDY);

        assertEquals(1, selectByWord(a.getId()).getWrongCount());
        assertEquals(2, selectByWord(b.getId()).getWrongCount());
    }

    // ---------------------------------------------------------------
    // §5.1 错题列表
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§5.1 空错题本 → total=0、list=[]")
    void listEmptyWhenNoMistakes() {
        PageResult<MistakeResponse> page = mistakeService.list(user.getId(), 0, 1, 20);

        assertEquals(0, page.getTotal());
        assertTrue(page.getList().isEmpty());
    }

    @Test
    @DisplayName("§5.1 列表带出单词文本、来源、错误次数、创建时间")
    void listCarriesWordDetail() {
        Word word = newWord("carry");
        mistakeService.addOrIncrement(user.getId(), word.getId(), Constants.SOURCE_STUDY);

        PageResult<MistakeResponse> page = mistakeService.list(user.getId(), 0, 1, 20);

        assertEquals(1, page.getTotal());
        MistakeResponse item = page.getList().get(0);
        assertEquals(word.getId(), item.getWordId());
        assertEquals("carry", item.getWord());
        assertEquals("释义-carry", item.getTranslation());
        assertEquals(Constants.SOURCE_STUDY, item.getSource());
        assertEquals(1, item.getWrongCount());
        // 契约 §5.1 的 mastered 是布尔值（false/true），不是 0/1
        assertFalse(item.getMastered());
        assertNotNull(item.getCreateTime());
        assertNotNull(item.getMistakeId());
    }

    @Test
    @DisplayName("§5.1 mastered=0 只看未掌握、mastered=1 只看已掌握")
    void listFiltersByMastered() {
        Word unmastered = newWord("un");
        Word mastered = newWord("m");
        mistakeService.addOrIncrement(user.getId(), unmastered.getId(), Constants.SOURCE_STUDY);
        mistakeService.addOrIncrement(user.getId(), mastered.getId(), Constants.SOURCE_STUDY);
        mistakeEntryMapper.update(null, Wrappers.<MistakeEntry>lambdaUpdate()
                .eq(MistakeEntry::getId, selectByWord(mastered.getId()).getId())
                .set(MistakeEntry::getMastered, 1));

        PageResult<MistakeResponse> unmasteredPage = mistakeService.list(user.getId(), 0, 1, 20);
        PageResult<MistakeResponse> masteredPage = mistakeService.list(user.getId(), 1, 1, 20);

        assertEquals(1, unmasteredPage.getTotal());
        assertEquals(unmastered.getId(), unmasteredPage.getList().get(0).getWordId());
        assertEquals(1, masteredPage.getTotal());
        assertEquals(mastered.getId(), masteredPage.getList().get(0).getWordId());
    }

    @Test
    @DisplayName("§5.1 mastered 缺省（null）等价于 0")
    void listDefaultsMasteredToZero() {
        Word word = newWord("def");
        mistakeService.addOrIncrement(user.getId(), word.getId(), Constants.SOURCE_STUDY);

        PageResult<MistakeResponse> page = mistakeService.list(user.getId(), null, 1, 20);

        assertEquals(1, page.getTotal());
    }

    @Test
    @DisplayName("§5.1 mastered 传非法值（如 7）→ 1005")
    void listRejectsInvalidMastered() {
        BizException e = assertThrows(BizException.class,
                () -> mistakeService.list(user.getId(), 7, 1, 20));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), e.getCode());
    }

    @Test
    @DisplayName("§5.1 列表分页：25 条错题 size=10 → 3 页，page=3 有 5 条")
    void listPaginates() {
        for (int i = 0; i < 25; i++) {
            mistakeService.addOrIncrement(user.getId(), newWord("pg" + i).getId(),
                    Constants.SOURCE_STUDY);
        }

        PageResult<MistakeResponse> page1 = mistakeService.list(user.getId(), 0, 1, 10);
        PageResult<MistakeResponse> page3 = mistakeService.list(user.getId(), 0, 3, 10);

        assertEquals(25, page1.getTotal());
        assertEquals(10, page1.getList().size());
        assertEquals(1, page1.getPage());
        assertEquals(10, page1.getSize());
        assertEquals(5, page3.getList().size());
    }

    @Test
    @DisplayName("§5.1 非法分页参数（page=0/size=-1）回落到默认 1/20")
    void listNormalizesPageParams() {
        for (int i = 0; i < 3; i++) {
            mistakeService.addOrIncrement(user.getId(), newWord("n" + i).getId(),
                    Constants.SOURCE_STUDY);
        }

        PageResult<MistakeResponse> page = mistakeService.list(user.getId(), 0, 0, -1);

        assertEquals(1, page.getPage());
        assertEquals(20, page.getSize());
        assertEquals(3, page.getList().size());
    }

    @Test
    @DisplayName("§5.1 只返回自己的错题（不含他人）")
    void listOnlyOwnMistakes() {
        otherUserMistake();
        Word mine = newWord("mine");
        mistakeService.addOrIncrement(user.getId(), mine.getId(), Constants.SOURCE_STUDY);

        PageResult<MistakeResponse> page = mistakeService.list(user.getId(), 0, 1, 20);

        assertEquals(1, page.getTotal());
        assertEquals(mine.getId(), page.getList().get(0).getWordId());
    }

    @Test
    @DisplayName("§5.1 已移除（deleted=1）的错题不出现在列表")
    void listExcludesRemoved() {
        Word word = newWord("gone");
        mistakeService.addOrIncrement(user.getId(), word.getId(), Constants.SOURCE_STUDY);
        mistakeService.remove(user.getId(), selectByWord(word.getId()).getId());

        PageResult<MistakeResponse> page = mistakeService.list(user.getId(), 0, 1, 20);

        assertEquals(0, page.getTotal());
    }

    // ---------------------------------------------------------------
    // §5.2 标记已掌握
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§5.2 标记已掌握成功 → mastered=1")
    void markMasteredSucceeds() {
        Word word = newWord("mark");
        mistakeService.addOrIncrement(user.getId(), word.getId(), Constants.SOURCE_STUDY);
        Long mistakeId = selectByWord(word.getId()).getId();

        MistakeMasteredResponse response = mistakeService.markMastered(user.getId(), mistakeId);

        assertEquals(mistakeId, response.getMistakeId());
        assertTrue(response.getMastered());
        assertEquals(1, selectByWord(word.getId()).getMastered());
    }

    @Test
    @DisplayName("§5.2 重复标记已掌握 → 4002")
    void markMasteredTwiceThrows4002() {
        Word word = newWord("twice");
        mistakeService.addOrIncrement(user.getId(), word.getId(), Constants.SOURCE_STUDY);
        Long mistakeId = selectByWord(word.getId()).getId();
        mistakeService.markMastered(user.getId(), mistakeId);

        BizException e = assertThrows(BizException.class,
                () -> mistakeService.markMastered(user.getId(), mistakeId));
        assertEquals(ErrorCode.MISTAKE_ALREADY_MASTERED.getCode(), e.getCode());
    }

    @Test
    @DisplayName("§5.2 错题不存在 → 4001")
    void markMasteredUnknownThrows4001() {
        BizException e = assertThrows(BizException.class,
                () -> mistakeService.markMastered(user.getId(), 987654321L));
        assertEquals(ErrorCode.MISTAKE_NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    @DisplayName("§5.2 不能标记他人的错题 → 4001（越权防护）")
    void markMasteredRejectsOtherUsersMistake() {
        MistakeEntry other = otherUserMistake();

        BizException e = assertThrows(BizException.class,
                () -> mistakeService.markMastered(user.getId(), other.getId()));
        assertEquals(ErrorCode.MISTAKE_NOT_FOUND.getCode(), e.getCode());
        // 他人的记录未被改动
        assertEquals(0, mistakeEntryMapper.selectById(other.getId()).getMastered());
    }

    @Test
    @DisplayName("§5.2 mistakeId=null → 4001（不抛 NPE）")
    void markMasteredNullIdThrows4001() {
        BizException e = assertThrows(BizException.class,
                () -> mistakeService.markMastered(user.getId(), null));
        assertEquals(ErrorCode.MISTAKE_NOT_FOUND.getCode(), e.getCode());
    }

    // ---------------------------------------------------------------
    // §5.3 移除错题
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§5.3 移除错题是逻辑删除：底层行仍在且 deleted=1，业务查询看不到")
    void removeIsLogicalDelete() {
        Word word = newWord("logical");
        mistakeService.addOrIncrement(user.getId(), word.getId(), Constants.SOURCE_STUDY);
        Long mistakeId = selectByWord(word.getId()).getId();

        MistakeRemoveResponse response = mistakeService.remove(user.getId(), mistakeId);

        assertTrue(response.getRemoved());
        // 业务查询（@TableLogic 自动附加 deleted=0）看不到
        assertEquals(0L, mistakeEntryMapper.selectCount(Wrappers.<MistakeEntry>lambdaQuery()
                .eq(MistakeEntry::getId, mistakeId)));

        // 绕过 @TableLogic 直接看底层行：仍存在且 deleted=1——这是「逻辑删除」的实证，
        // 也是「移除后重新收录不会撞历史行」的前提（本表刻意无唯一索引）。
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT deleted FROM mistake_entry WHERE id = ?", Integer.class, mistakeId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT deleted FROM mistake_entry WHERE id = ?", Integer.class, mistakeId));
    }

    @Test
    @DisplayName("§5.3 移除不存在的错题 → 4001")
    void removeUnknownThrows4001() {
        BizException e = assertThrows(BizException.class,
                () -> mistakeService.remove(user.getId(), 987654321L));
        assertEquals(ErrorCode.MISTAKE_NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    @DisplayName("§5.3 不能移除他人的错题 → 4001")
    void removeRejectsOtherUsersMistake() {
        MistakeEntry other = otherUserMistake();

        BizException e = assertThrows(BizException.class,
                () -> mistakeService.remove(user.getId(), other.getId()));
        assertEquals(ErrorCode.MISTAKE_NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    @DisplayName("§5.3 已移除的错题再次操作 → 4001（查不到即不存在）")
    void removeTwiceThrows4001() {
        Word word = newWord("rtwice");
        mistakeService.addOrIncrement(user.getId(), word.getId(), Constants.SOURCE_STUDY);
        Long mistakeId = selectByWord(word.getId()).getId();
        mistakeService.remove(user.getId(), mistakeId);

        BizException e = assertThrows(BizException.class,
                () -> mistakeService.remove(user.getId(), mistakeId));
        assertEquals(ErrorCode.MISTAKE_NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    @DisplayName("§5.3 移除后重新收录 → 生成新记录，历史行不干扰（本表刻意无唯一索引）")
    void reAddAfterRemoveCreatesFreshEntry() {
        Word word = newWord("re");
        mistakeService.addOrIncrement(user.getId(), word.getId(), Constants.SOURCE_STUDY);
        Long firstId = selectByWord(word.getId()).getId();
        mistakeService.remove(user.getId(), firstId);

        // 移除后重新收录：必须成功，且是一条新行 wrong_count=1
        mistakeService.addOrIncrement(user.getId(), word.getId(), Constants.SOURCE_STUDY);

        MistakeEntry fresh = selectByWord(word.getId());
        assertNotNull(fresh);
        assertNotEquals(firstId, fresh.getId());
        assertEquals(1, fresh.getWrongCount());
        assertEquals(0, fresh.getMastered());

        // 列表里只有这一条新记录
        PageResult<MistakeResponse> page = mistakeService.list(user.getId(), 0, 1, 20);
        assertEquals(1, page.getTotal());
        assertEquals(fresh.getId(), page.getList().get(0).getMistakeId());
    }

    // ---------------------------------------------------------------
    // 查询辅助
    // ---------------------------------------------------------------

    private MistakeEntry selectByWord(Long wordId) {
        return mistakeEntryMapper.selectOne(Wrappers.<MistakeEntry>lambdaQuery()
                .eq(MistakeEntry::getUserId, user.getId())
                .eq(MistakeEntry::getWordId, wordId)
                .last("LIMIT 1"));
    }

    private static void assertNotEquals(Object unexpected, Object actual) {
        if (unexpected == null ? actual == null : unexpected.equals(actual)) {
            throw new AssertionError("expected different values but both were: " + actual);
        }
    }
}
