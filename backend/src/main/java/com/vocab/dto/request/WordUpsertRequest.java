package com.vocab.dto.request;

import com.vocab.common.Constants;
import jakarta.validation.constraints.Size;

/**
 * 新增 / 修改单词请求（《接口详细设计》§8.3 / §8.4）。
 *
 * <pre>
 * { "bookId": 1, "word": "apple", "phonetic": "ˈæpl",
 *   "translation": "n. 苹果", "example": "…" }
 * </pre>
 *
 * <p><b>为什么新增与修改共用一个 DTO</b>：契约 §8.4 明确「字段同 §8.3（均可选）」，
 * 两者的字段集合完全一致，只差「哪些必填」。共用一个类可以避免两个结构
 * （一旦加字段就必须改两处，迟早漂移）。
 *
 * <p><b>为什么「必填」不写成注解</b>：
 * <ul>
 *   <li>新增：{@code bookId} 与 {@code word} 必填；</li>
 *   <li>修改：全部可选，但至少要传一项。</li>
 * </ul>
 * 同一字段在两个场景下的必填性相反，Bean Validation 的字段级注解无法表达
 * （需要分组校验或类级自定义约束，收益不抵复杂度）。故此处只保留
 * <b>恒成立的长度上界</b>——超长行交给数据库会抛 {@code Data too long}
 * （本机 {@code sql_mode} 含 {@code STRICT_TRANS_TABLES}），必须提前拦下；
 * 「必填 / 至少一项」由 {@code AdminServiceImpl} 判断并抛 1005
 * （与 {@code UpdateProfileRequest} 的「至少一项」同一套做法）。
 */
public class WordUpsertRequest {

    /** 所属词书 id。新增时必填；修改时可选（留空表示不改所属词书）。 */
    private Long bookId;

    /** 单词原文。新增时必填且非空白；修改时可选。 */
    @Size(max = Constants.WORD_MAX_LENGTH, message = "单词最多 100 字符")
    private String word;

    /** 音标。可选，留空表示不修改（新增时可为 null）。 */
    @Size(max = Constants.PHONETIC_MAX_LENGTH, message = "音标最多 100 字符")
    private String phonetic;

    /** 中文释义（多项以「；」连接）。可选。 */
    @Size(max = Constants.TRANSLATION_MAX_LENGTH, message = "释义最多 255 字符")
    private String translation;

    /** 示例例句。可选。 */
    @Size(max = Constants.EXAMPLE_MAX_LENGTH, message = "例句最多 500 字符")
    private String example;

    public WordUpsertRequest() {
    }

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    public String getWord() {
        return word;
    }

    public void setWord(String word) {
        this.word = word;
    }

    public String getPhonetic() {
        return phonetic;
    }

    public void setPhonetic(String phonetic) {
        this.phonetic = phonetic;
    }

    public String getTranslation() {
        return translation;
    }

    public void setTranslation(String translation) {
        this.translation = translation;
    }

    public String getExample() {
        return example;
    }

    public void setExample(String example) {
        this.example = example;
    }
}
