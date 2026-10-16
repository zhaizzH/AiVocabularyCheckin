package com.vocab.service;

import com.vocab.dto.response.ImportResult;
import com.vocab.entity.Word;

import java.io.InputStream;
import java.util.stream.Stream;

/**
 * 词库导入服务：JSONL 解析 + 批量写库。
 *
 * <p><b>单一解析实现，双入口复用</b>（design.md §7.2）：
 * <ul>
 *   <li>管理端上传 {@code POST /api/admin/books/import}（multipart）→ {@link #importFromStream}</li>
 *   <li>种子自举 {@code VocabSeedRunner}（从 {@code data/vocab-source/*.jsonl} 读）
 *       → 打开文件流后同样调用 {@link #importFromStream} 的底层
 *       {@link #importLines}</li>
 * </ul>
 * 两者<b>必须</b>走同一个 {@link #parseLine(String, Long)}——否则两套映射代码必然漂移，
 * 出现「种子导入的词与上传导入的词字段规则不同」的隐蔽缺陷。
 */
public interface WordImportService {

    /**
     * 解析一行 JSONL 为单词实体（唯一的字段映射实现）。
     *
     * <p>映射规则（契约 §8.1，research/00-environment-and-contract-gaps.md §2.2）：
     * <ul>
     *   <li>{@code word → word}（必填，缺失视为坏行）</li>
     *   <li>{@code uk → phonetic}（源数据没有 {@code phonetic} 键，音标在 {@code us}/{@code uk} 里；
     *       契约要求英式，故取 {@code uk}）</li>
     *   <li>{@code translations[] → translation}：逐项拼 {@code "{type}. {translation}"}，
     *       多项用 {@code ；} 连接</li>
     *   <li>{@code sentences[0].sentence → example}</li>
     *   <li>{@code definition} / {@code audioUrl} 一期留空（需求清单 §7.4/§7.5）</li>
     * </ul>
     *
     * @param jsonLine 一行 JSONL 文本（不含换行符）
     * @param bookId   目标词书 id
     * @return 可写入的 {@link Word}；行非法（JSON 解析失败 / {@code word} 为空 /
     *         字段超列宽）时返回 {@code null}，由调用方计入 {@code skipped}
     */
    Word parseLine(String jsonLine, Long bookId);

    /**
     * 管理端上传入口：新建词书 → 逐行导入 → 校正 {@code word_count}。
     *
     * <p>整体文件不可读（流为 null）→ 2004（{@code IMPORT_FILE_INVALID}）。
     * 单行格式错误只跳过并计数，<b>不中断</b>整个导入（契约 §8.1「格式错误行跳过并计数」）。
     *
     * @param in       JSONL 输入流（调用方负责关闭）
     * @param name     词书名（空白时用「{category} 词书」兜底）
     * @param category 分类：CET4 / CET6 / KAOYAN
     * @return 导入结果（含新建的词书 id、成功 / 跳过行数）
     */
    ImportResult importFromStream(InputStream in, String name, String category);

    /**
     * 种子导入的底层入口：对一个<b>已存在</b>的词书逐行导入。
     *
     * <p>按 {@code SAVE_BATCH_SIZE} 分批 {@code saveBatch}，避免循环单条插入
     * （database-guidelines.md 红线），也避免把三份 7–10MB 文件整体读进内存。
     * 结束时校正该词书的 {@code word_count}。
     *
     * @param lines  JSONL 行流（调用方负责关闭）
     * @param bookId 目标词书 id
     */
    ImportResult importLines(Stream<String> lines, Long bookId);
}
