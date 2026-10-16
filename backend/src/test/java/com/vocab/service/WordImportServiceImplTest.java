package com.vocab.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocab.common.Constants;
import com.vocab.entity.Word;
import com.vocab.service.impl.WordImportServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 词库 JSONL 解析单测（design.md §7.1 的唯一映射实现）。
 *
 * <p>本类的价值在于：把「契约 §8.1 的字段映射」逐条钉成可执行断言。
 * 若哪天有人改了 {@code parseLine} 的映射规则（例如把 {@code uk} 换成 {@code us}，
 * 或改变多义项分隔符），这里立刻红。
 *
 * <p>不启 Spring 上下文——纯 POJO 逻辑，毫秒级。
 */
class WordImportServiceImplTest {

    private final WordImportServiceImpl service =
            new WordImportServiceImpl(null, null, new ObjectMapper());

    private static final Long BOOK_ID = 7L;

    @Test
    @DisplayName("完整行：uk→phonetic、translations 拼「type. 释义」、sentences[0]→example")
    void parseFullLine() {
        String line = "{\"word\":\"abandon\",\"us\":\"əˈbændən\",\"uk\":\"əˈbændən\","
                + "\"translations\":[{\"translation\":\"放弃\",\"type\":\"v\"}],"
                + "\"sentences\":[{\"sentence\":\"He decided to abandon the plan.\","
                + "\"translation\":\"他决定放弃这个计划。\"}]}";

        Word word = service.parseLine(line, BOOK_ID);

        assertNotNull(word);
        assertEquals(BOOK_ID, word.getBookId());
        assertEquals("abandon", word.getWord());
        // 契约要求英式音标：取 uk 而非 us
        assertEquals("əˈbændən", word.getPhonetic());
        assertEquals("v. 放弃", word.getTranslation());
        assertEquals("He decided to abandon the plan.", word.getExample());
        // 一期留空字段
        assertNull(word.getDefinition());
        assertNull(word.getAudioUrl());
    }

    @Test
    @DisplayName("us 与 uk 不同时取 uk（防止误用美式音标）")
    void prefersUkOverUs() {
        String line = "{\"word\":\"absorb\",\"us\":\"æbˈsɔrb\",\"uk\":\"əb'sɔ:b\","
                + "\"translations\":[{\"translation\":\"吸收\",\"type\":\"v\"}]}";

        Word word = service.parseLine(line, BOOK_ID);

        assertNotNull(word);
        assertEquals("əb'sɔ:b", word.getPhonetic());
    }

    @Test
    @DisplayName("多义项：逐项「type. 释义」，用「；」连接")
    void joinsMultipleTranslations() {
        String line = "{\"word\":\"murder\",\"uk\":\"ˈmɜːdə(r)\",\"translations\":["
                + "{\"translation\":\"谋杀\",\"type\":\"n\"},"
                + "{\"translation\":\"杀害\",\"type\":\"v\"}]}";

        Word word = service.parseLine(line, BOOK_ID);

        assertNotNull(word);
        assertEquals("n. 谋杀；v. 杀害", word.getTranslation());
    }

    @Test
    @DisplayName("缺 type 时只保留释义，不产生孤立的「. 」")
    void translationWithoutTypeHasNoDotPrefix() {
        String line = "{\"word\":\"x\",\"translations\":[{\"translation\":\"无名\"}]}";

        Word word = service.parseLine(line, BOOK_ID);

        assertNotNull(word);
        assertEquals("无名", word.getTranslation());
    }

    @Test
    @DisplayName("缺 uk / 缺 sentences / 缺 translations → 空串而非 null（前端不用判空）")
    void missingOptionalFieldsBecomeEmptyStrings() {
        String line = "{\"word\":\"solo\"}";

        Word word = service.parseLine(line, BOOK_ID);

        assertNotNull(word);
        assertEquals("", word.getPhonetic());
        assertEquals("", word.getTranslation());
        assertEquals("", word.getExample());
    }

    @Test
    @DisplayName("sentences 为空数组 → example 空串（不抛 IndexOutOfBounds）")
    void emptySentencesArrayIsSafe() {
        String line = "{\"word\":\"solo\",\"sentences\":[],\"translations\":[]}";

        Word word = service.parseLine(line, BOOK_ID);

        assertNotNull(word);
        assertEquals("", word.getExample());
        assertEquals("", word.getTranslation());
    }

