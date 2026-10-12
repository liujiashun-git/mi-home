# MiHome — 基于 RAG 的企业知识问答引擎

覆盖「文档接入 → 智能切片 → 向量化存储 → 多源检索 → 精排融合 → 流式对话」全链路的可独立部署知识引擎。

**技术栈**：Spring Boot 3.5 · JDK 21 · LangChain4j · Elasticsearch 8（KNN 向量 + BM25 全文）· Neo4j 5 · MySQL 8 · Redis 7 · MinIO · 阿里云百炼（Qwen）

## 核心特性

- **多源检索调度**：主干（向量 ∥ 关键词）无条件并行 + 图/SQL 各自挂闸门叠加，替代 LLM 互斥路由；RRF 按来源加权融合并归一化，精排带三级降级兜底
- **表格双重表示**：Excel/CSV 行转文本 chunk + 表摘要/列描述 chunk，引用带 Sheet/行号定位锚点；支持多 sheet、兄弟窗口邻域扩展
- **后处理增强**：doc2query 问题生成（问句形态索引条目指回原 chunk，带版本防护与按批取消）；文档摘要 chunk（gist 前置，输出契约校验 + 确定性兜底）
- **流式对话**：SSE 全程可见——检索进度时间线、引用来源徽章（向量/关键词/混合）、相似度与精排分
- **可选组件不阻塞**：精排模型缺失自动降级为 RRF 排序；中间件缺一路只降级不崩溃

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

## 项目结构

```
src/main/java/com/mi/home/know/engine/
├── ai/          # 意图识别、查询改写
├── chat/        # 对话应用服务、SSE 流式接口
├── document/    # 文档上传、切片、向量化、后处理（摘要/问题生成）
├── rag/         # 检索器、闸门路由、RRF 融合、精排、切片器
├── auth/        # 登录鉴权（Sa-Token）
└── infra/       # 分布式锁、雪花 ID 等
```
