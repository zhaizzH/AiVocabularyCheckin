# -*- coding: utf-8 -*-
"""全链路验收：一个全新用户走完 F1–F10。

这个脚本存在的意义是抓「单模块都对、串起来不通」的问题：
例如错题本依赖学习记录、报告依赖打卡天数、统计依赖打卡日期。
每个模块的单测都过了，但串起来可能因为前置条件顺序不对而断掉。

序列：
  F1 注册/登录 → F3 选词书 → F4 学词（含答错）→ F5 打卡
  → F7 看错题/AI 讲解/标记掌握 → F6 复习
  → F9 统计（热力图有格、掌握度有值）→ F8 AI 报告（3 天后）
  → F10 个人资料（改昵称/头像）

注意：F8 需要累计打卡 ≥ 3 天，新账号当天只能打卡 1 次，
故本脚本用 pymysql 补两天的 study_record 来满足前置。
"""
import datetime
import json
import os
import random
import string
import urllib.error
import urllib.request

import pymysql

BASE = 'http://127.0.0.1:8080'
# 凭据从环境变量读取，禁止硬编码（密码曾经硬编码并泄漏进 Git 历史）。
MYSQL = dict(host=os.environ.get('DB_HOST', '127.0.0.1'),
             user=os.environ.get('DB_USERNAME', 'root'),
             password=os.environ.get('DBPASS', os.environ.get('DB_PASSWORD', '')),
             database=os.environ.get('DB_NAME', 'ai_vocab'), charset='utf8mb4')

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
        with urllib.request.urlopen(req, timeout=90) as resp:
            return json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode('utf-8'))