    @Test
    @DisplayName("坏行 → null：非法 JSON / 空行 / 缺 word / word 全空白")
    void badLinesReturnNull() {
        assertNull(service.parseLine("{\"word\":", BOOK_ID), "非法 JSON");
        assertNull(service.parseLine("", BOOK_ID), "空行");
        assertNull(service.parseLine("   ", BOOK_ID), "全空白行");
        assertNull(service.parseLine("{}", BOOK_ID), "缺 word");
        assertNull(service.parseLine("{\"word\":\"  \"}", BOOK_ID), "word 全空白");
        assertNull(service.parseLine("{\"word\":null}", BOOK_ID), "word 为 null");
        assertNull(service.parseLine(null, BOOK_ID), "null 行");
        // 合法 JSON 但不是对象（数组/标量）同样视为坏行
        assertNull(service.parseLine("[]", BOOK_ID), "数组");
        assertNull(service.parseLine("\"just a string\"", BOOK_ID), "标量");
    }

    @Test
    @DisplayName("超列宽行 → null（防御性：源数据实测不触发，但超宽会让整批 saveBatch 报错）")
    void overLengthLineIsSkipped() {
        String longWord = "a".repeat(Constants.WORD_MAX_LENGTH + 1);
        assertNull(service.parseLine("{\"word\":\"" + longWord + "\"}", BOOK_ID), "word 超宽");

        String longPhonetic = "p".repeat(Constants.PHONETIC_MAX_LENGTH + 1);
        assertNull(service.parseLine(
                "{\"word\":\"ok\",\"uk\":\"" + longPhonetic + "\"}", BOOK_ID), "phonetic 超宽");

        String longTranslation = "t".repeat(Constants.TRANSLATION_MAX_LENGTH + 1);
        assertNull(service.parseLine(
                "{\"word\":\"ok\",\"translations\":[{\"translation\":\"" + longTranslation + "\"}]}",
                BOOK_ID), "translation 超宽");

        String longExample = "e".repeat(Constants.EXAMPLE_MAX_LENGTH + 1);
        assertNull(service.parseLine(
                "{\"word\":\"ok\",\"sentences\":[{\"sentence\":\"" + longExample + "\"}]}",
                BOOK_ID), "example 超宽");
    }

    @Test
    @DisplayName("恰好等于列宽上限 → 保留（边界内不误杀）")
    void exactlyAtLimitIsKept() {
        String word = "a".repeat(Constants.WORD_MAX_LENGTH);
        Word parsed = service.parseLine("{\"word\":\"" + word + "\"}", BOOK_ID);

        assertNotNull(parsed);
        assertEquals(Constants.WORD_MAX_LENGTH, parsed.getWord().length());
    }

    @Test
    @DisplayName("word 首尾空白被 trim")
    void wordIsTrimmed() {
        Word word = service.parseLine("{\"word\":\"  hello  \"}", BOOK_ID);

        assertNotNull(word);
        assertEquals("hello", word.getWord());
    }

    @Test
    @DisplayName("真实源数据样例行可解析（防止「只对合成样例有效」）")
    void parsesRealSourceFormat() {
        // 取自 data/vocab-source/CET4.jsonl 的实际首行（含 us/uk/phrases/sentences）
        String line = "{\"word\":\"abruptly\",\"us\":\"ə'brʌptli\",\"uk\":\"ə'brʌptlɪ\","
                + "\"translations\":[{\"translation\":\"突然地\",\"type\":\"adv\"}],"
                + "\"phrases\":[],\"sentences\":[{\"sentence\":\"The path ends off abruptly.\","
                + "\"translation\":\"这条路突然到头了。\"},{\"sentence\":\"The ground rises abruptly.\","
                + "\"translation\":\"地势突然隆起。\"}]}";

        Word word = service.parseLine(line, BOOK_ID);

        assertNotNull(word);
        assertEquals("abruptly", word.getWord());
        assertEquals("ə'brʌptlɪ", word.getPhonetic());
        assertEquals("adv. 突然地", word.getTranslation());
        // 只有第一条例句
        assertEquals("The path ends off abruptly.", word.getExample());
        assertTrue(word.getExample().length() < Constants.EXAMPLE_MAX_LENGTH);
    }
}
