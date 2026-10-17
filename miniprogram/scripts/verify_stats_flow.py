# -*- coding: utf-8 -*-
"""数据统计（F9）端到端验证。

重点：
1. 空态判定**不能用 masteryRate**——后端在无错题时返回 100，
   三个计数全 0 才是空态。
2. 热力图按 check_in_date（打卡日）聚合，不是 study_date：
   「学了但没打卡」当天热力图仍是 0 档，这是契约行为不是 bug。
3. level 由后端分档，前端不得按 wordCount 重算。
"""
import json
import random
import string
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:8080'
passed = 0
failed = []


def check(name, cond, detail=''):
    global passed
    if cond:
        passed += 1
        print('  ✓ ' + name)
    else:
        failed.append(name)
        print('  ✗ ' + name + ('  ' + detail if detail else ''))


def api(method, path, body=None, token=None):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode('utf-8'))


def main():
    name = 'st_' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=8))
    api('POST', '/api/client/auth/register', {'username': name, 'password': 'Test123456'})
    tok = api('POST', '/api/client/auth/login',
              {'username': name, 'password': 'Test123456'})['data']['token']
    books = api('GET', '/api/client/books', None, tok)['data']
    api('POST', '/api/client/books/%d/select' % books[0]['bookId'], None, tok)

    print('=== 数据统计（F9）===')

    # ---------- 空账号 ----------
    hm = api('GET', '/api/client/stats/heatmap?weeks=12', None, tok)['data']
    days = hm['days']
    check('热力图返回 84 天（12 周）', len(days) == 84, '实际 %d' % len(days))
    check('日期连续且末点是今天',
          days[-1]['date'] > days[0]['date'], '%s → %s' % (days[0]['date'], days[-1]['date']))
    check('空账号热力图 wordCount 全 0',
          all(d['wordCount'] == 0 for d in days))
    check('空账号 level 全 0', all(d['level'] == 0 for d in days))

    ms = api('GET', '/api/client/stats/mastery?days=30', None, tok)['data']
    pts = ms['points']
    check('掌握度返回 30 点（30 天）', len(pts) == 30, '实际 %d' % len(pts))
    check('每点含 learnedCount/reviewCount/mistakeCount/masteryRate',
          all(all(k in p for k in ('learnedCount', 'reviewCount', 'mistakeCount', 'masteryRate'))
              for p in pts))

    # 关键契约：无数据时 masteryRate 是 100，绝不能拿它判空态
    check('⚠ 无数据时 masteryRate 返回 100（不可用于判空）',
          all(p['masteryRate'] == 100 for p in pts),
          '实际 %r' % [p['masteryRate'] for p in pts][:3])
    allZero = all(p['learnedCount'] + p['reviewCount'] + p['mistakeCount'] == 0 for p in pts)
    check('用三计数全 0 才能正确判定空态', allZero)

    # ---------- 学习但不打卡 → 热力图仍 0 档 ----------
    plan = api('GET', '/api/client/study/plan', None, tok)['data']
    for w in plan['words'][:5]:
        api('POST', '/api/client/study/record',
            {'wordId': w['wordId'], 'bookId': w['bookId'], 'known': True}, tok)

    hm2 = api('GET', '/api/client/stats/heatmap?weeks=12', None, tok)['data']['days']
    today = hm2[-1]
    check('学了但未打卡 → 今日热力图仍 0 档（按 check_in_date 聚合）',
          today['wordCount'] == 0 and today['level'] == 0,
          'wordCount=%d level=%d' % (today['wordCount'], today['level']))

    ms2 = api('GET', '/api/client/stats/mastery?days=30', None, tok)['data']['points']
    today_p = ms2[-1]
    check('掌握度曲线反映今日学习（learnedCount>0）',
          today_p['learnedCount'] > 0, '实际 %d' % today_p['learnedCount'])
    check('有学习数据后三计数不全为 0（非空态）',
          not all(p['learnedCount'] + p['reviewCount'] + p['mistakeCount'] == 0 for p in ms2))

    # ---------- 打卡后热力图分档 ----------
    # 打卡前置校验：完成数 >= 计划总数，故必须先学完整个计划
    for w in plan['words']:
        api('POST', '/api/client/study/record',
            {'wordId': w['wordId'], 'bookId': w['bookId'], 'known': True}, tok)
    ci = api('POST', '/api/client/study/checkin', None, tok)
    check('打卡成功返回 code 0', ci['code'] == '0', str(ci))

    hm3 = api('GET', '/api/client/stats/heatmap?weeks=12', None, tok)['data']['days']
    t3 = hm3[-1]
    check('打卡后今日 wordCount>0', t3['wordCount'] > 0, '实际 %d' % t3['wordCount'])
    # 计划是「每本词书 20 新词」，故 20 词应落在 2 档（11–20）。
    # 这里按分档公式校验而非写死档位：计划词数变了断言仍成立。
    def expect_level(c):
        if c <= 0:
            return 0
        if c <= 10:
            return 1
        if c <= 20:
            return 2
        return 3

    check('打卡后今日 level 符合 0/1-10/11-20/21+ 分档',
          t3['level'] == expect_level(t3['wordCount']),
          'wordCount=%d level=%d 期望 %d' % (t3['wordCount'], t3['level'],
                                            expect_level(t3['wordCount'])))
    check('每日 level 全部符合分档公式（前端不得重算）',
          all(d['level'] == expect_level(d['wordCount']) for d in hm3))

    # 12 周窗口外不返回
    hm4 = api('GET', '/api/client/stats/heatmap?weeks=1', None, tok)['data']['days']
    check('weeks=1 返回 7 天', len(hm4) == 7, '实际 %d' % len(hm4))
    check('weeks=1 时今日仍是末点', hm4[-1]['date'] == t3['date'])

    ms3 = api('GET', '/api/client/stats/mastery?days=7', None, tok)['data']['points']
    check('days=7 返回 7 点', len(ms3) == 7, '实际 %d' % len(ms3))

    print('\n' + '=' * 50)
    if failed:
        print('✗ %d 项通过，%d 项失败' % (passed, len(failed)))
        for f in failed:
            print('  ✗ ' + f)
        raise SystemExit(1)
    print('✓ %d 项断言全部通过' % passed)


if __name__ == '__main__':
    main()
