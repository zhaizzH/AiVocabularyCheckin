# 代码复用思考指南

> **目的**：写新代码之前先停下来想想——它是不是已经存在了？

---

## 问题所在

**重复代码是"不一致类 Bug"的头号来源。**

当你复制粘贴或重写已有逻辑时：
- Bug 修复不会同步到所有副本
- 行为随时间逐渐分叉
- 代码库越来越难读懂

---

## 写新代码之前

### 第 1 步：先搜索

```bash
# 搜索类似的函数名
grep -r "functionName" .

# 搜索类似的逻辑
grep -r "keyword" .
```

### 第 2 步：自问这些问题

| 问题 | 如果答案是"是"…… |
|------|------------------|
| 是否已有类似的函数？ | 直接使用或扩展它 |
| 这个模式别处用过吗？ | 遵循已有的模式 |
| 这段逻辑能做成共享工具吗？ | 在正确的位置创建 |
| 我是不是在从别的文件复制代码？ | **停**——抽成共享代码 |

---

## 常见重复模式

### 模式 1：复制粘贴函数

**反面**：把校验函数复制到另一个文件

**正面**：抽成共享工具函数，需要的地方 import

### 模式 2：相似组件

**反面**：新组件与已有组件 80% 相同

**正面**：通过 props/变体扩展现有组件

### 模式 3：重复的常量

**反面**：在多个文件里定义同一个常量

**正面**：单一数据源，到处 import

### 模式 4：重复的负载字段提取

**反面**：多个消费方各自对同一个 JSON/事件字段做局部断言：

```typescript
const description = (ev as { description?: string }).description;
const context = (ev as { context?: ContextEntry[] }).context;
```

即使只有两行，这也是重复的契约逻辑。每个消费方都各自定义了一遍
"合法负载长什么样"。

**正面**：把解码器、类型守卫或投影放在数据拥有者旁边：

```typescript
if (isThreadEvent(ev)) {
  renderThreadEvent(ev);
}
```

**规则**：如果同一个无类型负载字段在 2 处以上被读取，在出现第 3 个
读取方之前，先建立共享的类型守卫 / 归一化函数 / 投影。

---

## 什么时候抽象

**应该抽象**：
- 同一段代码出现 3 次以上
- 逻辑复杂到足以藏 Bug
- 多人都会用到

**不该抽象**：
- 只用一次
- 一眼就能看懂的一行代码
- 抽象比重复更复杂

---

## 批量修改之后

对多个文件做了相似修改之后：

1. **复查**：所有实例都改到了吗？
2. **搜索**：用 grep 找有没有漏网的
3. **考虑**：这些改动该不该抽象？

### Reducer 应使用穷尽式结构

当状态由类 action 的值（`action`、`kind`、`status`、`phase`）派生时，
优先用一个带 `switch` 的 reducer，而不是散落的 `if/else` 更新。

```typescript
// 反面——按 action 分散的状态迁移难以审计
if (action === "opened") { ... }
else if (action === "comment") { ... }
else if (action === "status") { ... }

// 正面——一个 reducer 拥有整张迁移表
switch (event.action) {
  case "opened":
    ...
    return;
  case "comment":
    ...
    return;
}
```

当事件日志是事实来源时这一点尤其重要。reducer 是被文档化的重放模型；
展示代码和命令不应该各自复制重放模型的片段。

---

## 提交前检查清单

- [ ] 已搜索过是否已有类似代码
- [ ] 没有本该共享却被复制粘贴的逻辑
- [ ] 共享解码器之外没有重复的无类型负载字段提取
- [ ] 常量只定义在一处
- [ ] 相似的模式遵循相同的结构
- [ ] Reducer/action 迁移集中在一个 reducer 或命令分发器中

---

## 陷阱：Python if/elif/else 没有穷尽性检查

**问题**：Python 的 if/elif/else 链没有编译期穷尽性检查。当你给一个
`Literal` 类型新增取值时，已有的 if/elif/else 链会静默落入 `else` 分支，
拿到错误的默认值。

**症状**：新平台部分功能正常——某些方法返回的是默认值而非平台专属值，
且不报任何错误。

**示例**：
```python
# 反面："gemini" 落入 else，返回了 "claude"
@property
def cli_name(self) -> str:
    if self.platform == "opencode":
        return "opencode"
    else:
        return "claude"  # gemini 静默拿到 "claude"！

# 正面：每个平台都有显式分支
@property
def cli_name(self) -> str:
    if self.platform == "opencode":
        return "opencode"
    elif self.platform == "gemini":
        return "gemini"
    else:
        return "claude"
```

**预防**：给 Python `Literal` 类型新增取值时，搜索所有基于该类型的
if/elif/else 链并补上显式分支。不要指望 `else` 对新值是正确的。

---

## 陷阱：不对称机制产生相同输出

**问题**：当两套不同机制必须产出相同的文件集合时（例如初始化用递归目录
复制、更新用手动文件清单），结构性变更（重命名、移动、新增子目录）只会
通过自动机制传播，手动的那份会静默漂移。

**症状**：初始化完全正常，但更新会把文件写到错误路径或整个漏掉。

**预防**：
- **最佳**：消除不对称——让手动路径调用自动机制
- **实在无法避免**：加一个回归测试，对比两套机制的输出
- 迁移目录结构时，搜索所有引用旧结构的代码路径

---

## 本项目中的应用

- 前端两个端（`miniprogram/` 与 `frontend/`）各自维护一份
  `utils/request` 封装与 `services/` 层——这是**有意按平台分离**，
  不要跨端合并；但同一端内绝不允许出现第二份请求封装。
- 后端两个服务（`backend/` 与 `ai-service/`）各自定义请求/响应模型——
  接口字段变更时两端都要改，改前先全局搜索字段名。
- 错误码常量只在 `backend/.../common/ErrorCode.java` 定义一次，
  前端按 `code` 字符串比对，不在前端重复定义语义。
