/**
 * 间隔复习页（F6）。
 *
 * 调度规则（后端 ReviewSchedule 为唯一真相源）：
 *   间隔序列 1/3/7/15 天，答对 stage+1（封顶 4），答错退回 stage 1。
 *
 * 前端**不做任何调度计算**：不复制间隔表、不推日期。按钮只标注行为后果，
 * 真正的 nextReviewDate 由后端返回并写库，以它为准。
 *
 * 注意：新账号今日待复习必为 0，这不是 bug。学习时 stage 从 1 开始，
 * 间隔 1 天，所以第一次复习要等到明天。
 */

const reviewService = require('../../services/review');
const text = require('../../utils/text');
const { ERROR_CODES } = require('../../utils/constants');

Page({
  data: {
    loading: true,
    errorMsg: '',

    total: 0,
    words: [],
    // 当前词在原列表中的位置；复习中会从 words 里移除已完成的词，
    // 所以不能直接用下标当「第几个」的序号展示，另存已复习计数。
    doneCount: 0,
    current: null,
    meanings: [],
    flipped: false,
    submitting: false,

    // 本轮全部完成后的收尾页
    finished: false,
  },

  onLoad() {
    this.loadToday();
  },

  onShow() {
    // 只有没在复习中途才重新拉取：正复习到一半时切回来不该把列表重置
    if (!this.data.finished && this.data.doneCount === 0) {
      this.loadToday();
    }
  },

  onPullDownRefresh() {
    this.loadToday().then(function () {
      wx.stopPullDownRefresh();
    });
  },

  async loadToday() {
    this.setData({ loading: true, errorMsg: '' });
    try {
      const result = await reviewService.getTodayReview();
      const words = (result && result.words) || [];
      this.setData({
        loading: false,
        total: (result && result.total) || 0,
        words: words,
        doneCount: 0,
        finished: false,
        current: words.length > 0 ? words[0] : null,
        meanings: words.length > 0 ? text.splitTranslation(words[0].translation) : [],
        flipped: false,
      });
    } catch (err) {
      this.setData({
        loading: false,
        words: [],
        current: null,
        total: 0,
        errorMsg: (err && err.message) || '加载失败，请下拉重试',
      });
    }
  },

  onFlip() {
    if (!this.data.current) {
      return;
    }
    this.setData({ flipped: !this.data.flipped });
  },

  /**
   * 提交复习结果。
   *
   * correct=true  → stage+1（封顶 4），间隔变长
   * correct=false → stage 重置为 1，1 天后重来，并自动收录错题本
   */
  async onAnswer(e) {
    if (this.data.submitting || !this.data.current) {
      return;
    }

    const correct = e.currentTarget.dataset.correct === true
      || e.currentTarget.dataset.correct === 'true';
    const wordId = this.data.current.wordId;

    this.setData({ submitting: true });

    try {
      const result = await reviewService.recordReview({ wordId: wordId, correct: correct });

      // 文案按 PRD F6：按钮要标注后果（答错退回 1 天后，答对进入下一阶段）。
      // 这里不用 result.nextReviewDate 拼日期，因为 toast 字数有限，
      // 而且 PRD 要求的文案就是「后果」而不是具体日期。
      wx.showToast({
        title: correct ? '进入下一阶段' : '退回 1 天后复习',
        icon: 'none',
      });

      this.removeCurrentAndAdvance();
    } catch (err) {
      this.setData({ submitting: false });
      const code = err && err.code;
      if (code === ERROR_CODES.VALIDATION_FAILED) {
        // 没有学习记录的词无法复习（例如进度被重置）。从列表移除，
        // 否则这个词会永远卡在列表里让用户无法推进。
        wx.showToast({ title: '该词无学习记录，已跳过', icon: 'none' });
        this.removeCurrentAndAdvance();
        return;
      }
      wx.showToast({
        title: (err && err.message) || '提交失败，请重试',
        icon: 'none',
      });
    }
  },

  /** 把当前词从待复习列表移除，并前进到下一个（PRD 要求实时移除）。 */
  removeCurrentAndAdvance() {
    const words = this.data.words.slice();
    words.shift();

    if (words.length === 0) {
      this.setData({
        words: [],
        current: null,
        meanings: [],
        flipped: false,
        submitting: false,
        doneCount: this.data.doneCount + 1,
        finished: true,
      });
      return;
    }

    this.setData({
      words: words,
      current: words[0],
      meanings: text.splitTranslation(words[0].translation),
      flipped: false,
      submitting: false,
      doneCount: this.data.doneCount + 1,
    });
  },

  goStudy() {
    wx.switchTab({ url: '/pages/study/study' });
  },

  /** 完成页 → 再来一轮（拉取最新的待复习列表）。 */
  onReload() {
    this.loadToday();
  },
});
