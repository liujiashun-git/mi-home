# MiHome — 基于 RAG 的企业知识问答引擎

覆盖「文档接入 → 智能切片 → 向量化存储 → 多源检索 → 精排融合 → 流式对话」全链路的可独立部署知识引擎。

**技术栈**：Spring Boot 3.5 · JDK 21 · LangChain4j · Elasticsearch 8（KNN 向量 + BM25 全文）· Neo4j 5 · MySQL 8 · Redis 7 · MinIO · 阿里云百炼（Qwen）

## 核心特性

- **多源检索调度**：主干（向量 ∥ 关键词）无条件并行 + 图/SQL 各自挂闸门叠加，替代 LLM 互斥路由；RRF 按来源加权融合并归一化，精排带三级降级兜底
- **表格双重表示**：Excel/CSV 行转文本 chunk + 表摘要/列描述 chunk，引用带 Sheet/行号定位锚点；支持多 sheet、兄弟窗口邻域扩展
- **图谱抽取（GraphRAG）**：入库时按 chunk fan-out 做两段式 LLM 抽取（实体 → 关系），实体带 chunk 溯源写 Neo4j；运行时用改写后的 query 抽实体查图，**图只当路由器——产出 chunk ID，正文回 chunk 库取**，因此图结果与向量结果同构，共用一套 RRF/精排/引用管线
- **后处理增强**：doc2query 问题生成（问句形态索引条目指回原 chunk，带版本防护与按批取消）；文档摘要 chunk（gist 前置，输出契约校验 + 确定性兜底）
- **流式对话**：SSE 全程可见——检索进度时间线、引用来源徽章（向量/关键词/混合）、相似度与精排分
- **可选组件不阻塞**：精排模型缺失自动降级为 RRF 排序；中间件缺一路只降级不崩溃（Neo4j 不可用时图谱链路整体跳过，一次 LLM 都不烧）

## 快速开始

```bash
# 1. 启动中间件（MySQL/ES/Neo4j/Redis/MinIO，首次会自动建库）
docker compose up -d

# 2. 配置环境变量（API Key 必填，模型名可覆盖默认值）
cp .env.example .env    # 编辑 .env，填入 DASHSCOPE_API_KEY

# 3. 打包并启动
mvn package -DskipTests
java -jar target/mi-home-1.0.0-SNAPSHOT.jar --server.port=8009
```

打开 `http://localhost:8009/login.html`。

| 端 | 账号 | 密码 |
|---|---|---|
| 用户端 | 13800138000 | 123456 |
| 员工端（知识库管理） | TEST001 | 123456 |

> 初始化数据由 `src/main/resources/sql/tables.sql` 自动载入；文档上传入口在 `/upload.html`，仅 Markdown / Excel / CSV 可本地解析入库（PDF/Word 需自行部署 MinerU 并配置 `file.parse.api.url`）。

## 主要配置

| 配置 | 说明 |
|---|---|
| `DASHSCOPE_API_KEY` | 百炼 API Key（对话 / 向量模型，`.env`） |
| `CHAT_MODEL_NAME` / `RAG_MODEL_NAME` / `TITLE_MODEL_NAME` | 模型名可配，默认 `qwen-plus` / `qwen-flash` |
| `know-engine.retrieval.mode` | `gate`（主干并行+闸门，默认）/ `router`（LLM 互斥路由回退） |
| `know-engine.retrieval.vector-weight` / `keyword-weight` | RRF 各路权重 |
| `know-engine.postprocess.*` | 问题生成 / 文档摘要开关与条数 |
| `know-engine.graph-extraction.enabled` | 图谱抽取总开关（**关掉则入库不抽、运行时也不查图**）。注意成本：每 chunk 两次 LLM 调用，只对关系类问题确实存在的语料开 |
| `know-engine.graph-extraction.knowledge-base` | 图的一级命名空间。入库按它写节点、运行时按它查节点，改这个值等于换一张图 |

## 图谱抽取（GraphRAG）

**数据模型**：`(:Entity {kbId, name, type, desc, chunks: []})` + `(:Entity)-[:RELATES {type, desc, strength, chunks: []}]->(:Entity)`，
唯一约束打在 `(kbId, name)` 上；`chunks` 是「哪些 chunk 提到了这个实体」，**追加累积**（去重），也是图 → 正文的回链。

**入库侧**（`graph/`，主干向量化完成后异步触发，独立线程池）：

```
文档入库完成（VECTOR_STORED）
  → 文档级任务：筛出"待抽取的正文块"（跳过表摘要/列描述/文档摘要/已抽过的）
  → fan-out 到块级任务池：一个 chunk 一个任务
       ├ 第一段：实体抽取（封闭 11 类清单，类型拿不准就跳过）
       ├ 第二段：关系抽取（输入 = 第一段实体 + 原文，明令禁止编造）
       ├ 写图前重读 chunk（防 LLM 期间被编辑/删除）
       └ 一个 chunk 一个事务落图，节点带 chunkId 溯源
```

六条边界处理：任务已过期（LLM 前查文档状态）、**模型用散文拒绝输出属合法结果不重试**、配置关闭/Neo4j 不可用整段跳过、写图前重读 chunk、chunk 被编辑则丢弃结果、写入前挂溯源。

**运行时**（实体图检索路）：

```
改写后的 query → 抽实体（LLM，抽不出则跳过图路）
  → SearchNode(kbId, 实体名)：命中节点 + 一跳邻接
  → 收集节点的 chunks → 去重 → 按 ID 从 chunk 库取【正文】
  → 校验可见范围（与向量路同一白名单）→ 包装成标准 SearchResult（source=graph）
  → 与向量/关键词结果一起 RRF 融合 + 精排
```

三条与主干的约定：**去重不自己做**（带上 embeddingId，RRF 按 key 合一）；**不打 skipRerank**（图路产出的是候选 chunk，不是最终答案文本，必须进融合与精排）；**不给图命中编相似度分**（没有向量分就是没有，最终分由精排模型给出）。

**删除清理**（引用计数语义）：文档删除 / 版本失效时按 chunkId 摘溯源，`chunks` 被摘空的节点才真正删除——别的文档可能也提到同一个实体。

**验收接口**（按顺序验，别一上来就怀疑 Neo4j）：

| 接口 | 验证什么 |
|---|---|
| `GET /ai/module/testGraphExtract?text=...` | 模型从一段文本里抽出了什么实体+关系（不落库） |
| `GET /ai/module/testGraphQueryEntity?query=...` | 查询侧实体抽取（含实际送进模型的 prompt） |
| `GET /ai/module/testGraphSearchNodes?query=...` | 实体名 → 图节点 → chunk ID（"图当路由器"的前半程） |
| `GET /ai/module/testGraphChunks?query=...` | 完整图路：实体 → 节点 → chunk ID → 正文 |

## 项目结构

```
src/main/java/com/mi/home/know/engine/
├── ai/          # 意图识别、查询改写
├── chat/        # 对话应用服务、SSE 流式接口
├── document/    # 文档上传、切片、向量化、后处理（摘要/问题生成）
├── graph/       # 图谱抽取：模型、Neo4j 存储（AddGraph/DelGraph/SearchNode）、两段式抽取
├── rag/         # 检索器、闸门路由、RRF 融合、精排、切片器
├── auth/        # 登录鉴权（Sa-Token）
└── infra/       # 分布式锁、雪花 ID 等
```
