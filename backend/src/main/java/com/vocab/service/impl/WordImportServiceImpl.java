package com.vocab.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocab.common.BizException;
import com.vocab.common.Constants;
import com.vocab.common.ErrorCode;
import com.vocab.entity.Word;
import com.vocab.entity.WordBook;
import com.vocab.mapper.WordBookMapper;
import com.vocab.mapper.WordMapper;
import com.vocab.dto.response.ImportResult;
import com.vocab.service.WordImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 词库导入实现。
 *
 * <p><b>本类是全项目「JSONL → Word」映射的唯一实现</b>（design.md §7.1）。
 * 管理端 multipart 上传与种子自举都汇聚到 {@link #parseLine(String, Long)}
 * ——这保证两条入口写入的词在字段规则上绝不可能漂移。
 *
 * <p><b>批量写入</b>：{@code saveBatch(list, SAVE_BATCH_SIZE)} 分批，
 * 每批固定 1000 行（database-guidelines.md 禁止循环单条插入）。
 * 三份词书共 22761 行，按 1000 分批约 23 批。
 *
 * <p><b>说明：为何逐行导入不去重</b>——源文件本身含重复单词行
 * （实测 CET4 7508 行中仅 4544 个不同单词，如 {@code absorb} 出现 3 次且释义各异，
 * 为多来源合并所致）。需求/prd 要求 {@code word_count} 严格等于**文件行数**
 * （7508 / 5651 / 9602），故逐行入库、不做单词去重；重复词的「同词多义项」
 * 由学习侧按 {@code word_id} 各自独立处理，不影响进度表唯一键
 * （{@code word_progress} 以 {@code word_id} 为准）。
 */
@Service
public class WordImportServiceImpl implements WordImportService {

    private static final Logger log = LoggerFactory.getLogger(WordImportServiceImpl.class);

    private final WordMapper wordMapper;
    private final WordBookMapper wordBookMapper;
    private final ObjectMapper objectMapper;

    public WordImportServiceImpl(WordMapper wordMapper,
                                 WordBookMapper wordBookMapper,
                                 ObjectMapper objectMapper) {
        this.wordMapper = wordMapper;
        this.wordBookMapper = wordBookMapper;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------
    // 唯一解析实现
    // ---------------------------------------------------------------

    @Override
    public Word parseLine(String jsonLine, Long bookId) {
        if (jsonLine == null) {
            return null;
        }
        String line = jsonLine.trim();
        if (line.isEmpty()) {
            return null;
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(line);
        } catch (Exception e) {
            // 坏行只跳过不抛出——契约 §8.1「格式错误行跳过并计数」。
            // 不记录整行内容（可能很长），只记长度辅助定位。
            log.debug("跳过非法 JSONL 行 length={}", line.length());
            return null;
        }

        String word = text(node, "word");
        if (!StringUtils.hasText(word)) {
            // word 是唯一必填字段（S3 表 7.3 NOT NULL），缺失无法构成一条知识
            log.debug("跳过缺少 word 字段的行");
            return null;
        }
        word = word.trim();

        String phonetic = text(node, "uk");
        String translation = joinTranslations(node.path("translations"));
        String example = firstSentence(node.path("sentences"));

        // 超列宽的行跳过，而不是让数据库抛 Data too long 中断整批 saveBatch。
        // 实测三份源数据最大值：word=18 / uk=58 / translation=126 / example=200，
        // 全部在以下上限内，因此本条路径正常不会被触发（属防御性校验）。
        if (word.length() > Constants.WORD_MAX_LENGTH
                || phonetic.length() > Constants.PHONETIC_MAX_LENGTH
                || translation.length() > Constants.TRANSLATION_MAX_LENGTH
                || example.length() > Constants.EXAMPLE_MAX_LENGTH) {
            log.debug("跳过超列宽行 word={} wordLen={} transLen={} exLen={}",
                    word, word.length(), translation.length(), example.length());
            return null;
        }

        Word entity = new Word();
        entity.setBookId(bookId);
        entity.setWord(word);
        entity.setPhonetic(phonetic);
        entity.setTranslation(translation);
        // definition / audioUrl 一期留空：源数据无英文释义；需求清单 §7.4 不做发音播放
        entity.setExample(example);
        return entity;
    }

    /**
     * 拼接释义：逐项 {@code "{type}. {translation}"}，多项用 {@code ；} 连接。
     *
     * <p>示例：{@code [{"v","放弃"},{"n","放任"}] → "v. 放弃；n. 放任"}。
     * {@code type} 缺失时只保留释义，避免出现孤立的 ". "。
     */
    private String joinTranslations(JsonNode translations) {
        if (translations == null || !translations.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode item : translations) {
            String type = text(item, "type");
            String translation = text(item, "translation");
            if (!StringUtils.hasText(translation)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('；');
            }
            if (StringUtils.hasText(type)) {
                builder.append(type.trim()).append(". ").append(translation.trim());
            } else {
                builder.append(translation.trim());
            }
        }
        return builder.toString();
    }

    /** 取第一条例句的英文（{@code sentences[0].sentence}）。 */
    private String firstSentence(JsonNode sentences) {
        if (sentences == null || !sentences.isArray() || sentences.isEmpty()) {
            return "";
        }
        JsonNode first = sentences.get(0);
        String sentence = text(first, "sentence");
        return sentence == null ? "" : sentence.trim();
    }

    /** 安全取文本字段：非文本 / 缺失返回空串（绝不让 null 流传下游）。 */
    private String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.asText("");
    }

    // ---------------------------------------------------------------
    // 入口一：管理端 multipart 上传
    // ---------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportResult importFromStream(InputStream in, String name, String category) {
        if (in == null) {
            throw new BizException(ErrorCode.IMPORT_FILE_INVALID);
        }
        validateImportParams(name, category);

        String resolvedName = StringUtils.hasText(name) ? name.trim() : fallbackBookName(category);
        String resolvedCategory = category.trim().toUpperCase();

        // 词书**延迟到第一行合法数据时才创建**。
        // 否则「整份文件全是坏行」时先建出的空词书会留下来——
        // 即使本方法整体回滚（
        // 调用方非事务时不会回滚），库里也会多出一本 0 词的空壳。
        // 实测暴露：importFromStream 抛 2004 后词书仍在。
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return importValidLines(reader.lines(), resolvedName, resolvedCategory);
        } catch (java.io.UncheckedIOException e) {
            // 读取流本身失败（网络中断/文件损坏）→ 整个导入失败
            log.error("词库导入读取失败 name={}", resolvedName, e);
            throw new BizException(ErrorCode.IMPORT_FILE_INVALID);
        } catch (java.io.IOException e) {
            log.error("词库导入读取失败 name={}", resolvedName, e);
            throw new BizException(ErrorCode.IMPORT_FILE_INVALID);
        }
    }

    /**
     * 先逐行解析，遇到第一条合法数据才建词书，然后把它连同已解析的首行一起写入。
     *
     * <p>这样「全坏行」的输入不会留下空词书——一次性把「是否有合法行」与
     * 「创建词书」两件事合并，而不是先建词书再事后回滚。
     */
    private ImportResult importValidLines(Stream<String> lines, String name, String category) {
        Iterator<String> iterator = lines.iterator();

        int skipped = 0;
        Word firstValid = null;
        while (iterator.hasNext()) {
            String line = iterator.next();
            // 词书 id 尚不存在，先以 null 解析出字段（映射与 id 无关）
            Word word = parseLine(line, null);
            if (word == null) {
                skipped++;
                continue;
            }
            firstValid = word;
            break;
        }

        if (firstValid == null) {
            // 整个文件没有任何合法行 → 整体格式非法（契约 §8.1 的 2004）
            log.warn("词库导入无任何合法行 name={} skipped={}", name, skipped);
            throw new BizException(ErrorCode.IMPORT_FILE_INVALID);
        }

        WordBook book = new WordBook();
        book.setName(name);
        book.setCategory(category);
        book.setDescription(null);
        book.setWordCount(0);
        wordBookMapper.insert(book);

        int success = 0;
        int batchSkipped = 0;
        List<Word> batch = new ArrayList<>(Constants.SAVE_BATCH_SIZE);

        firstValid.setBookId(book.getId());
        batch.add(firstValid);
        while (iterator.hasNext()) {
            String line = iterator.next();
            Word word = parseLine(line, book.getId());
            if (word == null) {
                batchSkipped++;
                continue;
            }
            batch.add(word);
            if (batch.size() >= Constants.SAVE_BATCH_SIZE) {
                insertBatch(batch);
            }
        }
        insertBatch(batch);

        int actual = correctWordCount(book.getId());
        int totalSkipped = skipped + batchSkipped;
        log.info("词库导入完成 name={} bookId={} skipped={} wordCount={}",
                name, book.getId(), totalSkipped, actual);
        // 成功数取真实 COUNT（与 word_count 同源），避免「批量插入返回行数」
        // 与「实际入库行数」出现理论上的偏差。
        return new ImportResult(book.getId(), actual, totalSkipped);
    }

    /** 写一批（空批直接跳过），返回实际写入行数。 */
    private int insertBatch(List<Word> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        int rows = wordMapper.insertBatch(batch);
        batch.clear();
        return rows;
    }

    private void validateImportParams(String name, String category) {
        if (!StringUtils.hasText(category)
                || !Constants.BOOK_CATEGORIES.contains(category.trim().toUpperCase())) {
            // 分类不在白名单属参数错误（1005），不是文件格式错误（2004）
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                    "category 仅支持 " + String.join(" / ", Constants.BOOK_CATEGORIES));
        }
        if (StringUtils.hasText(name) && name.trim().length() > Constants.BOOK_NAME_MAX_LENGTH) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                    "词书名最多 " + Constants.BOOK_NAME_MAX_LENGTH + " 字");
        }
    }

    private String fallbackBookName(String category) {
        return category.trim().toUpperCase() + " 词书";
    }

    // ---------------------------------------------------------------
    // 入口二：种子自举（与入口一共用本方法）
    // ---------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportResult importLines(Stream<String> lines, Long bookId) {
        if (lines == null) {
            throw new BizException(ErrorCode.IMPORT_FILE_INVALID);
        }
        if (bookId == null || wordBookMapper.selectById(bookId) == null) {
            throw new BizException(ErrorCode.BOOK_NOT_FOUND);
        }

        int success = 0;
        int skipped = 0;
        List<Word> batch = new ArrayList<>(Constants.SAVE_BATCH_SIZE);

        Iterator<String> iterator = lines.iterator();
        while (iterator.hasNext()) {
            String line = iterator.next();
            Word word = parseLine(line, bookId);
            if (word == null) {
                skipped++;
                continue;
            }
            batch.add(word);
            if (batch.size() >= Constants.SAVE_BATCH_SIZE) {
                // 分批写入：每批 1000 条，避免循环单条插入（规范红线）
                insertBatch(batch);
            }
        }
        insertBatch(batch);

        // 导入完成后按真实行数校正 word_count，而不是靠自增累加
        // （累加一旦中途失败就会漂移；COUNT 是唯一真相）。
        int actual = correctWordCount(bookId);

        log.info("词库导入完成 bookId={} skipped={} wordCount={}",
                bookId, skipped, actual);
        // success 取真实 COUNT，与 word_count 同源
        return new ImportResult(bookId, actual, skipped);
    }

    /**
     * 校正 {@code word_count} = 该词书真实未删除单词数。
     *
     * <p>用 {@code COUNT(*)} 而非自增累加：即使中途异常重试，
     * 计数也不会漂移（design.md §7.3）。
     *
     * @return 校正后的真实词数
     */
    @Transactional(rollbackFor = Exception.class)
    public int correctWordCount(Long bookId) {
        Long count = wordMapper.selectCount(Wrappers.<Word>lambdaQuery()
                .eq(Word::getBookId, bookId));
        int wordCount = count == null ? 0 : count.intValue();

        WordBook update = new WordBook();
        update.setId(bookId);
        update.setWordCount(wordCount);
        wordBookMapper.updateById(update);
        return wordCount;
    }
}
