import type { BookCategory } from '../types/book';

/** AntD Tag 颜色名。未知分类回落到 default，不崩。 */
const CATEGORY_COLOR: Record<string, string> = {
  CET4: 'blue',
  CET6: 'green',
  KAOYAN: 'purple',
};

export const CATEGORY_OPTIONS: { value: BookCategory; label: string }[] = [
  { value: 'CET4', label: '四级 CET4' },
  { value: 'CET6', label: '六级 CET6' },
  { value: 'KAOYAN', label: '考研 KAOYAN' },
];

export function categoryColor(category: string): string {
  return CATEGORY_COLOR[category] ?? 'default';
}

/** 空值统一显示为破折号，避免表格里出现 "null"。 */
export function dash(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  return String(value);
}

/**
 * createTime 原样展示。
 *
 * 后端返回 "yyyy-MM-dd HH:mm:ss" 字符串；绝不能 new Date(str) ——
 * Safari 对该格式不容错，会得到 Invalid Date。
 */
export function rawTime(value: string | null | undefined): string {
  return value ?? '—';
}

// 用 Record<string, string> 而非 Record<AiFeature, string>：后者索引时需要
// `as AiFeature` 断言（后端将来新增功能会绕过类型检查），前者天然安全。
const FEATURE_LABEL: Record<string, string> = {
  example: '例句生成',
  explain: '错题讲解',
  report: '学习报告',
};

export function featureLabel(feature: string): string {
  return FEATURE_LABEL[feature] ?? feature;
}

const REASON_COLOR: Record<string, string> = {
  TIMEOUT: 'orange',
  ERROR: 'red',
  CIRCUIT_OPEN: 'volcano',
};

export function reasonColor(reason: string): string {
  return REASON_COLOR[reason] ?? 'default';
}
