# 全仓一次性文件与构建产物清理

## Goal

清掉仓库里**可再生**的构建产物、虚拟环境、依赖目录与一次性调试输出，让工作区只留源码与文档；同时核对 `.gitignore` 是否已覆盖全部此类产物，避免下次又把它们带进提交。

## Background

四端（backend / ai-service / miniprogram / frontend）全部完成后，工作区堆积了约 380MB 可再生内容。这些都不是源码，但会干扰 `git status` 判断、拖慢编辑器索引、也在磁盘上白占空间。

排查已确认：**没有**未跟踪的临时文件（`.gitignore` 一直在生效），磁盘上的产物全部是「已被忽略、正常存在」的可再生项。

## Requirements

### R1 删除可再生产物

按下列清单删除，删前逐项确认「删了能靠一条命令再生」：

| 路径 | 大小 | 再生方式 |
|---|---|---|
| `frontend/node_modules/` | 216M | `cd frontend && npm install` |
| `ai-service/.venv/` | 129M | `cd ai-service && python -m venv .venv && .venv/Scripts/pip install -r requirements.txt` |
| `backend/target/` | 32M | `cd backend && mvn package` |
| `.tmp/` | 1.8M | **不可再生，但内容全是一次性调试残留**（见 R3） |
| `frontend/dist/` | 1.2M | `cd frontend && npm run build` |
| `frontend/*.tsbuildinfo` | 小 | `cd frontend && npx tsc -b` |

### R2 核对 .gitignore 覆盖

确认以下均在 `.gitignore` 中，缺则补：`node_modules/`、`.venv/`、`target/`、`dist/`、`*.tsbuildinfo`、`.tmp/`。验证方式：删除后 `git status --porcelain` 只应剩本任务范围外的遗留项。

### R3 明确保留项

- **`backend/scripts/` 全部保留**（用户决策）。其中 `g4-smoke.py`/`g4-crossday.py`/`g5-smoke.py`/`stage9-stats-smoke.py`/`stage10-admin-smoke.py` 虽是为闸口写的一次性验收脚本，但被 `smoke-test.sh` 一键自测调用；`verify-schema-entities.py`/`verify-h2-schema-parity.py` 是结构一致性护栏，防测试假绿。
- **`ai-service/scripts/`、`scripts/`、`miniprogram/scripts/` 全部保留**。
- **`.tmp/sess/*.md` 的价值已在 journal 中**：这些是历次会话的结论文本，内容已并入 `.trellis/workspace/zhaizzH/journal-1.md`。删除前抽查确认。

### R4 不动本任务范围外的改动

工作区存在三项**本任务之前就有**的未提交改动，一律不碰、不提交：

- `docs/logo/*.png` 删除（23 个）
- `docs/小程序注册信息-名称简称介绍.md` 删除
- `miniprogram/project.config.json` 修改（微信开发者工具自动写入的字段）

## Constraints

- 严禁 `git add -A`；只提交任务范围内路径。
- 删除前必须记录清单与大小，删除后必须复核「删了什么、还能不能再生」。
- 本任务**不改任何源码**，纯删除 + 文档。

## Acceptance Criteria

- [x] 上表 6 项全部删除，磁盘 **414M → 35M**（释放 379MB）
- [x] `git status --porcelain` 只剩 R4 列出的 3 类范围外改动（+ 本任务自身）
- [x] `.gitignore` 覆盖 R2 全部条目，14 项 `git check-ignore -v` 逐条验证命中
- [x] `backend/scripts/`(10)、`ai-service/scripts/`(2)、`scripts/`(7)、`miniprogram/scripts/`(10) 一个不少
- [x] 删除后重建全部通过：`npm install + tsc -b + build` 8.22s、`mvn -o compile` EXIT=0、`pytest` 60 passed/4 skipped、4 套管理端验证 47/27/61/54 全绿
- [x] `handoff.md` 新增 §9「工作区清理记录」：删了什么、怎么再生、`.tmp/sess` 内容去向、首次 clone 要跑什么
- [x] journal Session 15 已记录

## 落地记录

提交 `f338e34`。额外收获：删除构建产物后首次在无 `node_modules` 环境下运行
`verify_admin_frontend.py`，暴露了「npx esbuild 静默失败、stderr 为空、报错无原因」
的缺陷，已加前置 `node_modules` 检查 + 明确提示。**清理本身也是一次测试。**
