/**
 * 首页（F2）。
 *
 * 三张卡片：今日学习计划、待复习、连续打卡。
 *
 * 关键设计：三卡数据用 Promise.allSettled **并行**拉取，而不是串行、也不是 Promise.all。
 *   - 串行：三次 RTT 叠加，首屏慢一拍；
 *   - Promise.all：任一接口失败则整体 reject，另外两张本该能显示的卡片也白屏；
 *   - allSettled：各卡独立成败，失败的那张显示「加载失败，点击重试」。
 */

const studyService = require('../../services/study');
const reviewService = require('../../services/review');
const storage = require('../../utils/storage');
const { ERROR_CODES } = require('../../utils/constants');

Page({
  data: {
    loading: true,
    userInfo: null,
    greeting: '',

    // 三卡各自的状态，互不影响
    plan: null,
    planError: '',

    reviewTotal: 0,
    reviewError: '',

    streak: null,
    streakError: '',
  },

  onShow() {
    // onShow 而非 onLoad：从学习页打卡回来、从复习页答完回来，
    // 首页数据都必须刷新，否则显示的还是离开前的旧数字。
    this.setData({ userInfo: storage.getUserInfo() });
    this.refresh();
  },

  onPullDownRefresh() {
    this.refresh().then(() => {
      wx.stopPullDownRefresh();
    });
  },

  /** 并行拉三卡数据，单卡失败不影响其它卡。 */
  async refresh() {
    const hour = new Date().getHours();
    this.setData({
      loading: true,
      greeting: hour < 12 ? '早上好' : hour < 18 ? '下午好' : '晚上好',
    });

    const [planRes, reviewRes, streakRes] = await Promise.allSettled([
      studyService.getPlan(),
      reviewService.getTodayReview(),
      studyService.getStreak(),
    ]);

    const patch = { loading: false };

    if (planRes.status === 'fulfilled') {
      patch.plan = planRes.value;
      patch.planError = '';
    } else {
      patch.plan = null;
      patch.planError = this.describeError(planRes.reason);
    }

    if (reviewRes.status === 'fulfilled') {
      patch.reviewTotal = reviewRes.value.total || 0;
      patch.reviewError = '';
    } else {
      patch.reviewTotal = 0;
      patch.reviewError = this.describeError(reviewRes.reason);
    }

    if (streakRes.status === 'fulfilled') {
      patch.streak = streakRes.value;
      patch.streakError = '';
    } else {
      patch.streak = null;
      patch.streakError = this.describeError(streakRes.reason);
    }

    this.setData(patch);
  },

  /**
   * 错误 → 卡片内简短文案。
   * 卡片空间小，只说「加载失败」即可，详细原因靠用户点击重试后由
   * 具体页面展示；不在这里把后端 message 直接铺出来。
   */
  describeError(err) {
    const code = err && err.code;
    if (code === 'TIMEOUT') {
      return '加载超时';
    }
    if (code === ERROR_CODES.SYSTEM_BUSY) {
      return '服务繁忙';
    }
    return '加载失败';
  },

  /** 计划卡：进入学习页（tab 页，必须 switchTab）。 */
  goStudy() {
    wx.switchTab({ url: '/pages/study/study' });
  },

  /** 复习卡：进入复习页。 */
  goReview() {
    wx.switchTab({ url: '/pages/review/review' });
  },

  /** 词书入口：非 tab 页，用 navigateTo。 */
  goBooks() {
    wx.navigateTo({ url: '/pages/books/books' });
  },

  goStats() {
    wx.switchTab({ url: '/pages/stats/stats' });
  },

  goReport() {
    wx.navigateTo({ url: '/pages/report/report' });
  },

  /** 单卡重试：只重拉失败的卡，不整页刷新。 */
  retryPlan() {
    this.refresh();
  },

  retryReview() {
    this.refresh();
  },
});
