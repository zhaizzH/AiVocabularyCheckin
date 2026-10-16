package com.vocab.entity;

import com.vocab.mapper.MistakeEntryMapper;
import com.vocab.mapper.StatsMapper;
import com.vocab.mapper.StudyRecordMapper;
import com.vocab.mapper.UserMapper;
import com.vocab.mapper.UserWordBookMapper;
import com.vocab.mapper.WordBookMapper;
import com.vocab.mapper.WordMapper;
import com.vocab.mapper.WordProgressMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 面向<b>真实 MySQL</b> 的实体映射验证（阶段 4 闸口验证，默认跳过）。
 *
 * <p><b>为什么还需要这个测试</b>：{@link EntityMappingIntegrationTest} 跑在 H2 上，
 * 而 H2 结构与真实 DDL 之间隔着一份手写的 {@code schema-h2.sql}。
 * 本测试直接连真实 MySQL（无 DB 层时间默认值 + {@code STRICT_TRANS_TABLES}），
 * 是「{@code @TableField(fill=...)} 是否真的兜住了 1364」的**终极证据**——
 * 这正是阶段 3 实测报错 {@code 1364 Field 'create_time' doesn't have a default value}
 * 的回归防线。
 *
 * <p>默认不执行（避免污染开发者数据库 / 让 CI 依赖 MySQL）。启用方式：
 * <pre>
 * mvn -o test -Dmysql.it=true -Dtest=RealMysqlMappingIT \
 *     -Dspring.profiles.active=local
 * </pre>
 * 连接参数取自 {@code application-local.yml}（已被 .gitignore 排除）。
 *
 * <p>全类 {@code @Transactional}，测试结束自动回滚，不对库留下任何数据。
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "mysql.it", matches = "true")
class RealMysqlMappingIT {

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WordBookMapper wordBookMapper;
    @Autowired
    private WordMapper wordMapper;
    @Autowired
    private UserWordBookMapper userWordBookMapper;
    @Autowired
    private StudyRecordMapper studyRecordMapper;
    @Autowired
    private MistakeEntryMapper mistakeEntryMapper;
    @Autowired
    private WordProgressMapper wordProgressMapper;
    @Autowired
    private StatsMapper statsMapper;

