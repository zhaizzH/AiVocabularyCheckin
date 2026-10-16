package com.vocab.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vocab.common.BizException;
import com.vocab.common.ErrorCode;
import com.vocab.common.PageResult;
import com.vocab.common.Pages;
import com.vocab.dto.response.AdminBookResponse;
import com.vocab.dto.response.BookDetailResponse;
import com.vocab.dto.response.BookLearnedCount;
import com.vocab.dto.response.BookResponse;
import com.vocab.dto.response.ImportResult;
import com.vocab.dto.response.WordResponse;
import com.vocab.entity.UserWordBook;
import com.vocab.entity.Word;
import com.vocab.entity.WordBook;
import com.vocab.mapper.StatsMapper;
import com.vocab.mapper.UserWordBookMapper;
import com.vocab.mapper.WordBookMapper;
import com.vocab.mapper.WordMapper;
import com.vocab.service.WordBookService;
import com.vocab.service.WordImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 词书服务实现。
 *
 * <p><b>并发安全</b>：{@code select} 的「已加入」判断有两道防线——
 * 先查（快速失败、给出 2003 正确文案）+ DB 唯一索引
 * {@code uk_user_word_book_user_book} 兜底（并发双击时后到的 insert 撞
 * {@link DuplicateKeyException}，同样转成 2003）。只靠先查会在并发下写出重复行；
 * 只靠捕获唯一键异常又无法区分「已加入」与其它 DB 错误。
 */
@Service
public class WordBookServiceImpl implements WordBookService {

    private static final Logger log = LoggerFactory.getLogger(WordBookServiceImpl.class);

    private final WordBookMapper wordBookMapper;
    private final WordMapper wordMapper;
    private final UserWordBookMapper userWordBookMapper;
    private final StatsMapper statsMapper;
    private final WordImportService wordImportService;

    public WordBookServiceImpl(WordBookMapper wordBookMapper,
                               WordMapper wordMapper,
                               UserWordBookMapper userWordBookMapper,
                               StatsMapper statsMapper,
                               WordImportService wordImportService) {
        this.wordBookMapper = wordBookMapper;
        this.wordMapper = wordMapper;
        this.userWordBookMapper = userWordBookMapper;
        this.statsMapper = statsMapper;
        this.wordImportService = wordImportService;
    }

    // ---------------------------------------------------------------
    // 学生端
    // ---------------------------------------------------------------

    @Override
    public List<BookResponse> listBooks(Long userId, String category) {
        var query = Wrappers.<WordBook>lambdaQuery()
                .orderByAsc(WordBook::getId);
        if (StringUtils.hasText(category)) {
            query.eq(WordBook::getCategory, category.trim().toUpperCase());
        }
        // 词书总量是个位数量级（一期 3 本），不分页是合理的：
        // 契约 §2.1 明确响应是 `list` 数组而非分页结构。
        List<WordBook> books = wordBookMapper.selectList(query);
        if (books.isEmpty()) {
            return Collections.emptyList();
        }

        // 一次性取两个用户维度集合，避免逐一查询造成 N+1：
        //   selectedIds —— 该用户已加入的词书 id
        //   learnedMap  —— 该用户在每本词书的已学词数
        Set<Long> selectedIds = new HashSet<>();
        List<UserWordBook> relations = userWordBookMapper.selectList(
                Wrappers.<UserWordBook>lambdaQuery().eq(UserWordBook::getUserId, userId));
        for (UserWordBook relation : relations) {
            selectedIds.add(relation.getBookId());
        }

        Map<Long, Integer> learnedMap = new HashMap<>();
        List<BookLearnedCount> counts = statsMapper.selectLearnedCountByBook(userId);
        if (counts != null) {
            for (BookLearnedCount count : counts) {
                if (count.getBookId() != null) {
                    learnedMap.put(count.getBookId(), count.getLearnedCount());
                }
            }
        }

        List<BookResponse> result = new ArrayList<>(books.size());
        for (WordBook book : books) {
            result.add(toBookResponse(book, selectedIds.contains(book.getId()),
                    learnedMap.getOrDefault(book.getId(), 0)));
        }
        return result;
    }

    private BookResponse toBookResponse(WordBook book, boolean selected, int learnedCount) {
        BookResponse response = new BookResponse();
        response.setBookId(book.getId());
        response.setName(book.getName());
        response.setCategory(book.getCategory());
        response.setWordCount(book.getWordCount());
        response.setDescription(book.getDescription());
        response.setSelected(selected);
        response.setLearnedCount(learnedCount);
        return response;
    }

