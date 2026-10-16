package com.vocab.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vocab.common.BizException;
import com.vocab.common.Constants;
import com.vocab.common.ErrorCode;
import com.vocab.common.PageResult;
import com.vocab.common.Pages;
import com.vocab.dto.response.MistakeMasteredResponse;
import com.vocab.dto.response.MistakeRemoveResponse;
import com.vocab.dto.response.MistakeResponse;
import com.vocab.entity.MistakeEntry;
import com.vocab.entity.Word;
import com.vocab.mapper.MistakeEntryMapper;
import com.vocab.mapper.WordMapper;
import com.vocab.service.MistakeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 错题服务实现（契约 §5 + design.md §4.4）。
 *
 * <p><b>列表为什么不能用一条 JOIN</b>：{@code mistake_entry} 与 {@code word} 本身可以
 * JOIN，但本项目的分页是用 {@code Page} + 分页插件（{@code PaginationInnerInterceptor}）
 * 在<b>单表</b>上做的；手写 JOIN 分页需要再写一套 XML 且容易在 COUNT 语句上出错。
 * 这里采用「先分页查错题 → 一次批量查单词 → 内存拼装」的两段式：
 * SQL 次数恒为 2（与页大小无关），既避免 N+1，也复用成熟的分页插件。
 *
 * <p><b>为什么 time 列用 {@code between} 而不是 {@code DATE()}</b>：
 * 见 {@code StatsMapper} —— 不依赖方言特有的日期函数，H2 与 MySQL 同语义。
 */
@Service
public class MistakeServiceImpl implements MistakeService {

    private static final Logger log = LoggerFactory.getLogger(MistakeServiceImpl.class);

    private final MistakeEntryMapper mistakeEntryMapper;
    private final WordMapper wordMapper;

    public MistakeServiceImpl(MistakeEntryMapper mistakeEntryMapper, WordMapper wordMapper) {
        this.mistakeEntryMapper = mistakeEntryMapper;
        this.wordMapper = wordMapper;
    }

    // ---------------------------------------------------------------
    // 收录原语（被 StudyService / ReviewService 复用）
    // ---------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addOrIncrement(Long userId, Long wordId, String source) {
        MistakeEntry existing = mistakeEntryMapper.selectOne(Wrappers.<MistakeEntry>lambdaQuery()
                .eq(MistakeEntry::getUserId, userId)
                .eq(MistakeEntry::getWordId, wordId)
                .last("LIMIT 1"));

        if (existing == null) {
            MistakeEntry entry = new MistakeEntry();
            entry.setUserId(userId);
            entry.setWordId(wordId);
            entry.setSource(source);
            entry.setWrongCount(1);
            entry.setMastered(0);
            mistakeEntryMapper.insert(entry);
            log.debug("新增错题 userId={} wordId={} source={}", userId, wordId, source);
            return;
        }

