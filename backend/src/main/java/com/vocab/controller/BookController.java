package com.vocab.controller;

import com.vocab.common.Result;
import com.vocab.config.UserContext;
import com.vocab.dto.response.BookDetailResponse;
import com.vocab.dto.response.BookResponse;
import com.vocab.dto.response.BookSelectResponse;
import com.vocab.service.WordBookService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 学生端词库接口（《接口详细设计》§2）。
 *
 * <p>Controller 只做参数接收 + 单次 Service 调用 + {@link Result} 包装，
 * 无任何业务分支（quality-guidelines.md「Controller 不写业务逻辑」）。
 *
 * <p>当前用户 id 一律取 {@link UserContext}（来自 token），
 * <b>绝不接受请求参数传入 userId</b>——否则任何人都能查到别人的学习进度。
 */
@RestController
@RequestMapping("/api/client/books")
public class BookController {

    private final WordBookService wordBookService;

    public BookController(WordBookService wordBookService) {
        this.wordBookService = wordBookService;
    }

    /** §2.1 词库列表（可选按分类过滤）。 */
    @GetMapping
    public Result<List<BookResponse>> list(
            @RequestParam(required = false) String category) {
        return Result.ok(wordBookService.listBooks(UserContext.getUserId(), category));
    }

    /** §2.2 词书详情（含单词分页）。 */
    @GetMapping("/{bookId}")
    public Result<BookDetailResponse> detail(
            @PathVariable Long bookId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(wordBookService.bookDetail(UserContext.getUserId(), bookId, page, size));
    }

    /** §2.3 选择词书。 */
    @PostMapping("/{bookId}/select")
    public Result<BookSelectResponse> select(@PathVariable Long bookId) {
        Long userId = UserContext.getUserId();
        wordBookService.select(userId, bookId);
        return Result.ok(new BookSelectResponse(bookId, true));
    }

    /** §2.4 移出词书（历史学习记录保留）。 */
    @DeleteMapping("/{bookId}/select")
    public Result<BookSelectResponse> deselect(@PathVariable Long bookId) {
        Long userId = UserContext.getUserId();
        wordBookService.deselect(userId, bookId);
        return Result.ok(new BookSelectResponse(bookId, false));
    }
}
