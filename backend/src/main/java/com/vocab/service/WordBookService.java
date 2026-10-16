package com.vocab.service;

import com.vocab.dto.response.BookDetailResponse;
import com.vocab.dto.response.BookResponse;
import com.vocab.dto.response.ImportResult;

import java.io.InputStream;
import java.util.List;

/**
 * 词书服务：学生端词书列表 / 详情 / 选词书 / 移出，以及管理端词书管理。
 *
 * <p>契约（《接口详细设计》§2.1/§2.2/§8）：所有方法都是<b>按用户</b>语义——
 * {@code selected} / {@code learnedCount} 依赖 userId，
 * 因此方法签名显式带 {@code userId}，而不是靠 {@link com.vocab.config.UserContext}
 * 在 Service 内部隐式取（显式参数才能单测，也避免 Service 依赖 Web 上下文）。
 */
public interface WordBookService {

    /**
     * 学生端词书列表（契约 §2.1）。
     *
     * @param userId   当前用户
     * @param category 可选分类过滤（CET4 / CET6 / KAOYAN）；空白表示不过滤
     * @return 全部（或指定分类的）词书，元素含 {@code selected} 与 {@code learnedCount}
     */
    List<BookResponse> listBooks(Long userId, String category);

    /**
     * 学生端词书详情（契约 §2.2）：词书元信息 + 该词书单词分页。
     *
     * @param userId 当前用户
     * @param bookId 词书 id
     * @param page   页码（从 1 开始）
     * @param size   每页条数
     * @throws com.vocab.common.BizException 2001 词书不存在
     */
    BookDetailResponse bookDetail(Long userId, Long bookId, long page, long size);

    /**
     * 选词书（契约 §2.3 {@code POST /api/client/books/{bookId}/select}）。
     *
     * @throws com.vocab.common.BizException 2001 词书不存在；2003 已加入
     */
    void select(Long userId, Long bookId);

    /**
     * 移出词书（契约 §2.4 {@code DELETE /api/client/books/{bookId}/select}）。
     *
     * <p><b>只删 {@code user_word_book} 关联行，不动学习历史</b>：
     * {@code word_progress} / {@code study_record} / {@code mistake_entry}
     * 全部保留（S3 §7.6 与需求清单 §2.4）。重新选回后进度仍在。
     *
     * @throws com.vocab.common.BizException 2001 词书不存在；2002 未选择该词书
     */
    void deselect(Long userId, Long bookId);

    // ---------------- 管理端 ----------------

    /** 管理端词书列表（契约增补 §8.3）：全部词书（含未选），无用户维度。 */
    List<BookResponse> adminListBooks();

    /**
     * 管理端导入词书（契约 §8.1）。
     *
     * @param in       JSONL 文件流
     * @param name     词书名
     * @param category 分类
     * @throws com.vocab.common.BizException 1005 参数非法；2004 文件格式非法
     */
    ImportResult importBook(InputStream in, String name, String category);

    /**
     * 管理端删除词书（契约增补 §8.4）：逻辑删除词书<b>并连带</b>其下单词。
     *
     * <p>不物理删除——用户可能仍有该书的 {@code word_progress} 历史，
     * 物理删除单词会让历史记录指向不存在的 {@code word_id}。
     *
     * @throws com.vocab.common.BizException 2001 词书不存在
     */
    void deleteBook(Long bookId);
}