        // 已存在：累加错误次数、刷新来源、重置为未掌握。
        // 「已掌握再答错 → 重置为未掌握」是合理推断（design.md §4.4）：
        // 需求清单 §3.1 只规定「重复错 → wrong_count+1 不重复建记录」，
        // 但既然又错了，再次标记为「已掌握」显然与事实不符。
        existing.setWrongCount(safeInt(existing.getWrongCount()) + 1);
        existing.setSource(source);
        existing.setMastered(0);
        mistakeEntryMapper.updateById(existing);
        log.debug("累加错题 userId={} wordId={} wrongCount={} source={}",
                userId, wordId, existing.getWrongCount(), source);
    }

    // ---------------------------------------------------------------
    // 学生端
    // ---------------------------------------------------------------

    @Override
    public PageResult<MistakeResponse> list(Long userId, Integer mastered, long page, long size) {
        int masteredFilter = normalizeMastered(mastered);

        IPage<MistakeEntry> entryPage = mistakeEntryMapper.selectPage(
                new Page<>(Pages.normalizePage(page), Pages.normalizeSize(size)),
                Wrappers.<MistakeEntry>lambdaQuery()
                        .eq(MistakeEntry::getUserId, userId)
                        .eq(MistakeEntry::getMastered, masteredFilter)
                        .orderByDesc(MistakeEntry::getCreateTime)
                        .orderByDesc(MistakeEntry::getId));

        List<MistakeEntry> entries = entryPage.getRecords();
        if (entries == null || entries.isEmpty()) {
            return PageResult.empty(entryPage.getCurrent(), entryPage.getSize());
        }

        // 一次批量取词，避免逐条查询（N+1）。
        List<Long> wordIds = entries.stream().map(MistakeEntry::getWordId).distinct().toList();
        Map<Long, Word> wordMap = new HashMap<>();
        for (Word word : wordMapper.selectBatchIds(wordIds)) {
            wordMap.put(word.getId(), word);
        }

        // 实体 → DTO 在分页边界完成（对外绝不暴露实体）
        return PageResult.of(entryPage, entry -> toResponse(entry, wordMap.get(entry.getWordId())));
    }

    private MistakeResponse toResponse(MistakeEntry entry, Word word) {
        MistakeResponse response = new MistakeResponse();
        response.setMistakeId(entry.getId());
        response.setWordId(entry.getWordId());
        if (word != null) {
            response.setWord(word.getWord());
            response.setPhonetic(word.getPhonetic());
            response.setTranslation(word.getTranslation());
        }
        response.setSource(entry.getSource());
        response.setWrongCount(entry.getWrongCount());
        response.setMastered(isMastered(entry));
        response.setCreateTime(entry.getCreateTime());
        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MistakeMasteredResponse markMastered(Long userId, Long mistakeId) {
        MistakeEntry entry = requireOwned(userId, mistakeId);
        if (isMastered(entry)) {
            // 已经是「已掌握」——幂等场景返回 4002，与契约一致。
            throw new BizException(ErrorCode.MISTAKE_ALREADY_MASTERED);
        }
        entry.setMastered(1);
        mistakeEntryMapper.updateById(entry);
        log.info("标记错题已掌握 userId={} mistakeId={}", userId, mistakeId);
        return new MistakeMasteredResponse(mistakeId, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MistakeRemoveResponse remove(Long userId, Long mistakeId) {
        MistakeEntry entry = requireOwned(userId, mistakeId);
        // @TableLogic：deleteById 实为 UPDATE deleted=1，保留历史行。
        // 这样「移除后重新收录」的新行不会与历史行冲突（本表无 (user_id, word_id)
        // 唯一索引，正是为此，见 design.md §4.4）。
        mistakeEntryMapper.deleteById(entry.getId());
        log.info("移除错题 userId={} mistakeId={}", userId, mistakeId);
        return new MistakeRemoveResponse(mistakeId, true);
    }

    // ---------------------------------------------------------------
    // 内部工具
    // ---------------------------------------------------------------

    /**
     * 取错题并校验归属。
     *
     * <p><b>必须带 userId 条件</b>：否则用户 A 能通过猜 mistakeId 操作 B 的错题。
     * 查询借 {@code @TableLogic} 自动过滤 {@code deleted=0}——
     * 已移除的错题查不到，按 4001 处理（移除后再操作本就应报不存在）。
     *
     * @throws BizException 4001 错题不存在（含不属于当前用户、已被移除）
     */
    private MistakeEntry requireOwned(Long userId, Long mistakeId) {
        MistakeEntry entry = mistakeId == null ? null : mistakeEntryMapper.selectOne(
                Wrappers.<MistakeEntry>lambdaQuery()
                        .eq(MistakeEntry::getId, mistakeId)
                        .eq(MistakeEntry::getUserId, userId)
                        .last("LIMIT 1"));
        if (entry == null) {
            throw new BizException(ErrorCode.MISTAKE_NOT_FOUND);
        }
        return entry;
    }

    /** 入参 mastered 归一化：缺省 0；非 0/1 抛 1005。 */
    private int normalizeMastered(Integer mastered) {
        int value = mastered == null ? 0 : mastered;
        if (value != 0 && value != 1) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                    "mastered 只能为 0 或 1");
        }
        return value;
    }

    private boolean isMastered(MistakeEntry entry) {
        return entry.getMastered() != null && entry.getMastered() == 1;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