    @Test
    @DisplayName("真实 MySQL：7 表可查 + 时间戳自动填充生效（DB 层确实无默认值）")
    void allEntitiesRoundTripOnRealMysql() {
        // 1) 7 张表均可查询（验证 @TableName / 列名与真实 MySQL 一致）
        assertEquals(0L, userMapper.selectCount(null));
        assertEquals(0L, wordBookMapper.selectCount(null));
        assertEquals(0L, wordMapper.selectCount(null));
        assertEquals(0L, userWordBookMapper.selectCount(null));
        assertEquals(0L, studyRecordMapper.selectCount(null));
        assertEquals(0L, mistakeEntryMapper.selectCount(null));
        assertEquals(0L, wordProgressMapper.selectCount(null));

        // 2) 插入：刻意不设 createTime/updateTime。
        //    真实 MySQL 这两列是 DATETIME NOT NULL 且无默认值，
        //    若 @TableField(fill=...) 失效，此处必定抛 1364。
        WordBook book = new WordBook();
        book.setName("阶段4映射验证词书");
        book.setCategory("CET4");
        book.setWordCount(0);
        assertEquals(1, wordBookMapper.insert(book));
        assertNotNull(book.getCreateTime(), "真实 MySQL 上 createTime 必须被填充");
        assertNotNull(book.getUpdateTime(), "真实 MySQL 上 updateTime 必须被填充");

        User user = new User();
        user.setUsername("stage4-verify-" + System.nanoTime());
        user.setPasswordHash("$2a$10$verifyOnlyNotARealCredential");
        user.setNickname("阶段4验证");
        user.setAvatar("avatar_1");
        user.setRole("USER");
        user.setStatus(1);
        assertEquals(1, userMapper.insert(user));
        assertNotNull(user.getId());
        assertNotNull(user.getCreateTime());

        Word word = new Word();
        word.setBookId(book.getId());
        word.setWord("abandon");
        word.setPhonetic("əˈbændən");
        word.setTranslation("v. 放弃；抛弃");
        word.setExample("He decided to abandon the plan.");
        assertEquals(1, wordMapper.insert(word));

        UserWordBook relation = new UserWordBook();
        relation.setUserId(user.getId());
        relation.setBookId(book.getId());
        assertEquals(1, userWordBookMapper.insert(relation));
        assertNotNull(relation.getCreateTime(), "中间表（无 deleted）同样须被填充");

        StudyRecord study = new StudyRecord();
        study.setUserId(user.getId());
        study.setBookId(book.getId());
        study.setWordId(word.getId());
        study.setStudyType("STUDY");
        study.setStudyDate(LocalDate.now());
        study.setCheckInDate(LocalDate.now());
        study.setStreakDays(1);
        assertEquals(1, studyRecordMapper.insert(study));

        MistakeEntry mistake = new MistakeEntry();
        mistake.setUserId(user.getId());
        mistake.setWordId(word.getId());
        mistake.setSource("STUDY");
        mistake.setWrongCount(1);
        mistake.setMastered(0);
        assertEquals(1, mistakeEntryMapper.insert(mistake));

        WordProgress progress = new WordProgress();
        progress.setUserId(user.getId());
        progress.setBookId(book.getId());
        progress.setWordId(word.getId());
        progress.setStage(1);
        progress.setNextReviewDate(LocalDate.now().plusDays(1));
        progress.setLearned(1);
        assertEquals(1, wordProgressMapper.insert(progress));

        // 3) 读回：字段完整往返
        Word loadedWord = wordMapper.selectById(word.getId());
        assertEquals("abandon", loadedWord.getWord());
        assertEquals("v. 放弃；抛弃", loadedWord.getTranslation());
        assertNull(loadedWord.getDefinition(), "definition 一期留空");
        assertNull(loadedWord.getAudioUrl(), "audio_url 一期留空");

        StudyRecord loadedStudy = studyRecordMapper.selectById(study.getId());
        assertEquals(LocalDate.now(), loadedStudy.getStudyDate());
        assertNull(loadedStudy.getCorrect(), "学习型记录 correct 为 NULL");

        // 4) 真实 MySQL 上跑统计聚合（验证 XML 在 MySQL 方言下同样可执行）
        assertEquals(1, statsMapper.selectHeatmap(user.getId(), LocalDate.now().minusDays(84)).size());
        var mastery = statsMapper.selectMasteryDaily(user.getId(), LocalDate.now().minusDays(30));
        assertEquals(1, mastery.size());
        assertEquals(1, mastery.get(0).getLearnedCount(), "1 条 STUDY");
        assertEquals(0, mastery.get(0).getReviewCount());
        assertEquals(1, mastery.get(0).getMistakeCount(),
                "真实 MySQL 上 LEFT JOIN 错题子查询必须返回 1（验证 DATE()/ONLY_FULL_GROUP_BY 兼容）");
        assertEquals(1, statsMapper.countCheckInDays(user.getId()));
        assertEquals(1, statsMapper.countLearnedWords(user.getId()));
        assertEquals(1, statsMapper.countLearnedWordsInBook(user.getId(), book.getId()));
        assertEquals(1, statsMapper.countTotalMistakes(user.getId()));
        assertEquals(0, statsMapper.countMasteredMistakes(user.getId()));

        // 5) update_time 自动刷新（updateFill）
        String originalUpdateTime = loadedWord.getUpdateTime().toString();
        Word patch = new Word();
        patch.setId(word.getId());
        patch.setTranslation("v. 放弃；抛弃（已更新）");
        assertEquals(1, wordMapper.updateById(patch));
        Word afterUpdate = wordMapper.selectById(word.getId());
        assertEquals("v. 放弃；抛弃（已更新）", afterUpdate.getTranslation());
        assertTrue(afterUpdate.getUpdateTime().toString().compareTo(originalUpdateTime) >= 0,
                "updateById 后 updateTime 必须被刷新");
        assertEquals(loadedWord.getCreateTime(), afterUpdate.getCreateTime(),
                "update 不得改动 createTime");
    }
}
