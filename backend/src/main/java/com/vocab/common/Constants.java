package com.vocab.common;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 业务常量。
 *
 * <p>本类中的值来自《需求清单-功能与规则》与《接口详细设计》，
 * 属于**已冻结的规则常量**（需求清单 §3.2 明确「已定为常量，写死即可」）。
 */
public final class Constants {

    private Constants() {
    }

    // ---------------- 学习计划 ----------------

    /** 每本已选词书每日新词数量（需求清单 §2.2）。 */
    public static final int DAILY_NEW_WORDS = 20;

    /** 分页默认值（契约 §0）。 */
    public static final long DEFAULT_PAGE = 1L;
    public static final long DEFAULT_SIZE = 20L;

    // ---------------- 间隔复习 ----------------

    /**
     * 复习间隔序列（天），索引 0..3 对应 stage 1..4。
     *
     * <p>需求清单 §3.2：「复习间隔序列 1 → 3 → 7 → 15 天」。
     * 语义：stage 直接决定下次复习的间隔天数。
     */
    public static final int[] REVIEW_INTERVALS = {1, 3, 7, 15};

    /** 复习阶段上限（达到后不再递增）。 */
    public static final int MAX_STAGE = 4;

    /** 复习阶段下限。 */
    public static final int MIN_STAGE = 1;

    // ---------------- 用户 ----------------

    /** 昵称最大长度（需求清单 §1.3）。 */
    public static final int NICKNAME_MAX_LENGTH = 20;

    /** 内置头像白名单（契约 §1.5：仅限 avatar_1 ~ avatar_6）。 */
    public static final List<String> AVATARS = Collections.unmodifiableList(
            Arrays.asList("avatar_1", "avatar_2", "avatar_3", "avatar_4", "avatar_5", "avatar_6"));

    /** 默认头像。 */
    public static final String DEFAULT_AVATAR = "avatar_1";

    /** 用户角色。 */
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";

    /** 用户状态：1 正常 / 0 禁用。 */
    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;

    // ---------------- 学习记录 ----------------

    /** 学习类型：新词学习。 */
    public static final String STUDY_TYPE_STUDY = "STUDY";
    /** 学习类型：复习。 */
    public static final String STUDY_TYPE_REVIEW = "REVIEW";

    /** 错题来源：学习中标记「不认识」。 */
    public static final String SOURCE_STUDY = "STUDY";
    /** 错题来源：复习中答错。 */
    public static final String SOURCE_REVIEW = "REVIEW";

    // ---------------- 统计 ----------------

    /** 热力图默认周数（契约 §7.1）。 */
    public static final int DEFAULT_HEATMAP_WEEKS = 12;

    /** 掌握度曲线默认天数（契约 §7.2）。 */
    public static final int DEFAULT_MASTERY_DAYS = 30;

    // ---------------- AI 监控 ----------------

    /** 降级事件环形队列容量。 */
    public static final int DEGRADE_EVENT_CAPACITY = 100;

    /** 生成学习报告所需的最少学习天数（需求清单 §4.3）。 */
    public static final int MIN_REPORT_STUDY_DAYS = 3;

    /** 判断昵称/头像是否合法。 */
    public static boolean isValidAvatar(String avatar) {
        return avatar != null && AVATARS.contains(avatar);
    }
}
