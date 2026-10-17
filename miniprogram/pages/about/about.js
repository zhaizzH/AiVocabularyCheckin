/**
 * 关于页（F10）。
 *
 * 必须包含：版本号 + 词库版权说明（BSD 3-Clause，KyleBing/english-vocabulary）。
 * 词库是第三方开源项目，MIT 之外的许可要求保留版权声明，不能只写「词库来自网络」。
 */

const { APP_VERSION } = require('../../utils/constants');

Page({
  data: {
    version: APP_VERSION,
    // 占位：PRD 待补的「第 N 组」文案由用户提供后替换
    groupText: '',
  },
});
