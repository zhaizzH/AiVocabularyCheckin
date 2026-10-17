/**
 * 错题本页（F7，含 AI 讲解）。
 *
 * 两种掌握状态各自独立分页：未掌握是待办队列，已掌握是回顾列表。
 * 切换 Tab 时重新从第 1 页拉取，不合并两种状态的数据。
 *
 * AI 讲解与「标记已掌握」是**独立的两件事**：
 * 讲解失败绝不影响标记按钮可用（PRD F7 明确要求）。
 */

const mistakeService = require('../../services/mistake');
const aiService = require('../../services/ai');
const { ERROR_CODES, SOURCE_LABEL } = require('../../utils/constants');

const PAGE_SIZE = 20;

Page({
  data: {
    // 'todo' 未掌握 / 'done' 已掌握
    tab: 'todo',
    // 未掌握总数，用于 Tab 角标
    todoTotal: 0,

    list: [],
    page: 1,
    total: 0,
    hasMore: false,

    loading: true,
    loadingMore: false,
    errorMsg: '',

    // 每条错题的 AI 讲解状态，key = mistakeId
    explainMap: {},
    // 正在请求讲解的 mistakeId
    explainingId: null,

    // 正在提交标记/移除的 mistakeId，用于禁用该行按钮
    pendingId: null,
  },

  onLoad() {
    this.loadList(true);
  },

  onShow() {
    // 从学习页答错返回后错题数会变，需要刷新
    this.loadList(true);
  },

  onPullDownRefresh() {
    this.loadList(true).then(function () {
      wx.stopPullDownRefresh();
    });
  },

  onReachBottom() {
    if (this.data.hasMore && !this.data.loadingMore) {
      this.loadList(false);
    }
  },

  /**
   * 拉取列表。
   * @param {boolean} reset true = 从第 1 页重拉并替换列表
   */
  async loadList(reset) {
    const nextPage = reset ? 1 : this.data.page + 1;

    if (reset) {
      this.setData({ loading: true, errorMsg: '' });
    } else {
      this.setData({ loadingMore: true });
    }

    try {
      const result = await mistakeService.listMistakes({
        mastered: this.data.tab === 'done' ? 1 : 0,
        page: nextPage,
        size: PAGE_SIZE,
      });

      const incoming = (result && result.list) || [];
      const list = reset ? incoming : this.data.list.concat(incoming);
      const total = (result && result.total) || 0;

      this.setData({
        loading: false,
        loadingMore: false,
        list: list.map(this.decorate),
        page: nextPage,
        total: total,
        hasMore: list.length < total,
      });

      // 顺便刷新未掌握角标：切到已掌握 Tab 时也要显示正确数字
      if (this.data.tab === 'done') {
        this.refreshTodoTotal();
      } else {
        this.setData({ todoTotal: total });
      }
    } catch (err) {
      this.setData({
        loading: false,
        loadingMore: false,
        errorMsg: reset ? ((err && err.message) || '加载失败，请下拉重试') : '',
      });
      if (!reset) {
        wx.showToast({ title: '加载更多失败', icon: 'none' });
      }
    }
  },

  /** 补上界面用的派生字段（来源标签、讲解状态）。 */
  decorate(item) {
    const ex = (this.data.explainMap && this.data.explainMap[item.mistakeId]) || {};
    return {
      mistakeId: item.mistakeId,
      wordId: item.wordId,
      word: item.word,
      phonetic: item.phonetic,
      translation: item.translation,
      wrongCount: item.wrongCount,
      mastered: item.mastered,
      sourceLabel: SOURCE_LABEL[item.source] || item.source,
      createTime: item.createTime,
      // 讲解态拍平进条目：WXML 里写 explainMap[id].x 在 key 不存在时
      // 会读到 undefined 的属性，渲染出空面板且难以排查。
      exLoading: !!ex.loading,
      exError: ex.error || '',
      exExpanded: !!ex.expanded,
      exExplanation: ex.explanation || '',
      exAdvice: ex.advice || '',
    };
  },

  /**
   * 把讲解态同步到条目上。
   * 讲解态存在 explainMap（按 mistakeId 索引），但列表渲染需要扁平字段，
   * 所以每次改动都要把两者对齐一次。
   */
  syncExplainToList(explainMap) {
    const list = this.data.list.map((item) => {
      const ex = explainMap[item.mistakeId] || {};
      return Object.assign({}, item, {
        exLoading: !!ex.loading,
        exError: ex.error || '',
        exExpanded: !!ex.expanded,
        exExplanation: ex.explanation || '',
        exAdvice: ex.advice || '',
      });
    });
    this.setData({ explainMap: explainMap, list: list });
  },

  /** 单独拉一次未掌握总数，只为更新角标。失败不影响主列表。 */
  async refreshTodoTotal() {
    try {
      const r = await mistakeService.listMistakes({ mastered: 0, page: 1, size: 1 });
      this.setData({ todoTotal: (r && r.total) || 0 });
    } catch (err) {
      // 角标是辅助信息，失败就保留旧值，不弹错打断用户
      console.warn('刷新未掌握角标失败', err && err.code);
    }
  },

  onSwitchTab(e) {
    const tab = e.currentTarget.dataset.tab;
    if (tab === this.data.tab) {
      return;
    }
    // 切 Tab 时清空讲解面板：不同列表混着显示会让人分不清哪条是哪条
    this.setData({ tab: tab, list: [], explainMap: {}, page: 1 });
    this.loadList(true);
  },

  /**
   * AI 讲解。
   *
   * 与「标记已掌握」完全解耦：讲解失败只更新 explainMap 里的错误文案，
   * 不影响列表数据、也不影响标记按钮。
   */
  async onExplain(e) {
    const mistakeId = e.currentTarget.dataset.id;

    if (this.data.explainingId !== null) {
      return;
    }

    const cache = this.data.explainMap[mistakeId];
    if (cache && cache.explanation) {
      // 已经拿到过讲解：再点就收起/展开，不重复请求
      this.toggleExplain(mistakeId);
      return;
    }

    this.setData({ explainingId: mistakeId });

    const map = Object.assign({}, this.data.explainMap);
    map[mistakeId] = { loading: true, error: '', explanation: '', advice: '' };
    this.syncExplainToList(map);

    try {
      const result = await aiService.explainMistake(mistakeId);
      const next = Object.assign({}, this.data.explainMap);
      next[mistakeId] = {
        loading: false,
        error: '',
        explanation: (result && result.explanation) || '',
        advice: (result && result.advice) || '',
        expanded: true,
      };
      this.syncExplainToList(next);
      this.setData({ explainingId: null });
    } catch (err) {
      const code = err && err.code;
      let msg = 'AI 讲解暂时不可用，请稍后重试';
      if (code === ERROR_CODES.AI_TIMEOUT || code === 'TIMEOUT') {
        msg = 'AI 讲解生成超时，请稍后再试';
      } else if (code === ERROR_CODES.AI_ERROR) {
        msg = 'AI 讲解暂时不可用，请稍后重试';
      }
      const next = Object.assign({}, this.data.explainMap);
      next[mistakeId] = { loading: false, error: msg, explanation: '', advice: '' };
      this.syncExplainToList(next);
      this.setData({ explainingId: null });
    }
  },

  /** 展开/收起已有讲解。 */
  toggleExplain(mistakeId) {
    const cur = this.data.explainMap[mistakeId];
    if (!cur) {
      return;
    }
    const next = Object.assign({}, this.data.explainMap);
    next[mistakeId] = Object.assign({}, cur, { expanded: !cur.expanded });
    this.syncExplainToList(next);
  },

  /** 点已展开的讲解区域收起。 */
  onCollapseExplain(e) {
    const mistakeId = e.currentTarget.dataset.id;
    const cur = this.data.explainMap[mistakeId];
    if (cur && cur.expanded) {
      this.toggleExplain(mistakeId);
    }
  },

  /**
   * 标记已掌握。
   * 4002（已掌握）当成功处理：用户的目标状态已达成，不必报错。
   */
  async onMarkMastered(e) {
    const mistakeId = e.currentTarget.dataset.id;
    if (this.data.pendingId !== null) {
      return;
    }
    this.setData({ pendingId: mistakeId });

    try {
      await mistakeService.markMastered(mistakeId);
      wx.showToast({ title: '已标记掌握', icon: 'success' });
      // 从当前列表移除（未掌握列表里不该再出现），并刷新角标
      this.removeFromList(mistakeId);
    } catch (err) {
      const code = err && err.code;
      if (code === ERROR_CODES.MISTAKE_ALREADY_MASTERED) {
        this.removeFromList(mistakeId);
      } else if (code === ERROR_CODES.MISTAKE_NOT_FOUND) {
        wx.showToast({ title: '该错题已不存在', icon: 'none' });
        this.removeFromList(mistakeId);
      } else {
        wx.showToast({
          title: (err && err.message) || '操作失败，请重试',
          icon: 'none',
        });
      }
    } finally {
      this.setData({ pendingId: null });
    }
  },

  /** 移除错题，需二次确认（不可恢复）。 */
  async onRemove(e) {
    const mistakeId = e.currentTarget.dataset.id;
    if (this.data.pendingId !== null) {
      return;
    }

    const confirmed = await new Promise(function (resolve) {
      wx.showModal({
        title: '移除错题',
        content: '移除后这条错题记录将不再出现，确定移除吗？',
        confirmText: '移除',
        confirmColor: '#d4380d',
        success: function (res) { resolve(!!res.confirm); },
        fail: function () { resolve(false); },
      });
    });
    if (!confirmed) {
      return;
    }

    this.setData({ pendingId: mistakeId });

    try {
      await mistakeService.removeMistake(mistakeId);
      wx.showToast({ title: '已移除', icon: 'none' });
      this.removeFromList(mistakeId);
    } catch (err) {
      const code = err && err.code;
      if (code === ERROR_CODES.MISTAKE_NOT_FOUND) {
        this.removeFromList(mistakeId);
      } else {
        wx.showToast({
          title: (err && err.message) || '移除失败，请重试',
          icon: 'none',
        });
      }
    } finally {
      this.setData({ pendingId: null });
    }
  },

  /** 从本地列表移除一条并同步计数（不重拉列表，避免列表跳动）。 */
  removeFromList(mistakeId) {
    const list = this.data.list.filter(function (x) {
      return x.mistakeId !== mistakeId;
    });
    const removed = this.data.list.length - list.length;
    const patch = {
      list: list,
      total: Math.max(0, this.data.total - removed),
      hasMore: list.length < this.data.total - removed,
    };
    if (this.data.tab === 'todo') {
      patch.todoTotal = Math.max(0, this.data.todoTotal - removed);
    }
    this.setData(patch);
  },

  goStudy() {
    wx.switchTab({ url: '/pages/study/study' });
  },
});
