package com.vocab.service;

import com.vocab.common.Constants;
import com.vocab.dto.response.HeatmapDay;
import com.vocab.dto.response.HeatmapResponse;
import com.vocab.dto.response.MasteryPoint;
import com.vocab.dto.response.MasteryResponse;
import com.vocab.entity.MistakeEntry;
import com.vocab.entity.StudyRecord;
import com.vocab.entity.User;
import com.vocab.entity.UserWordBook;
import com.vocab.entity.Word;
import com.vocab.entity.WordBook;
import com.vocab.mapper.MistakeEntryMapper;
import com.vocab.mapper.StatsMapper;
import com.vocab.mapper.StudyRecordMapper;
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

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统计服务集成测试（阶段 9 闸口，契约 §7）。
 *
 * <p>本类证明三件事，全部是「SQL 聚合 + Java 补零/分档」这条职责边界的可观测后果：
 * <ol>
 *   <li><b>日期序列连续且长度恒定</b>：84 点 / 30 点，相邻点差 1 天，末点为今天。
 *       这是前端日历格与折线图的硬前提——少一天就整体错位。</li>
 *   <li><b>{@code level} 与 {@code wordCount} 严格匹配分档</b>：0 / 1–10 / 11–20 / 21+
 *       四个档位的边界值（1、10、11、20、21）逐一验证，而不是只测中间值。</li>
 *   <li><b>聚合确实发生在 SQL</b>：同一天灌入多条流水，mapper 只返回 1 行；
 *       若有人把 SQL 改成「查明细回 Java 统计」，行数断言会立刻失败
 *       （需求清单 §5.1 红线，也是本阶段闸口的第三条验证项）。</li>
 * </ol>
 *
 * <p>时间全部来自注入的 {@link FixedClock}（默认今天 = 2026-10-16）：
 * 「近 84 天」「近 30 天」这类用例若用系统时钟，跑在不同日期会有不同结果。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestClockConfig.class)
class StatsServiceImplTest {

    private static final LocalDate TODAY = FixedClock.DEFAULT_DATE;

    @Autowired
    private StatsService statsService;

    @Autowired
    private StatsMapper statsMapper;

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

    @Autowired
    private UserWordBookMapper userWordBookMapper;

    private User user;
    private WordBook book;