def main():
    name = 'e2e_' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=8))

    print('=== F1 注册与登录 ===')
    reg = api('POST', '/api/client/auth/register',
              {'username': name, 'password': 'Test123456'})
    check('注册成功', reg['code'] == '0', str(reg.get('message')))
    check('注册返回 userId', bool(reg['data'].get('userId')))

    login = api('POST', '/api/client/auth/login',
                {'username': name, 'password': 'Test123456'})
    check('登录成功', login['code'] == '0', str(login.get('message')))
    tok = login['data']['token']
    check('拿到 token', bool(tok))
    check('登录响应含 userInfo', bool(login['data'].get('userInfo')))

    prof = api('GET', '/api/client/auth/profile', None, tok)
    check('带 token 可取个人资料', prof['code'] == '0')
    check('新账号累计打卡为 0', prof['data']['totalCheckInDays'] == 0)

    print('\n=== F3 词书 ===')
    books = api('GET', '/api/client/books', None, tok)['data']
    check('词书列表非空', len(books) > 0, '%d 本' % len(books))
    check('词书含 bookId/name/category',
          all(k in books[0] for k in ('bookId', 'name', 'category')))
    check('分类在 CET4/CET6/KAOYAN 内',
          books[0]['category'] in ('CET4', 'CET6', 'KAOYAN'), books[0]['category'])

    book_id = books[0]['bookId']
    sel = api('POST', '/api/client/books/%d/select' % book_id, None, tok)
    check('选择词书成功', sel['code'] == '0', str(sel))
    sel2 = api('POST', '/api/client/books/%d/select' % book_id, None, tok)
    check('重复选择返回 2003', sel2['code'] == '2003', str(sel2))

    print('\n=== F4 学习计划与学词 ===')
    plan = api('GET', '/api/client/study/plan', None, tok)['data']
    check('拿到学习计划', plan['total'] > 0, 'total=%d' % plan['total'])
    check('计划含 words 数组', len(plan['words']) > 0)
    check('计划含 date/finished/checkedIn 字段',
          all(k in plan for k in ('date', 'finished', 'checkedIn')))
    check('计划词的 bookId 与所选词书一致',
          all(w['bookId'] == book_id for w in plan['words']),
          '出现其他 bookId')

    words = plan['words']
    # 前 2 个答错（进错题本），其余答对
    for i, w in enumerate(words):
        known = i >= 2
        r = api('POST', '/api/client/study/record',
                {'wordId': w['wordId'], 'bookId': w['bookId'], 'known': known}, tok)
        if r['code'] != '0':
            check('学词上报应成功', False, str(r))
            break
    else:
        check('全部 %d 个词均已上报' % len(words), True)

    plan2 = api('GET', '/api/client/study/plan', None, tok)['data']
    check('上报后计划里 words 的 learned 状态更新',
          all(w.get('learned') for w in plan2['words']),
          '已学 %d/%d' % (sum(1 for w in plan2['words'] if w.get('learned')),
                          len(plan2['words'])))

    print('\n=== F5 打卡 ===')
    ci = api('POST', '/api/client/study/checkin', None, tok)
    check('打卡成功', ci['code'] == '0', str(ci))
    check('返回连续天数', ci['data'].get('streakDays', 0) >= 1,
          str(ci['data'].get('streakDays')))
    ci2 = api('POST', '/api/client/study/checkin', None, tok)
    check('重复打卡返回 3001', ci2['code'] == '3001', str(ci2))

    print('\n=== F7 错题本与 AI 讲解 ===')
    mk = api('GET', '/api/client/mistakes?mastered=0&page=1&size=20', None, tok)['data']
    check('答错的词进了错题本', mk['total'] == 2, '实际 %d' % mk['total'])
    check('错题 source 为 STUDY', mk['list'][0]['source'] == 'STUDY')
    check('错题 mastered 为布尔 false', mk['list'][0]['mastered'] is False)

    mid = mk['list'][0]['mistakeId']
    ex = api('POST', '/api/client/ai/explain', {'mistakeId': mid}, tok)
    check('AI 讲解成功', ex['code'] == '0', str(ex.get('message')))
    check('讲解有实质内容', len(ex['data'].get('explanation', '')) > 20)

    marked = api('POST', '/api/client/mistakes/%d/mastered' % mid, None, tok)
    check('标记已掌握成功', marked['code'] == '0', str(marked))
    done = api('GET', '/api/client/mistakes?mastered=1&page=1&size=20', None, tok)['data']
    check('已掌握列表有 1 条', done['total'] == 1, '实际 %d' % done['total'])

    print('\n=== F6 复习 ===')
    rev = api('GET', '/api/client/review/today', None, tok)
    check('复习列表可获取', rev['code'] == '0', str(rev.get('message')))
    rev_words = rev['data'].get('words', [])
    check('今日待复习为 0（stage=1 次日才复习）', len(rev_words) == 0,
          '实际 %d 个' % len(rev_words))

    # 造一个待复习词：把 next_review_date 改到今天、stage 提到 2
    conn = pymysql.connect(**MYSQL)
    cur = conn.cursor()
    cur.execute("SELECT id FROM user WHERE username=%s", (name,))
    uid = cur.fetchone()[0]
    cur.execute(
        "UPDATE word_progress SET next_review_date=CURDATE(), stage=2 "
        "WHERE user_id=%s AND learned=1 AND deleted=0 LIMIT 1", (uid,))
    conn.commit()
    conn.close()

    rev2 = api('GET', '/api/client/review/today', None, tok)['data']
    check('造数据后出现待复习词', len(rev2.get('words', [])) == 1,
          '实际 %d' % len(rev2.get('words', [])))

    if rev2.get('words'):
        rw = rev2['words'][0]
        rr = api('POST', '/api/client/review/record',
                 {'wordId': rw['wordId'], 'correct': True}, tok)
        check('复习上报成功（不带 bookId）', rr['code'] == '0', str(rr))
        rev3 = api('GET', '/api/client/review/today', None, tok)['data']
        check('复习后列表清空', len(rev3.get('words', [])) == 0,
              '实际 %d' % len(rev3.get('words', [])))

    print('\n=== F9 统计 ===')
    hm = api('GET', '/api/client/stats/heatmap?weeks=12', None, tok)['data']['days']
    check('热力图 84 天', len(hm) == 84, '实际 %d' % len(hm))
    check('今日热力图非 0（已打卡）', hm[-1]['wordCount'] > 0,
          'wordCount=%d' % hm[-1]['wordCount'])
    check('今日 level 与 wordCount 分档一致',
          hm[-1]['level'] == (0 if hm[-1]['wordCount'] == 0
                              else 1 if hm[-1]['wordCount'] <= 10
                              else 2 if hm[-1]['wordCount'] <= 20 else 3))

    ms = api('GET', '/api/client/stats/mastery?days=30', None, tok)['data']['points']
    check('掌握度 30 点', len(ms) == 30, '实际 %d' % len(ms))
    check('今日掌握度曲线有学习数据', ms[-1]['learnedCount'] > 0,
          'learnedCount=%d' % ms[-1]['learnedCount'])
    check('非空态（三计数不全 0）',
          not all(p['learnedCount'] + p['reviewCount'] + p['mistakeCount'] == 0
                  for p in ms))

    print('\n=== F8 AI 报告（需累计打卡 ≥3 天）===')
    r0 = api('POST', '/api/client/ai/report', None, tok)
    check('仅 1 天打卡时报告返回 5003', r0['code'] == '5003', str(r0))

    conn = pymysql.connect(**MYSQL)
    cur = conn.cursor()
    cur.execute("SELECT word_id, book_id FROM study_record WHERE user_id=%s LIMIT 1", (uid,))
    row = cur.fetchone()
    w_id, b_id = row[0], row[1]
    for i in range(1, 3):
        d = datetime.date.today() - datetime.timedelta(days=i)
        cur.execute(
            "INSERT INTO study_record "
            "(user_id, book_id, word_id, study_type, study_date, check_in_date, "
            " streak_days, correct, deleted, create_time, update_time) "
            "VALUES (%s,%s,%s,'STUDY',%s,%s,%s,1,0,NOW(),NOW())",
            (uid, b_id, w_id, d, d, i + 1))
    conn.commit()
    cur.execute("SELECT COUNT(DISTINCT check_in_date) FROM study_record "
                "WHERE user_id=%s AND deleted=0 AND check_in_date IS NOT NULL", (uid,))
    days = cur.fetchone()[0]
    conn.close()
    check('补足到 3 天打卡', days == 3, '实际 %d' % days)

    r1 = api('POST', '/api/client/ai/report', None, tok)
    check('3 天后报告生成成功', r1['code'] == '0', str(r1.get('message')))
    if r1['code'] == '0':
        rep = r1['data']['report']
        check('报告三段齐全',
              bool(rep['overview']) and bool(rep['weakness']) and bool(rep['suggestion']))
        check('报告含 generatedAt', bool(r1['data'].get('generatedAt')))

    print('\n=== F10 个人资料 ===')
    up = api('PUT', '/api/client/auth/profile', {'nickname': '端到端昵称'}, tok)
    check('改昵称成功', up['code'] == '0', str(up))
    check('昵称已生效', up['data']['nickname'] == '端到端昵称', up['data'].get('nickname'))

    up2 = api('PUT', '/api/client/auth/profile', {'avatar': 'avatar_5'}, tok)
    check('换头像成功', up2['code'] == '0', str(up2))
    check('头像已生效', up2['data']['avatar'] == 'avatar_5', up2['data'].get('avatar'))

    prof2 = api('GET', '/api/client/auth/profile', None, tok)['data']
    check('重新拉取资料两处修改都在',
          prof2['nickname'] == '端到端昵称' and prof2['avatar'] == 'avatar_5')

    print('\n' + '=' * 50)
    if failed:
        print('✗ %d 项通过，%d 项失败' % (passed, len(failed)))
        for f in failed:
            print('  ✗ ' + f)
        raise SystemExit(1)
    print('✓ 全链路 %d 项断言全部通过' % passed)


if __name__ == '__main__':
    main()
