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

- [ ] 上表 6 项全部删除，磁盘释放约 380MB
- [ ] `git status --porcelain` 只剩 R4 列出的 3 类范围外改动
- [ ] `.gitignore` 覆盖 R2 全部条目，且 `git check-ignore` 逐项验证通过
- [ ] `backend/scripts/`、`ai-service/scripts/`、`scripts/`、`miniprogram/scripts/` 一个不少
- [ ] 删除后 `frontend` 与 `backend` 仍能干净重建（`npm install` + `npm run build`、`mvn -o test` 至少编译通过）
- [ ] `handoff.md` 补一节「清理记录」：删了什么、怎么再生、`.tmp/sess` 内容去向
- [ ] journal 记录本次会话
