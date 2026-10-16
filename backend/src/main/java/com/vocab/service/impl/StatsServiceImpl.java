package com.vocab.service.impl;

import com.vocab.common.Constants;
import com.vocab.common.MasteryRates;
import com.vocab.dto.response.HeatmapDay;
import com.vocab.dto.response.HeatmapResponse;
import com.vocab.dto.response.MasteryPoint;
import com.vocab.dto.response.MasteryResponse;
import com.vocab.mapper.StatsMapper;
import com.vocab.service.StatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计服务实现（契约 §7 + design.md §4.5）。
 *
 * <p><b>本类只做两件事：补零、分档。</b>所有计数都在
 * {@code StatsMapper} 的 SQL 内聚合完成（需求清单 §5.1 红线：
 * 「SQL 聚合完成，不加载原始记录」），本类拿到的只是
 * 「有数据的那几天」——最多几十行。若把明细查出来在这里统计，
 * 一个学习一年的用户会把几万行流水拉进 JVM，既慢又违背红线。
 *
 * <p><b>为什么补零放在 Java 而不放 SQL</b>：生成连续日期序列在
 * MySQL 里要靠递归 CTE（{@code WITH RECURSIVE}）或数字辅助表，
 * 而 H2 的 MySQL 兼容模式对递归 CTE 的支持与 MySQL 有差异——
 * 同一份 SQL 两边跑会出方言问题。交给 {@code LocalDate} 循环
 * 反而更短、更可控，也让「序列长度」「最后一天是不是今天」
 * 这类规则可以被单测直接注入固定日期验证。
 */
@Service
public class StatsServiceImpl implements StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsServiceImpl.class);

    /**
     * 热力图分档阈值（契约 §7.1）：0=未打卡 / 1=1–10 词 / 2=11–20 词 / 3=21+ 词。
     *
     * <p>分档放在 Java 是因为它是<b>展示规则</b>（对应前端的 4 档色阶），
     * 不是数据聚合；写进 SQL 会让 {@code CASE WHEN} 与色阶耦合，
     * 日后前端想改 5 档色阶还得动 SQL。
     */
    private static final int LEVEL_MAX_LOW = 10;
    private static final int LEVEL_MAX_MID = 20;

    private final StatsMapper statsMapper;
    private final Clock clock;

    public StatsServiceImpl(StatsMapper statsMapper, Clock clock) {
        this.statsMapper = statsMapper;
        this.clock = clock;
    }

    // ---------------------------------------------------------------
    // §7.1 打卡热力图
    // ---------------------------------------------------------------

    @Override
    public HeatmapResponse heatmap(Long userId, Integer weeks) {
        LocalDate today = LocalDate.now(clock);
        int resolvedWeeks = weeks == null || weeks <= 0
                ? Constants.DEFAULT_HEATMAP_WEEKS
                : Math.min(weeks, Constants.MAX_HEATMAP_WEEKS);
        int totalDays = resolvedWeeks * 7;
        // 序列最后一天是今天，故起点 = 今天 − (totalDays − 1)。
        // 区间 [today−(n−1), today] 共 n 天（闭区间长度 = n）。
        // 用「−（n−1）」而非「−n」是为了让总长度精确等于 totalDays：
        // 区间 [today−83, today] 共 84 天。
        LocalDate since = today.minusDays(totalDays - 1L);

        Map<LocalDate, Integer> counts = new HashMap<>();
        for (HeatmapDay day : statsMapper.selectHeatmap(userId, since)) {
            counts.put(day.getDate(), safeInt(day.getWordCount()));
        }

        List<HeatmapDay> days = new ArrayList<>(totalDays);
        for (int i = 0; i < totalDays; i++) {
            LocalDate date = since.plusDays(i);
            int wordCount = counts.getOrDefault(date, 0);
            days.add(new HeatmapDay(date, wordCount, level(wordCount)));
        }

        log.info("热力图 userId={} weeks={} points={}", userId, resolvedWeeks, days.size());
        return new HeatmapResponse(days);
    }

    /**
     * 打卡分档：0 词 → 0 档（未打卡）；1–10 → 1；11–20 → 2；21+ → 3。
     *
     * <p>注意 0 档与「未打卡」不是一回事——单词数 0 也可能是
     * 「打卡当天没学新词」，但契约 §7.1 只按词数分档，
     * 故本方法只认词数，不额外查 {@code check_in_date}。
     */
    private int level(int wordCount) {
        if (wordCount <= 0) {
            return 0;
        }
        if (wordCount <= LEVEL_MAX_LOW) {
            return 1;
        }
        if (wordCount <= LEVEL_MAX_MID) {
            return 2;
        }
        return 3;
    }

    // ---------------------------------------------------------------
    // §7.2 掌握度曲线
    // ---------------------------------------------------------------

    @Override
    public MasteryResponse mastery(Long userId, Integer days) {
        LocalDate today = LocalDate.now(clock);
        int resolvedDays = days == null || days <= 0
                ? Constants.DEFAULT_MASTERY_DAYS
                : Math.min(days, Constants.MAX_MASTERY_DAYS);
        LocalDate since = today.minusDays(resolvedDays - 1L);

        // 每日的学习/复习/新增错题数（SQL 聚合）。
        Map<LocalDate, MasteryPoint> daily = new HashMap<>();
        for (MasteryPoint point : statsMapper.selectMasteryDaily(userId, since)) {
            daily.put(point.getDate(), point);
        }

        // 掌握度 = 当前已掌握 / 当前总数 —— 当日快照口径，整条曲线用同一个值。
        // 每个日期点都算一次「当日快照」需要为每一天重放错题状态，
        // 而契约公式只认「已掌握 / 总错题」这个存量比例，故取当前快照即可
        // （见 MasteryResponse 类注释与 MasteryRates 的说明）。
        int masteryRate = MasteryRates.percent(
                safeInt(statsMapper.countMasteredMistakes(userId)),
                safeInt(statsMapper.countTotalMistakes(userId)));

        List<MasteryPoint> points = new ArrayList<>(resolvedDays);
        for (int i = 0; i < resolvedDays; i++) {
            LocalDate date = since.plusDays(i);
            MasteryPoint existing = daily.get(date);
            if (existing == null) {
                // 补零：那一天没有任何学习活动，折线图该点为 0。
                points.add(new MasteryPoint(date, 0, 0, 0, masteryRate));
            } else {
                existing.setMasteryRate(masteryRate);
                points.add(existing);
            }
        }

        log.info("掌握度曲线 userId={} days={} points={} masteryRate={}",
                userId, resolvedDays, points.size(), masteryRate);
        return new MasteryResponse(points);
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
