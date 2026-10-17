/**
 * 每日学习页（F4 + F5）。
 *
 * 交互模型：一次只显示一个词，点卡片翻面看释义，再选「认识/不认识」。
 * 上报是**逐词即时**的（契约 §3.2），不是学完批量提交——这样中途退出
 * 已学的词不会白学，finished 也能真实反映进度。
 *
 * 打卡门槛：plan.finished >= plan.total。total 由后端计算
 * （待复习词 + 每本已选词书 20 新词 + 今日已上报词去重），**前端不得自己算**。
 */

const studyService = require('../../services/study');
const aiService = require('../../services/ai');
const storage = require('../../utils/storage');
const text = require('../../utils/text');
const { ERROR_CODES } = require('../../utils/constants');

Page({
  data: {
    loading: true,
    errorMsg: '',

    plan: null,
    // 当前词索引
    index: 0,
    current: null,
    // 当前词的多义项数组（已拆分）
    meanings: [],
    // 卡片是否翻面（翻面才显示释义）
    flipped: false,
    // 上报中，防止连点导致同一词重复上报
    submitting: false,

    // 打卡相关
    checkInLoading: false,
    // 打卡成功后的结果（非 null 时显示成功页）
    checkInResult: null,

    // AI 例句
    aiExample: null,
    aiDegraded: false,
    aiLoading: false,
    aiError: '',
  },

  onLoad() {
    this.loadPlan();
  },

  onShow() {
    // 从其他页面返回时，若计划可能已变化（例如去词书页加了新书），刷新。
    // 但打卡成功页正在展示时不要刷新，否则用户看不到结果。
    if (!this.data.checkInResult) {
      this.loadPlan();
    }
  },

  async loadPlan() {
    this.setData({ loading: true, errorMsg: '' });
    try {
      const plan = await studyService.getPlan();
      this.applyPlan(plan);
    } catch (err) {
      this.setData({
        plan: null,
        current: null,
        loading: false,
        errorMsg: (err && err.message) || '加载失败，请下拉重试',
      });
    }
  },

  onPullDownRefresh() {
    this.loadPlan().then(function () {
      wx.stopPullDownRefresh();
    });
  },

  /**
   * 应用计划数据，并把「当前词」定位到第一个未学的词。
   *
   * 为什么要找第一个未学：用户可能学到一半退出，再进来应该继续而不是
   * 从第一个词重新看一遍（那些词已经上报过了，重看会让人以为进度丢了）。
   */
  applyPlan(plan) {
    const words = (plan && plan.words) || [];

    // 默认停在第一个未学的词；若已全部学完则停在最后一个
    // （而不是回到第一个，否则用户会以为「又得重学」）。
    let startIndex = words.length > 0 ? words.length - 1 : 0;
    for (let i = 0; i < words.length; i++) {
      if (!words[i].learned) {
        startIndex = i;
        break;
      }
    }

    this.setData({
      plan: plan,
      loading: false,
      index: startIndex,
      current: words.length > 0 ? words[startIndex] : null,
      meanings: words.length > 0 ? text.splitTranslation(words[startIndex].translation) : [],
      flipped: false,
      aiExample: null,
      aiDegraded: false,
      aiError: '',
    });
  },

  /** 点击卡片翻面。 */
  onFlip() {
    if (!this.data.current || this.data.aiLoading) {
      return;
    }
    this.setData({ flipped: !this.data.flipped });
  },

  /**
   * 上报学习结果并前进到下一个词。
   *
   * known=true  → 记为已学
   * known=false → 记为已学且自动收录错题本（后端行为，前端不用另调接口）
   */
  async onAnswer(e) {
    if (this.data.submitting || !this.data.current) {
      return;
    }

    const known = e.currentTarget.dataset.known === true
      || e.currentTarget.dataset.known === 'true';
    const current = this.data.current;

    this.setData({ submitting: true });

    try {
      await studyService.recordStudy({
        wordId: current.wordId,
        bookId: current.bookId,
        known: known,
      });

      if (!known) {
        wx.showToast({ title: '已加入错题本', icon: 'none' });
      }

      // 本地推进，避免为了刷新进度再请求一次计划（多一次 RTT，且会打断手感）
      await this.advance();
    } catch (err) {
      this.setData({ submitting: false });
      wx.showToast({
        title: (err && err.message) || '上报失败，请重试',
        icon: 'none',
      });
    }
  },

  /**
   * 前进到下一个词；若已是最后一个，重新拉计划拿到最新的 finished。
   *
   * 必须 await（不能 fire-and-forget）：否则学完最后一个词后用户立刻点打卡，
   * plan.finished 还是旧值，按钮仍显示禁用，用户会以为学了半天白学。
   */
  async advance() {
    const plan = this.data.plan;
    const words = (plan && plan.words) || [];
    const nextIndex = this.data.index + 1;

    if (nextIndex < words.length) {
      const next = words[nextIndex];
      this.setData({
        index: nextIndex,
        current: next,
        meanings: text.splitTranslation(next.translation),
        flipped: false,
        submitting: false,
        aiExample: null,
        aiDegraded: false,
        aiError: '',
      });
      return;
    }

    // 已是最后一个词：等计划重新加载完，让 finished/total 与实际一致，
    // 也让打卡按钮的可用状态正确。
    this.setData({ submitting: false });
    await this.loadPlan();
  },

  /** 回到上一个词（仅查看，不撤销上报）。 */
  onPrev() {
    if (this.data.index <= 0 || this.data.submitting) {
      return;
    }
    const words = (this.data.plan && this.data.plan.words) || [];
    const prev = words[this.data.index - 1];
    this.setData({
      index: this.data.index - 1,
      current: prev,
      meanings: text.splitTranslation(prev.translation),
      flipped: false,
      aiExample: null,
      aiDegraded: false,
      aiError: '',
    });
  },

  /**
   * 请求 AI 例句。
   *
   * 降级处理是关键：AI 不可用时后端返回成功（HTTP 200 + code 0）+ degraded:true，
   * 此时 example 是词库内置例句、mnemonic 为空。用户仍能看到例句，
   * 但要明确告知「这是内置例句，不是 AI 生成」，否则会以为功能坏了。
   */
  async onRequestAiExample() {
    if (this.data.aiLoading || !this.data.current) {
      return;
    }

    const wordId = this.data.current.wordId;

    // 会话内缓存：同一个词反复翻看不必重复请求（AI 调用有成本和延迟）
    const app = getApp();
    const cache = (app && app.globalData && app.globalData.aiExampleCache) || {};
    if (cache[wordId]) {
      this.applyAiExample(cache[wordId]);
      return;
    }

    this.setData({ aiLoading: true, aiError: '' });

    try {
      const result = await aiService.getExample(wordId);
      cache[wordId] = result;
      if (app && app.globalData) {
        app.globalData.aiExampleCache = cache;
      }
      this.applyAiExample(result);
    } catch (err) {
      const code = err && err.code;
      let msg = 'AI 例句生成失败，请稍后重试';
      if (code === ERROR_CODES.AI_TIMEOUT || code === 'TIMEOUT') {
        msg = 'AI 例句生成超时，请稍后重试';
      } else if (code === ERROR_CODES.AI_ERROR) {
        msg = 'AI 服务暂时不可用，请稍后重试';
      }
      this.setData({ aiLoading: false, aiError: msg });
    }
  },

  applyAiExample(result) {
    this.setData({
      aiLoading: false,
      aiError: '',
      aiExample: result,
      aiDegraded: !!result.degraded,
    });
  },

  onCloseAiExample() {
    this.setData({ aiExample: null, aiError: '' });
  },

  /**
   * 打卡。
   *
   * 错误码处理（契约 §3.3）：
   *   3001 已打卡 → 幂等，直接刷新成「已打卡」状态
   *   3002 无计划 → 提示去选词书
   *   3003 未完成 → 提示还差几个词
   */
  async onCheckIn() {
    if (this.data.checkInLoading) {
      return;
    }

    const plan = this.data.plan;
    if (!plan || plan.total === 0) {
      wx.showToast({ title: '还没有学习计划，先去选词书', icon: 'none' });
      return;
    }
    if (plan.finished < plan.total) {
      // 正常情况下按钮已禁用，这里是双保险（防止状态不同步时点了还发请求）
      wx.showToast({ title: '完成学习后才能打卡', icon: 'none' });
      return;
    }

    this.setData({ checkInLoading: true });

    try {
      const result = await studyService.checkIn();
      this.setData({ checkInLoading: false, checkInResult: result });
    } catch (err) {
      this.setData({ checkInLoading: false });
      const code = err && err.code;

      if (code === ERROR_CODES.ALREADY_CHECKED_IN) {
        // 幂等：用户可能刚在另一台设备打过卡，重新拉计划即可，
        // 不要把「已打卡」当成错误弹给用户。
        wx.showToast({ title: '今日已打卡', icon: 'none' });
        this.loadPlan();
        return;
      }
      if (code === ERROR_CODES.NO_STUDY_PLAN) {
        wx.showToast({ title: '还没有学习计划，先去选词书', icon: 'none' });
        return;
      }
      if (code === ERROR_CODES.PLAN_NOT_FINISHED) {
        wx.showToast({ title: '还有单词没学完，继续加油', icon: 'none' });
        this.loadPlan();
        return;
      }
      wx.showToast({
        title: (err && err.message) || '打卡失败，请重试',
        icon: 'none',
      });
    }
  },

  /** 打卡成功页 → 看错题。 */
  goMistakes() {
    wx.switchTab({ url: '/pages/mistakes/mistakes' });
  },

  /** 打卡成功页 → 回首页。 */
  goHome() {
    this.setData({ checkInResult: null });
    wx.switchTab({ url: '/pages/index/index' });
  },

  goBooks() {
    wx.navigateTo({ url: '/pages/books/books' });
  },
});