    @BeforeEach
    void setUp() {
        FixedClock.reset();

        user = new User();
        user.setUsername("stats_" + System.nanoTime());
        user.setPasswordHash("$2a$10$fakeHash");
        user.setNickname("统计测试");
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

    // ===============================================================
    //  §7.1 热力图：连续日期序列 + 分档
    // ===============================================================

    @Test
    @DisplayName("§7.1 默认 12 周 → 恰好 84 个点，日期连续升序，末点为今天")
    void heatmapDefaultReturns84ContiguousDays() {
        HeatmapResponse response = statsService.heatmap(user.getId(), null);

        assertEquals(84, response.getDays().size(), "12 周 × 7 天 = 84，缺一天日历格就错位");

        List<HeatmapDay> days = response.getDays();
        assertEquals(TODAY.minusDays(83), days.get(0).getDate(), "首点 = 今天 − 83 天");
        assertEquals(TODAY, days.get(83).getDate(), "末点必须是今天");
        for (int i = 1; i < days.size(); i++) {
            assertEquals(days.get(i - 1).getDate().plusDays(1), days.get(i).getDate(),
                    "日期序列必须逐日连续，第 " + i + " 个点断开");
        }
    }

    @Test
    @DisplayName("§7.1 weeks=1 → 7 个点；weeks=4 → 28 个点（长度 = weeks × 7）")
    void heatmapLengthFollowsWeeksParam() {
        assertEquals(7, statsService.heatmap(user.getId(), 1).getDays().size());
        assertEquals(28, statsService.heatmap(user.getId(), 4).getDays().size());
    }

    @Test
    @DisplayName("§7.1 weeks 为 0/负数时回落默认 12 周（前端初始态常传 0）")
    void heatmapFallsBackToDefaultOnNonPositiveWeeks() {
        assertEquals(84, statsService.heatmap(user.getId(), 0).getDays().size());
        assertEquals(84, statsService.heatmap(user.getId(), -3).getDays().size());
    }

    @Test
    @DisplayName("§7.1 weeks 过大时被上限截断（防 weeks=1000000 撑爆内存与响应体）")
    void heatmapClampsExcessiveWeeks() {
        int capped = statsService.heatmap(user.getId(), 1_000_000).getDays().size();
        assertEquals(Constants.MAX_HEATMAP_WEEKS * 7, capped);
    }

    @Test
    @DisplayName("§7.1 无任何学习数据 → 仍返回 84 个点且 level 全为 0（空态判定交给前端）")
    void heatmapOnEmptyDataReturnsZeroLevels() {
        HeatmapResponse response = statsService.heatmap(user.getId(), null);

        assertEquals(84, response.getDays().size(), "空数据也必须返回完整日期序列");
        for (HeatmapDay day : response.getDays()) {
            assertEquals(0, day.getWordCount(), day.getDate() + " 无数据应为 0 词");
            assertEquals(0, day.getLevel(), day.getDate() + " 0 词应为 0 档");
        }
    }

    @Test
    @DisplayName("§7.1 level 分档边界：1/10→1 档，11/20→2 档，21→3 档，未打卡→0 档")
    void heatmapLevelBinariesAtBoundaries() {
        // 每个日期灌入不同数量的「已打卡」学习流水，覆盖全部边界值。
        seedCheckedInWords(TODAY, 21);            // 21+ → 3
        seedCheckedInWords(TODAY.minusDays(1), 20); // 20 → 2
        seedCheckedInWords(TODAY.minusDays(2), 11); // 11 → 2
        seedCheckedInWords(TODAY.minusDays(3), 10); // 10 → 1
        seedCheckedInWords(TODAY.minusDays(4), 1);  // 1  → 1
        // TODAY-5 刻意不灌数据 → 0 词 0 档

        List<HeatmapDay> days = statsService.heatmap(user.getId(), 1).getDays();
        LocalDate since = TODAY.minusDays(6);

        assertEquals(3, levelOf(days, TODAY), "21 词 → 3 档");
        assertEquals(2, levelOf(days, TODAY.minusDays(1)), "20 词 → 2 档（上边界）");
        assertEquals(2, levelOf(days, TODAY.minusDays(2)), "11 词 → 2 档（下边界）");
        assertEquals(1, levelOf(days, TODAY.minusDays(3)), "10 词 → 1 档（上边界）");
        assertEquals(1, levelOf(days, TODAY.minusDays(4)), "1 词 → 1 档（下边界）");
        assertEquals(0, levelOf(days, TODAY.minusDays(5)), "无数据 → 0 档");

        // 词数一并核对，防止「分档对但词数错」这种哑故障。
        assertEquals(21, wordCountOf(days, TODAY));
        assertEquals(10, wordCountOf(days, TODAY.minusDays(3)));
        assertEquals(0, wordCountOf(days, TODAY.minusDays(5)));
        assertEquals(since, days.get(0).getDate());
    }

    @Test
    @DisplayName("§7.1 只统计已打卡的日子（check_in_date 为 NULL 的流水不进热力图）")
    void heatmapIgnoresRecordsWithoutCheckInDate() {
        // 学习流水存在但从未打卡（§3.2 上报时 check_in_date 为 NULL）。
        seedStudyRecord(TODAY, 0L, Constants.STUDY_TYPE_STUDY, null);

        HeatmapDay today = daysMap(statsService.heatmap(user.getId(), 1)).get(TODAY);
        assertEquals(0, today.getWordCount(), "未打卡的日子不应计入热力图");
        assertEquals(0, today.getLevel());
    }

    // ===============================================================
    //  §7.2 掌握度曲线：连续日期序列 + 掌握度
    // ===============================================================

    @Test
    @DisplayName("§7.2 默认 30 天 → 恰好 30 个点，日期连续，末点为今天")
    void masteryDefaultReturns30ContiguousPoints() {
        MasteryResponse response = statsService.mastery(user.getId(), null);

        assertEquals(30, response.getPoints().size(), "默认近 30 天应有 30 个点");
        List<MasteryPoint> points = response.getPoints();
        assertEquals(TODAY.minusDays(29), points.get(0).getDate());
        assertEquals(TODAY, points.get(29).getDate());
        for (int i = 1; i < points.size(); i++) {
            assertEquals(points.get(i - 1).getDate().plusDays(1), points.get(i).getDate(),
                    "日期序列必须逐日连续，第 " + i + " 个点断开");
        }
    }

    @Test
    @DisplayName("§7.2 days=7 → 7 个点；days 非法值回落 30；过大被上限截断")
    void masteryLengthFollowsDaysParam() {
        assertEquals(7, statsService.mastery(user.getId(), 7).getPoints().size());
        assertEquals(30, statsService.mastery(user.getId(), 0).getPoints().size());
        assertEquals(30, statsService.mastery(user.getId(), -1).getPoints().size());
        assertEquals(Constants.MAX_MASTERY_DAYS,
                statsService.mastery(user.getId(), 1_000_000).getPoints().size());
    }

    @Test
    @DisplayName("§7.2 无数据日补零；有数据日取 SQL 聚合值；未灌数据日三计数均为 0")
    void masteryZeroFillsMissingDays() {
        seedStudyRecord(TODAY.minusDays(2), 101L, Constants.STUDY_TYPE_STUDY, null);
        seedStudyRecord(TODAY.minusDays(2), 102L, Constants.STUDY_TYPE_STUDY, null);
        seedStudyRecord(TODAY.minusDays(2), 103L, Constants.STUDY_TYPE_REVIEW, null);

        var byDate = pointsMap(statsService.mastery(user.getId(), 7));

        MasteryPoint day2 = byDate.get(TODAY.minusDays(2));
        assertEquals(2, day2.getLearnedCount(), "当日 2 条 STUDY");
        assertEquals(1, day2.getReviewCount(), "当日 1 条 REVIEW");
        assertEquals(0, day2.getMistakeCount(), "当日无新增错题");

        MasteryPoint idle = byDate.get(TODAY.minusDays(3));
        assertEquals(0, idle.getLearnedCount(), "无数据日应补零");
        assertEquals(0, idle.getReviewCount());
        assertEquals(0, idle.getMistakeCount());
    }

    @Test
    @DisplayName("§7.2 masteryRate = 已掌握 / 总错题 × 100 取整（与手工 SQL 计算一致）")
    void masteryRateMatchesManualCalculation() {
        // 造 2 条复习流水，使「今天」出现在聚合结果里（曲线才有该日点）。
        seedStudyRecord(TODAY, 201L, Constants.STUDY_TYPE_STUDY, null);
        seedStudyRecord(TODAY, 202L, Constants.STUDY_TYPE_REVIEW, null);

        // 4 条错题：2 条已掌握 → 掌握度应为 50。
        seedMistake(201L, true);
        seedMistake(202L, true);
        seedMistake(203L, false);
        seedMistake(204L, false);

        // 手工 SQL：与 Service 独立计算一次，作为交叉验证。
        int total = statsMapper.countTotalMistakes(user.getId());
        int mastered = statsMapper.countMasteredMistakes(user.getId());
        int expected = (int) Math.round(mastered * 100.0 / total);
        assertEquals(4, total);
        assertEquals(2, mastered);
        assertEquals(50, expected);

        var byDate = pointsMap(statsService.mastery(user.getId(), 7));
        assertEquals(expected, byDate.get(TODAY).getMasteryRate(),
                "掌握度必须与手工 SQL 计算结果一致");
        // 存量指标：同日序列各点取同一快照，故补零日也带同样的掌握度。
        assertEquals(expected, byDate.get(TODAY.minusDays(5)).getMasteryRate(),
                "掌握度是存量快照，补零日也应为同一值");
    }

    @Test
    @DisplayName("§7.2 无错题时掌握度取 100（没有未掌握的薄弱点），而非 0")
    void masteryRateIs100WithoutAnyMistake() {
        seedStudyRecord(TODAY, 301L, Constants.STUDY_TYPE_STUDY, null);

        var byDate = pointsMap(statsService.mastery(user.getId(), 7));
        assertEquals(100, byDate.get(TODAY).getMasteryRate(),
                "一条错题都没有时取 100，取 0 会被误读为「一个都没掌握」");
    }

    @Test
    @DisplayName("§7.2 仅复习无新学的日期也必须出现在曲线中（复习不打卡但仍属学习活动）")
    void masteryIncludesReviewOnlyDay() {
        seedStudyRecord(TODAY.minusDays(1), 401L, Constants.STUDY_TYPE_REVIEW, null);

        var byDate = pointsMap(statsService.mastery(user.getId(), 7));
        MasteryPoint reviewOnly = byDate.get(TODAY.minusDays(1));
        assertEquals(0, reviewOnly.getLearnedCount(), "仅复习日 learnedCount 应为 0");
        assertEquals(1, reviewOnly.getReviewCount());
    }

    // ===============================================================
    //  阶段闸口第三条：聚合在 SQL，不加载原始记录
    // ===============================================================

    @Test
    @DisplayName("闸口：同一天灌 30 条流水，聚合查询只返回 1 行（证明确实在 SQL 聚合）")
    void aggregationsReturnOneRowPerDayNotRawRecords() {
        for (int i = 0; i < 30; i++) {
            seedStudyRecord(TODAY, 1000L + i, Constants.STUDY_TYPE_STUDY, null);
        }

        assertEquals(1, statsMapper.selectMasteryDaily(user.getId(), TODAY.minusDays(30)).size(),
                "30 条明细必须聚合成 1 行——若返回 30 行，说明明细被加载到了 Java");
        assertEquals(30, statsMapper.selectMasteryDaily(user.getId(), TODAY.minusDays(30))
                .get(0).getLearnedCount());
    }

    // ===============================================================
    //  夹具
    // ===============================================================

    /** 在 {@code date} 造 {@code count} 个不同的「已学习并已打卡」单词流水。 */
    private void seedCheckedInWords(LocalDate date, int count) {
        for (int i = 0; i < count; i++) {
            Long wordId = insertWord("w" + date + "_" + i);
            seedStudyRecord(date, wordId, Constants.STUDY_TYPE_STUDY, date);
        }
    }

    private void seedStudyRecord(LocalDate studyDate, Long wordId,
                                 String studyType, LocalDate checkInDate) {
        StudyRecord record = new StudyRecord();
        record.setUserId(user.getId());
        record.setBookId(book.getId());
        record.setWordId(wordId);
        record.setStudyType(studyType);
        record.setStudyDate(studyDate);
        record.setCheckInDate(checkInDate);
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

    /** 插一个单词并返回 id（热力图按 word_id 去重计数，必须有真实词行）。 */
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

    private java.util.Map<LocalDate, HeatmapDay> daysMap(HeatmapResponse response) {
        var map = new java.util.HashMap<LocalDate, HeatmapDay>();
        for (HeatmapDay day : response.getDays()) {
            map.put(day.getDate(), day);
        }
        return map;
    }

    private java.util.Map<LocalDate, MasteryPoint> pointsMap(MasteryResponse response) {
        var map = new java.util.HashMap<LocalDate, MasteryPoint>();
        for (MasteryPoint point : response.getPoints()) {
            map.put(point.getDate(), point);
        }
        return map;
    }

    private int levelOf(List<HeatmapDay> days, LocalDate date) {
        return byDate(days).get(date).getLevel();
    }

    private int wordCountOf(List<HeatmapDay> days, LocalDate date) {
        return byDate(days).get(date).getWordCount();
    }

    private java.util.Map<LocalDate, HeatmapDay> byDate(List<HeatmapDay> days) {
        var map = new java.util.HashMap<LocalDate, HeatmapDay>();
        for (HeatmapDay day : days) {
            map.put(day.getDate(), day);
        }
        return map;
    }
}