    @Override
    public BookDetailResponse bookDetail(Long userId, Long bookId, long page, long size) {
        WordBook book = requireBook(bookId);

        IPage<Word> wordPage = wordMapper.selectPage(
                new Page<>(Pages.normalizePage(page), Pages.normalizeSize(size)),
                Wrappers.<Word>lambdaQuery()
                        .eq(Word::getBookId, bookId)
                        .orderByAsc(Word::getId));

        BookDetailResponse response = new BookDetailResponse();
        response.setBookId(book.getId());
        response.setName(book.getName());
        response.setCategory(book.getCategory());
        response.setWordCount(book.getWordCount());
        response.setDescription(book.getDescription());
        // 实体 → DTO 在分页边界完成（禁止把实体序列化出去）
        response.setWords(PageResult.of(wordPage, this::toWordResponse));
        return response;
    }

    private WordResponse toWordResponse(Word word) {
        WordResponse response = new WordResponse();
        response.setWordId(word.getId());
        response.setWord(word.getWord());
        response.setPhonetic(word.getPhonetic());
        response.setTranslation(word.getTranslation());
        response.setExample(word.getExample());
        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void select(Long userId, Long bookId) {
        requireBook(bookId);

        Long existing = userWordBookMapper.selectCount(Wrappers.<UserWordBook>lambdaQuery()
                .eq(UserWordBook::getUserId, userId)
                .eq(UserWordBook::getBookId, bookId));
        if (existing != null && existing > 0) {
            throw new BizException(ErrorCode.BOOK_ALREADY_SELECTED);
        }

        UserWordBook relation = new UserWordBook();
        relation.setUserId(userId);
        relation.setBookId(bookId);
        try {
            userWordBookMapper.insert(relation);
        } catch (DuplicateKeyException e) {
            // 并发双击：先查与 insert 之间有窗口，由 uk_user_word_book_user_book 兜底。
            // 语义上与「已加入」完全一致，故统一转 2003，而不是冒泡成 9001/500。
            throw new BizException(ErrorCode.BOOK_ALREADY_SELECTED);
        }
        log.info("用户选择词书 userId={} bookId={}", userId, bookId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deselect(Long userId, Long bookId) {
        requireBook(bookId);

        // 物理删除关联行（本表无 deleted 列，全项目唯一）。
        // 刻意**不动** word_progress / study_record / mistake_entry：
        // 移出词书只解除关联，历史学习记录必须保留（契约 §2.4 + 需求清单 §2.4）。
        int deleted = userWordBookMapper.delete(Wrappers.<UserWordBook>lambdaQuery()
                .eq(UserWordBook::getUserId, userId)
                .eq(UserWordBook::getBookId, bookId));
        if (deleted == 0) {
            throw new BizException(ErrorCode.BOOK_NOT_SELECTED);
        }
        log.info("用户移出词书 userId={} bookId={}", userId, bookId);
    }

    // ---------------------------------------------------------------
    // 管理端
    // ---------------------------------------------------------------

    @Override
    public List<AdminBookResponse> adminListBooks() {
        List<WordBook> books = wordBookMapper.selectList(
                Wrappers.<WordBook>lambdaQuery().orderByAsc(WordBook::getId));
        List<AdminBookResponse> result = new ArrayList<>(books.size());
        for (WordBook book : books) {
            AdminBookResponse response = new AdminBookResponse();
            response.setBookId(book.getId());
            response.setName(book.getName());
            response.setCategory(book.getCategory());
            response.setWordCount(book.getWordCount());
            response.setDescription(book.getDescription());
            // 管理端独有：创建时间（学生端 §2.1 无此字段，故不复用 BookResponse）
            response.setCreateTime(book.getCreateTime());
            result.add(response);
        }
        return result;
    }

    @Override
    public ImportResult importBook(InputStream in, String name, String category) {
        return wordImportService.importFromStream(in, name, category);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBook(Long bookId) {
        WordBook book = requireBook(bookId);
        if (book.getWordCount() != null && book.getWordCount() > 0) {
            log.info("删除词书连带逻辑删除其单词 bookId={} wordCount={}",
                    bookId, book.getWordCount());
        }
        // 逻辑删除单词（@TableLogic 触发 UPDATE deleted=1，不是物理 DELETE）
        wordMapper.delete(Wrappers.<Word>lambdaQuery().eq(Word::getBookId, bookId));
        // 再逻辑删除词书本身
        wordBookMapper.deleteById(bookId);
        log.info("删除词书 bookId={} name={}", bookId, book.getName());
    }

    // ---------------------------------------------------------------
    // 内部工具
    // ---------------------------------------------------------------

    /** 取词书，不存在抛 2001（逻辑删除的词书查不到，同样按不存在处理）。 */
    private WordBook requireBook(Long bookId) {
        WordBook book = bookId == null ? null : wordBookMapper.selectById(bookId);
        if (book == null) {
            throw new BizException(ErrorCode.BOOK_NOT_FOUND);
        }
        return book;
    }

    private long normalizePage(long page) {
        return Pages.normalizePage(page);
    }

    private long normalizeSize(long size) {
        return Pages.normalizeSize(size);
    }
}
