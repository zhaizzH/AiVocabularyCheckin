/**
 * 词书页（F3）。
 *
 * 分类筛选 + 加入/移出词书。列表接口返回裸数组（词书个位数量级，后端不分页）。
 */

const bookService = require('../../services/book');
const { BOOK_CATEGORIES, CATEGORY_LABEL, ERROR_CODES } = require('../../utils/constants');

Page({
  data: {
    categories: BOOK_CATEGORIES,
    activeCategory: '',
    books: [],
    loading: true,
    errorMsg: '',
    // 正在提交的 bookId，用于按书禁用按钮，防止连点重复提交
    pendingBookId: null,
  },

  onLoad() {
    this.loadBooks();
  },

  onPullDownRefresh() {
    this.loadBooks().then(() => {
      wx.stopPullDownRefresh();
    });
  },

  async loadBooks() {
    this.setData({ loading: true, errorMsg: '' });
    try {
      const books = await bookService.listBooks(this.data.activeCategory);
      this.setData({ books: this.decorate(books || []), loading: false });
    } catch (err) {
      this.setData({
        books: [],
        loading: false,
        errorMsg: (err && err.message) || '加载失败，请下拉重试',
      });
    }
  },

  /** 给每本书补上界面用的派生字段，避免在 WXML 里写表达式。 */
  decorate(books) {
    return books.map(function (book) {
      return {
        bookId: book.bookId,
        name: book.name,
        category: book.category,
        categoryLabel: CATEGORY_LABEL[book.category] || book.category,
        wordCount: book.wordCount,
        description: book.description,
        selected: book.selected,
        learnedCount: book.learnedCount || 0,
        // 进度百分比：没有已选/未学时为 0，避免出现 NaN
        progress:
          book.selected && book.wordCount > 0
            ? Math.round(((book.learnedCount || 0) / book.wordCount) * 100)
            : 0,
      };
    });
  },

  onSwitchCategory(e) {
    const value = e.currentTarget.dataset.value || '';
    if (value === this.data.activeCategory) {
      return;
    }
    this.setData({ activeCategory: value });
    this.loadBooks();
  },

  /** 加入 / 移出词书。同一按钮承担两个动作，靠 selected 分派。 */
  async onToggleBook(e) {
    const bookId = e.currentTarget.dataset.bookid;
    const selected = e.currentTarget.dataset.selected;

    if (this.data.pendingBookId !== null) {
      return;
    }

    // 移出需要二次确认（prd F3）：已学进度虽不删，但移出后今日计划随即减少，
    // 误点会让用户以为「学习记录丢了」。加入是幂等且无害的，不弹窗。
    if (selected) {
      const confirmed = await this.confirmRemove();
      if (!confirmed) {
        return;
      }
    }

    this.setData({ pendingBookId: bookId });

    try {
      if (selected) {
        await bookService.unselectBook(bookId);
        wx.showToast({ title: '已移出词书', icon: 'none' });
      } else {
        await bookService.selectBook(bookId);
        wx.showToast({ title: '已加入词书', icon: 'success' });
      }
      await this.loadBooks();
    } catch (err) {
      const code = err && err.code;
      // 2003 = 已经选过。这多半是本地状态过期（比如在另一台设备加过），
      // 对用户来说「已加入」就是想要的结果，所以静默刷新而不是报错。
      if (code === ERROR_CODES.BOOK_ALREADY_SELECTED) {
        await this.loadBooks();
      } else {
        wx.showToast({
          title: (err && err.message) || '操作失败，请重试',
          icon: 'none',
        });
      }
    } finally {
      this.setData({ pendingBookId: null });
    }
  },

  /** 移出词书的二次确认，包成 Promise 以便 await。 */
  confirmRemove() {
    return new Promise(function (resolve) {
      wx.showModal({
        title: '移出词书',
        content: '移出后今日学习计划会相应减少，已学过的单词会保留。确定移出吗？',
        confirmText: '移出',
        confirmColor: '#d4380d',
        success(res) {
          resolve(!!res.confirm);
        },
        fail() {
          // modal 弹不出来（极少见）时按「取消」处理，
          // 避免把删除类操作在用户未确认的情况下执行。
          resolve(false);
        },
      });
    });
  },
});
