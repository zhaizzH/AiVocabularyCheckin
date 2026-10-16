package com.vocab.controller.admin;

import com.vocab.common.BizException;
import com.vocab.common.ErrorCode;
import com.vocab.common.Result;
import com.vocab.dto.response.AdminBookResponse;
import com.vocab.dto.response.BookDeleteResponse;
import com.vocab.dto.response.ImportResult;
import com.vocab.service.WordBookService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 管理端词书接口（《接口详细设计》§8.1 / §8.8 / §8.9）。
 *
 * <p><b>为什么把导入放在这里而不是 AdminWordController</b>：
 * 导入的语义是「新建一本词书，并把整份 JSONL 灌进它」——产出物是词书
 * （响应 {@code bookId}），不是某个单词；单词管理接口（§8.2–§8.5）
 * 操作的是「已存在词书里的词」。按契约 §8 的分组，导入与词书列表/删除同属
 * 词书管理页（admin-prototype.html）。
 *
 * <p><b>复用 {@link WordBookService#importBook}</b>：解析与批量写入的实现在
 * {@code WordImportService}（种子导入与管理端上传共用同一份 {@code parseLine}，
 * design.md §7.2）。本 Controller 只负责把 {@code MultipartFile} 的流交出去
 * ——<b>绝不自己读文件内容</b>（否则就是第二处解析实现，必然漂移）。
 */
@RestController
@RequestMapping("/api/admin/books")
public class AdminBookController {

    private final WordBookService wordBookService;

    public AdminBookController(WordBookService wordBookService) {
        this.wordBookService = wordBookService;
    }

    /** §8.8 词书列表（裸数组，含 createTime；管理端无用户维度）。 */
    @GetMapping
    public Result<List<AdminBookResponse>> list() {
        return Result.ok(wordBookService.adminListBooks());
    }

    /**
     * §8.1 词库导入（multipart：file / name / category）。
     *
     * <p>先校验文件存在与非空——{@code MultipartFile} 为 null（未带 file 字段）
     * 属参数错误 1005；文件本身格式非法（整份无合法行）由 Service 抛 2004。
     *
     * <p><b>为何此处对 {@code IOException} 做一次翻译</b>：契约 §8.1 规定
     * 「file 流不可读 → 2004」。流不可读只可能在「把 MultipartFile 变成
     * InputStream」这一步暴露，而这一步属 Web 层的入参搬运、不是业务规则；
     * 若不翻译就会被全局兜底处理成 9001，与契约不符。故在此把
     * 「取流失败」翻译成 2004 后交给全局处理器——这是一次<b>边界异常映射</b>，
     * 不含任何业务判断。
     *
     * <p>用 try-with-resources 关闭流：{@code MultipartFile} 的输入流由
     * Spring 管理，但显式关闭能更早释放临时文件句柄。
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ImportResult> importBook(
            @RequestParam("file") MultipartFile file,
            @RequestParam("name") String name,
            @RequestParam("category") String category) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.IMPORT_FILE_INVALID.getCode(), "导入文件为空");
        }
        try (InputStream in = file.getInputStream()) {
            return Result.ok(wordBookService.importBook(in, name, category));
        } catch (IOException e) {
            // 流不可读 → 2004（契约 §8.1），不是 9001
            throw new BizException(ErrorCode.IMPORT_FILE_INVALID);
        }
    }

    /** §8.9 删除词书（逻辑删除，连带逻辑删除其下全部单词）。 */
    @DeleteMapping("/{bookId}")
    public Result<BookDeleteResponse> delete(@PathVariable Long bookId) {
        wordBookService.deleteBook(bookId);
        return Result.ok(new BookDeleteResponse(bookId, true));
    }
}
