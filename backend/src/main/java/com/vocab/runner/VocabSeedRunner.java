package com.vocab.runner;

import com.vocab.entity.WordBook;
import com.vocab.mapper.WordBookMapper;
import com.vocab.service.WordImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 种子词库自举导入（design.md §7.2 入口二）。
 *
 * <p><b>只在显式开启时运行</b>：由 {@code vocab.seed=true} 触发
 * （{@link ConditionalOnProperty}），默认 false——
 * 否则每次启动都会重跑 22761 行导入，既慢又会产生重复词书。
 *
 * <pre>
 * mvn spring-boot:run -Dspring-boot.run.profiles=local \
 *   -Dspring-boot.run.arguments=--vocab.seed=true
 * </pre>
 *
 * <p><b>幂等</b>：若同名（name + category）词书已存在，跳过该书而不是再建一本——
 * 这样误跑两次也不会得到 6 本词书。需要重导时先按 R3 回滚点
 * （{@code DELETE FROM word WHERE book_id=?}）删除后重跑。
 *
 * <p><b>复用同一套解析</b>：本类只负责「打开文件 → 建词书 → 交给
 * {@link WordImportService#importLines}」，字段映射全部发生在
 * {@code WordImportServiceImpl.parseLine}，与管理端上传完全同一份代码
 * （design.md §7.2「两者必须调用同一个 parseLine」）。
 */
@Component
@ConditionalOnProperty(name = "vocab.seed", havingValue = "true")
public class VocabSeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(VocabSeedRunner.class);

    /** 种子清单：文件名（相对 seed-dir）/ 词书名 / 分类 / 期望行数（G3 闸口校验基准）。 */
    private static final List<SeedSpec> SEEDS = List.of(
            new SeedSpec("CET4.jsonl", "四级核心词汇", "CET4", 7508),
            new SeedSpec("CET6.jsonl", "六级核心词汇", "CET6", 5651),
            new SeedSpec("KAOYAN.jsonl", "考研核心词汇", "KAOYAN", 9602));

    private final WordImportService wordImportService;
    private final WordBookMapper wordBookMapper;
    private final String seedDir;

    public VocabSeedRunner(WordImportService wordImportService,
                           WordBookMapper wordBookMapper,
                           @Value("${vocab.seed-dir:../data/vocab-source}") String seedDir) {
        this.wordImportService = wordImportService;
        this.wordBookMapper = wordBookMapper;
        this.seedDir = seedDir;
    }

    @Override
    public void run(String... args) {
        Path baseDir = Paths.get(seedDir).toAbsolutePath().normalize();
        log.info("开始种子词库导入 seedDir={}", baseDir);

        List<String> failures = new ArrayList<>();
        for (SeedSpec spec : SEEDS) {
            try {
                seedOne(baseDir, spec);
            } catch (RuntimeException e) {
                // 单本失败不阻断其余两本；最后统一汇总并以非零退出码暴露
                log.error("种子导入失败 file={} reason={}", spec.fileName(), e.getMessage(), e);
                failures.add(spec.fileName() + ": " + e.getMessage());
            }
        }

        if (!failures.isEmpty()) {
            // 抛异常让 Spring Boot 以非零码退出——CI/脚本能立刻看到失败，
            // 而不是「日志里有 ERROR 但进程退出码为 0」这种半失败状态。
            throw new IllegalStateException("种子词库导入存在失败项: " + String.join("; ", failures));
        }
        log.info("种子词库导入全部完成");
    }

    private void seedOne(Path baseDir, SeedSpec spec) {
        WordBook existing = wordBookMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<WordBook>lambdaQuery()
                        .eq(WordBook::getName, spec.bookName())
                        .eq(WordBook::getCategory, spec.category())
                        .last("LIMIT 1"));
        if (existing != null) {
            log.info("种子词书已存在，跳过 file={} bookId={} wordCount={}",
                    spec.fileName(), existing.getId(), existing.getWordCount());
            return;
        }

        Path file = baseDir.resolve(spec.fileName());
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("词库源文件不存在: " + file);
        }

        WordBook book = new WordBook();
        book.setName(spec.bookName());
        book.setCategory(spec.category());
        book.setDescription(null);
        book.setWordCount(0);
        wordBookMapper.insert(book);

        long start = System.currentTimeMillis();
        try (InputStream in = Files.newInputStream(file);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            var result = wordImportService.importLines(reader.lines(), book.getId());
            long cost = System.currentTimeMillis() - start;
            log.info("种子词书导入完成 book={} category={} bookId={} success={} skipped={} 期望={} 耗时={}ms",
                    spec.bookName(), spec.category(), book.getId(),
                    result.getSuccess(), result.getSkipped(), spec.expectedLines(), cost);

            // G3 闸口：行数必须严格一致。不一致就报错——词库是本项目的数据基石，
            // 静默少几行会让「7508 词的词书」变成不可信的中间态。
            if (result.getSuccess() != spec.expectedLines()) {
                throw new IllegalStateException(String.format(
                        "%s 导入行数不符：实际 %d，期望 %d", spec.fileName(),
                        result.getSuccess(), spec.expectedLines()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取词库源文件失败: " + file, e);
        }
    }

    /** 一份种子词库的规格说明。 */
    private record SeedSpec(String fileName, String bookName, String category, int expectedLines) {
    }
}
