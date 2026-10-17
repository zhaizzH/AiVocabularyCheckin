/**
 * 全局常量。
 *
 * 所有「魔法字符串」集中在这里：错误码、Storage key、分类值域、超时时间。
 * 页面与服务层一律从本文件引用，避免同一字面量在十几处各写一遍后改漏。
 */

/** 后端地址：全项目唯一一处。部署时只改这里（或改为按环境切换）。 */
const BASE_URL = 'http://127.0.0.1:8080';

/** Storage key：加 vocab_ 前缀，避免与同机其它小程序/调试数据混淆。 */
const STORAGE_KEYS = {
  token: 'vocab_token',
  userInfo: 'vocab_user',
};

/**
 * 错误码（契约《接口详细设计》§0）。
 * 必须与后端一致且为字符串——后端 Result.code 是字符串，
 * 用字符串比较可避免 '0' == 0 这类隐式类型转换陷阱。
 */
const ERROR_CODES = {
  OK: '0',
  // 1xxx 认证/用户
  USERNAME_TAKEN: '1001',
  BAD_CREDENTIALS: '1002',
  USER_DISABLED: '1003',
  WX_LOGIN_FAILED: '1004',
  VALIDATION_FAILED: '1005',
  TOKEN_INVALID: '1006',
  FORBIDDEN: '1007',
  // 2xxx 词库
  BOOK_NOT_FOUND: '2001',
  BOOK_NOT_SELECTED: '2002',
  BOOK_ALREADY_SELECTED: '2003',
  // 3xxx 学习/打卡
  ALREADY_CHECKED_IN: '3001',
  NO_STUDY_PLAN: '3002',
  PLAN_NOT_FINISHED: '3003',
  // 4xxx 错题
  MISTAKE_NOT_FOUND: '4001',
  MISTAKE_ALREADY_MASTERED: '4002',
  // 5xxx AI
  AI_TIMEOUT: '5001',
  AI_ERROR: '5002',
  AI_REPORT_DATA_INSUFFICIENT: '5003',
  // 9xxx 系统
  SYSTEM_BUSY: '9001',
  NOT_FOUND: '9002',
  METHOD_NOT_ALLOWED: '9003',
  UNSUPPORTED_MEDIA_TYPE: '9004',
};

/** request 层自造的两个非后端错误码，供页面区分展示。 */
const CLIENT_ERROR_CODES = {
  NETWORK: 'NETWORK',
  TIMEOUT: 'TIMEOUT',
};

/**
 * 词书分类。value 是后端契约值（大写英文），label 是界面中文。
 * 「全部」传空串而不是不传参数——后端 category 为空时返回全部。
 */
const BOOK_CATEGORIES = [
  { value: '', label: '全部' },
  { value: 'CET4', label: '四级' },
  { value: 'CET6', label: '六级' },
  { value: 'KAOYAN', label: '考研' },
];

/** 分类值 → 中文标签，用于词书卡片上的小标签。 */
const CATEGORY_LABEL = {
  CET4: '四级',
  CET6: '六级',
  KAOYAN: '考研',
};

/** 内置头像值域，与后端 PUT /auth/profile 的 ^avatar_[1-6]$ 校验一致。 */
const AVATARS = ['avatar_1', 'avatar_2', 'avatar_3', 'avatar_4', 'avatar_5', 'avatar_6'];

/**
 * 头像 → emoji。
 * 一期不引入图片资源：图片缺失会导致编译失败，而 emoji 是文本，零资源风险。
 * 若后续设计稿提供图片，替换本映射即可，页面无需改动。
 */
const AVATAR_EMOJI = {
  avatar_1: '📘',
  avatar_2: '🌱',
  avatar_3: '🔥',
  avatar_4: '🎯',
  avatar_5: '🚀',
  avatar_6: '🦉',
};

/** 错题来源 → 中文标签（后端 source 取值 STUDY/REVIEW）。 */
const SOURCE_LABEL = {
  STUDY: '学习',
  REVIEW: '复习',
};

/**
 * AI 三接口的超时上限（毫秒）。
 * 必须小于后端对应超时（15/30/60s），否则前端先断而后端还在算，
 * 用户看到「超时」但后端实际成功，还会产生一条无主的成功日志。
 */
const AI_TIMEOUT = {
  example: 15000,
  explain: 30000,
  report: 60000,
};

const NICKNAME_MAX_LENGTH = 20;
const HEATMAP_WEEKS = 12;
const MASTERY_DAYS = 30;

/** 复习答错后退回的间隔天数（仅用于按钮文案「退回1天后」）。 */
const FIRST_REVIEW_INTERVAL_DAYS = 1;

module.exports = {
  BASE_URL,
  STORAGE_KEYS,
  ERROR_CODES,
  CLIENT_ERROR_CODES,
  BOOK_CATEGORIES,
  CATEGORY_LABEL,
  AVATARS,
  AVATAR_EMOJI,
  SOURCE_LABEL,
  AI_TIMEOUT,
  NICKNAME_MAX_LENGTH,
  HEATMAP_WEEKS,
  MASTERY_DAYS,
  FIRST_REVIEW_INTERVAL_DAYS,
};
