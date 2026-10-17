/**
 * 数据统计页（F9）。
 *
 * 两块内容：
 *   1. 打卡热力图：近 12 周（84 格），4 档色阶，点格子 toast 当日词数，仅展示无跳转；
 *   2. 掌握度曲线：近 30 天双折线（掌握度% 实线 + 每日错题 虚线）。
 *
 * 零第三方图表库：热力图是 84 个 <view> + 4 个 CSS 等级类；
 * 折线图用 <canvas type="2d"> 手绘。一期只需要两组折线，
 * 引 ECharts/uCharts 会增加 300KB+ 包体且需要 npm 构建，不划算。
 */

const statsService = require('../../services/stats');
const dateUtil = require('../../utils/date');

/** 图表内边距（逻辑像素）。左留 32 给 % 轴，右留 28 给错题数轴。 */
const PAD = { top: 16, right: 30, bottom: 26, left: 34 };

Page({
  data: {
    loading: true,
    errorMsg: '',
    // 是否「完全没有数据」：三个计数全 0 且热力图全 0
    isEmpty: false,

    // 热力图
    cells: [],
    weekdayLabels: dateUtil.WEEKDAY_LABELS,
    heatmapMonths: [],

    // 折线图旁的图例与摘要
    masterySummary: '',
    mistakeSummary: '',
    chartReady: false,
  },

  onLoad() {
    this.loadAll();
  },

  onShow() {
    // 从学习页返回时数据可能已变，重拉一次；首次 onShow 紧随 onLoad，跳过。
    if (this.loadedOnce) {
      this.loadAll();
    }
  },

  async loadAll() {
    this.setData({ loading: true, errorMsg: '' });

    // 两个接口互不依赖，用 allSettled：一个失败不该让另一个也白拉。
    const results = await Promise.allSettled([
      statsService.getHeatmap(),
      statsService.getMastery(),
    ]);

    const heatmapRes = results[0];
    const masteryRes = results[1];

    if (heatmapRes.status === 'rejected' && masteryRes.status === 'rejected') {
      this.setData({
        loading: false,
        errorMsg: (heatmapRes.reason && heatmapRes.reason.message) || '数据加载失败',
      });
      return;
    }

    const days = heatmapRes.status === 'fulfilled' && heatmapRes.value
      ? heatmapRes.value.days || []
      : [];
    const points = masteryRes.status === 'fulfilled' && masteryRes.value
      ? masteryRes.value.points || []
      : [];

    // 空态判定：绝不能用 masteryRate。后端在「无错题」时返回 100，
    // 用 masteryRate === 0 判空会把「有学习但没错题」误判成空态。
    const noStudy = days.every((d) => (d.wordCount || 0) === 0)
      && points.every((p) => (p.learnedCount || 0) + (p.reviewCount || 0)
        + (p.mistakeCount || 0) === 0);

    const cells = dateUtil.buildHeatmapCells(days);
    const monthMarks = this.buildMonthMarks(cells);

    this.setData({
      loading: false,
      errorMsg: '',
      isEmpty: noStudy,
      cells: cells,
      heatmapMonths: monthMarks,
      masterySummary: this.buildMasterySummary(points),
      mistakeSummary: this.buildMistakeSummary(points),
    });
    // 用实例属性而非 data：它只控制 onShow 是否重拉，不参与渲染
    this.loadedOnce = true;

    if (!noStudy) {
      // canvas 必须在 setData 渲染出节点之后才能查询，延到下一帧
      wx.nextTick(() => {
        this.drawChart(points);
      });
    }
  },

  /**
   * 热力图底部月份标注。
   *
   * 每列是一周（7 格）。取每列第一格有数据的日期，若它的月份与上一列不同，
   * 就在该列位置标一次月份，避免 12 列都写「10月」。
   */
  buildMonthMarks(cells) {
    const marks = [];
    let lastMonth = '';
    for (let col = 0; col < cells.length / 7; col++) {
      const first = cells[col * 7];
      if (!first) {
        marks.push('');
        continue;
      }
      const month = String(first.date).slice(5, 7).replace(/^0/, '');
      if (month !== lastMonth) {
        marks.push(month + '月');
        lastMonth = month;
      } else {
        marks.push('');
      }
    }
    return marks;
  },

  buildMasterySummary(points) {
    if (!points.length) {
      return '';
    }
    const last = points[points.length - 1];
    return String(Math.round(last.masteryRate || 0));
  },

  buildMistakeSummary(points) {
    if (!points.length) {
      return '';
    }
    const total = points.reduce((sum, p) => sum + (p.mistakeCount || 0), 0);
    return String(total);
  },

  /** 点格子：toast 显示当日词数（PRD F9 明确「仅展示无跳转」）。 */
  onTapCell(e) {
    const ds = e.currentTarget.dataset;
    // 占位格（补齐首尾的 null）没有日期，不响应
    if (!ds.date) {
      return;
    }
    const count = Number(ds.count) || 0;
    wx.showToast({
      title: count > 0 ? ds.date + ' 学习 ' + count + ' 词' : ds.date + ' 未学习',
      icon: 'none',
    });
  },

  onRetry() {
    this.loadAll();
  },

  goStudy() {
    wx.switchTab({ url: '/pages/study/study' });
  },

  // ============================================================
  //  折线图（手绘 canvas 2d）
  // ============================================================

  /**
   * 双 y 轴折线：左轴掌握度 0–100%，右轴每日错题数 0–max。
   *
   * 为什么必须双轴：掌握度是百分比（0–100），错题数是小整数（0–10 量级）。
   * 共用一个轴的话错题折线会被压成贴地的一条直线，完全看不出波动。
   */
  drawChart(points) {
    if (!points || points.length === 0) {
      return;
    }

    wx.createSelectorQuery()
      .select('#masteryChart')
      .fields({ node: true, size: true })
      .exec((res) => {
        if (!res || !res[0] || !res[0].node) {
          // 画布节点拿不到（极少数机型/时序问题）：不抛错，页面本身仍可读摘要数字
          return;
        }

        const canvas = res[0].node;
        const width = res[0].width;
        const height = res[0].height;
        const dpr = wx.getSystemInfoSync().pixelRatio || 2;

        canvas.width = width * dpr;
        canvas.height = height * dpr;
        const ctx = canvas.getContext('2d');
        ctx.scale(dpr, dpr);
        ctx.clearRect(0, 0, width, height);

        const plotLeft = PAD.left;
        const plotRight = width - PAD.right;
        const plotTop = PAD.top;
        const plotBottom = height - PAD.bottom;
        const plotW = plotRight - plotLeft;
        const plotH = plotBottom - plotTop;

        const n = points.length;
        const xAt = (i) => (n === 1 ? plotLeft : plotLeft + (plotW * i) / (n - 1));

        const maxMistake = points.reduce(
          (m, p) => Math.max(m, p.mistakeCount || 0), 0
        );
        // 右轴上限取整到 2 的倍数，且至少 2，避免 0 除和刻度过于拥挤
        const mistakeCeil = Math.max(2, Math.ceil(maxMistake / 2) * 2);

        const yMastery = (v) => plotBottom - (plotH * (v || 0)) / 100;
        const yMistake = (v) => plotBottom - (plotH * (v || 0)) / mistakeCeil;

        // ---------- 网格 + 左轴（掌握度 %） ----------
        ctx.font = '10px sans-serif';
        ctx.textBaseline = 'middle';
        const gridCount = 4;
        for (let i = 0; i <= gridCount; i++) {
          const ratio = i / gridCount;
          const y = plotBottom - plotH * ratio;
          ctx.beginPath();
          ctx.strokeStyle = '#f0f0f0';
          ctx.lineWidth = 1;
          ctx.moveTo(plotLeft, y);
          ctx.lineTo(plotRight, y);
          ctx.stroke();

          const label = Math.round(ratio * 100) + '';
          ctx.fillStyle = '#bbbbbb';
          ctx.textAlign = 'right';
          ctx.fillText(label, plotLeft - 6, y);
        }

        // ---------- 右轴（错题数） ----------
        ctx.textAlign = 'left';
        for (let i = 0; i <= gridCount; i++) {
          const ratio = i / gridCount;
          const y = plotBottom - plotH * ratio;
          ctx.fillStyle = '#ddbbaa';
          ctx.fillText(String(Math.round(ratio * mistakeCeil)), plotRight + 6, y);
        }

        // ---------- 横轴日期（最多 5 个刻度，避免挤成一团） ----------
        ctx.fillStyle = '#bbbbbb';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'top';
        const step = Math.max(1, Math.ceil(n / 5));
        for (let i = 0; i < n; i += step) {
          ctx.fillText(dateUtil.shortMonthDay(points[i].date), xAt(i), plotBottom + 8);
        }
        // 末点必标，否则用户看不到「今天」
        const lastIdx = n - 1;
        if (lastIdx % step !== 0) {
          ctx.fillText(
            dateUtil.shortMonthDay(points[lastIdx].date), xAt(lastIdx), plotBottom + 8
          );
        }

        // ---------- 折线 1：掌握度（实线，蓝） ----------
        ctx.beginPath();
        ctx.strokeStyle = '#4a6cf7';
        ctx.lineWidth = 2;
        ctx.setLineDash([]);
        ctx.lineJoin = 'round';
        for (let i = 0; i < n; i++) {
          const x = xAt(i);
          const y = yMastery(points[i].masteryRate);
          if (i === 0) {
            ctx.moveTo(x, y);
          } else {
            ctx.lineTo(x, y);
          }
        }
        ctx.stroke();

        // ---------- 折线 2：每日错题（虚线，橙） ----------
        ctx.beginPath();
        ctx.strokeStyle = '#fa8c16';
        ctx.lineWidth = 1.5;
        ctx.setLineDash([4, 3]);
        for (let i = 0; i < n; i++) {
          const x = xAt(i);
          const y = yMistake(points[i].mistakeCount);
          if (i === 0) {
            ctx.moveTo(x, y);
          } else {
            ctx.lineTo(x, y);
          }
        }
        ctx.stroke();
        // 虚线状态要复位，否则后续绘制（末点圆点）也会变成虚线
        ctx.setLineDash([]);

        // ---------- 末点圆点：让「当前值」有视觉落点 ----------
        const lastP = points[lastIdx];
        ctx.beginPath();
        ctx.fillStyle = '#4a6cf7';
        ctx.arc(xAt(lastIdx), yMastery(lastP.masteryRate), 3, 0, Math.PI * 2);
        ctx.fill();

        this.setData({ chartReady: true });
      });
  },
});
