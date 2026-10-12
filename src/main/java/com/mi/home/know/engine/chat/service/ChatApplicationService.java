package com.mi.home.know.engine.chat.service;

import com.mi.home.know.engine.ai.constant.KnowEngineIntent;
import com.mi.home.know.engine.ai.model.IntentRecognitionResult;
import com.mi.home.know.engine.ai.service.CommonChatService;
import com.mi.home.know.engine.ai.service.IntentRecognitionService;
import com.mi.home.know.engine.ai.service.KnowEngineChatAiService;
import com.mi.home.know.engine.ai.service.PromptService;
import com.mi.home.know.engine.ai.service.TitleSummaryService;
import com.mi.home.know.engine.business.converter.CarInfoConverter;
import com.mi.home.know.engine.business.converter.MyCarConverter;
import com.mi.home.know.engine.business.entity.CarInfo;
import com.mi.home.know.engine.business.entity.MyCar;
import com.mi.home.know.engine.business.service.CarInfoService;
import com.mi.home.know.engine.business.service.MyCarService;
import com.mi.home.know.engine.business.service.UserRoleService;
import com.mi.home.know.engine.chat.constant.ChatSource;
import com.mi.home.know.engine.chat.entity.ChatParam;
import com.mi.home.know.engine.chat.memory.DatabaseChatMemoryStore;
import com.mi.home.know.engine.document.entity.TableMeta;
import com.mi.home.know.engine.document.service.KnowEngineTableMetaService;
import com.mi.home.know.engine.document.service.KnowledgeSegmentService;
import com.mi.home.know.engine.document.util.DocumentPermissionUtils;
import com.mi.home.know.engine.graph.config.GraphProperties;
import com.mi.home.know.engine.graph.service.GraphQueryEntityService;
import com.mi.home.know.engine.graph.store.GraphStore;
import com.mi.home.know.engine.rag.constant.RoleEnum;
import com.mi.home.know.engine.rag.modules.*;
import com.mi.home.know.engine.rag.modules.reranker.BgeScoringModel;
import com.alibaba.fastjson2.JSON;
import dev.langchain4j.community.rag.content.retriever.neo4j.Neo4jGraph;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.scoring.onnx.OnnxScoringModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.injector.ContentInjector;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.router.QueryRouter;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchConfigurationFullText;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchConfigurationKnn;
import dev.langchain4j.store.embedding.filter.Filter;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.RestClient;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.mi.home.know.engine.rag.config.ElasticSearchConfiguration.INDEX_NAME;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.ACCESSIBLE_BY;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.CONTENT_TYPE;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.SOURCE_KEYWORD;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.SOURCE_VECTOR;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.TYPE_TABLE_COLUMN;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.TYPE_TABLE_SUMMARY;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import static java.nio.charset.StandardCharsets.UTF_8;

@Service
@Slf4j
public class ChatApplicationService {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private StreamingChatModel streamingChatModel;

    @Autowired
    private CommonChatService commonChatService;

    private IntentRecognitionService intentRecognitionService;

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private KnowledgeSegmentService knowledgeSegmentService;

    @Autowired
    private RestClient restClient;

    @Autowired
    private Driver neo4jDriver;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private KnowEngineTableMetaService knowEngineTableMetaService;

    @Autowired
    private PromptService promptService;

    @Autowired
    private OpenAiEmbeddingModel openAiEmbeddingModel;

    @Autowired
    private MyCarService myCarService;

    @Autowired
    private CarInfoService carInfoService;

    @Autowired
    private DatabaseChatMemoryStore databaseChatMemoryStore;

    @Autowired
    private GraphStore graphStore;

    @Autowired
    private GraphQueryEntityService graphQueryEntityService;

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String chatModelApiKey;

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String chatModelBaseUrl;

    @Value("classpath:prompts/text-to-sql-prompt.txt")
    private Resource textToSqlPrompt;

    @Value("classpath:prompts/text-to-cypher-prompt.txt")
    private Resource textToCypherPrompt;

    @Value("classpath:sql/retrieve_tables.sql")
    private Resource tablesSql;

    // ==================== 检索配置 ====================

    /**
     * 检索模式：
     * <ul>
     *   <li>{@code gate}（默认）—— 主干（向量 ∥ 关键词）无条件并行 + 其他路各自挂闸门叠加</li>
     *   <li>{@code router} —— 回退到基于 LLM 的互斥路由（一次调用只选一路）</li>
     * </ul>
     * 出问题时可一键切回 router，不需要改代码。
     */
    @Value("${know-engine.retrieval.mode:gate}")
    private String retrievalMode;

    /**
     * 是否启用图闸门：true = 意图识别抽到实体才查图；false = 每次对话都查图
     */
    @Value("${know-engine.retrieval.graph-gate-enabled:true}")
    private boolean graphGateEnabled;

    /**
     * 主干每路召回条数。粗排多捞、精排收敛，比"每路只取 5 条"更能兜住正确答案。
     */
    @Value("${know-engine.retrieval.pool-size:20}")
    private int retrievalPoolSize;

    /**
     * RRF 名次平滑常数
     */
    @Value("${know-engine.retrieval.rrf-k:60}")
    private int rrfK;

    /**
     * RRF 向量路权重
     */
    @Value("${know-engine.retrieval.vector-weight:0.7}")
    private double vectorWeight;

    /**
     * RRF 关键词路权重
     */
    @Value("${know-engine.retrieval.keyword-weight:0.3}")
    private double keywordWeight;

    // ==================== 图谱抽取（入库侧产物，运行时按实体名查图） ====================

    /**
     * 图谱抽取配置。
     * <p>
     * <b>一个开关管两端</b>：关掉时入库不抽（每块两次 LLM 调用，这是整条流水线里最贵的一环），
     * 运行时也不查——图里没东西可查，再去抽一次查询实体纯属浪费。
     * <p>
     * 注意 {@code knowledgeBase} 必须是全局唯一的值：入库侧按它写节点，运行时按它查节点。
     */
    @Autowired
    private GraphProperties graphProperties;

    /**
     * RAG 流式生成用的模型名（质量优先）。
     * 做成可配：不同百炼账号开通的模型不同，写死会导致换账号时报 model not found。
     */
    @Value("${know-engine.model.rag:qwen-plus}")
    private String ragModelName;

    /**
     * 异步标题生成用的模型名（便宜快即可）
     */
    @Value("${know-engine.model.title:qwen-flash}")
    private String titleModelName;

    /**
     * 是否启用「我的车辆」门禁。
     * <p>
     * 开启时（原实现行为）：网页端提问若被判为「售后维修与保养」或「车辆使用与技术指导」，
     * 且既没抽到 car_id、用户名下也没有车辆记录，就直接返回
     * {@code [WARN]:您还没有添加车辆信息，请先添加车辆信息} —— <b>不会进入检索</b>。
     * 这是 4S 店客服场景的设计：问维保必须先选车。
     * <p>
     * 但当前知识库是<b>公开的产品资料与售后政策</b>（车型参数、质保政策、配件价格、召回记录），
     * 这类问题本身就能直接检索回答，与「用户名下是否有车」无关。此时门禁会把
     * 「动力电池衰减到多少可以免费更换？」这类正常问题全部挡掉，导致检索链路根本走不到。
     * <p>
     * 因此默认 <b>false</b>（关闭）；需要演示 4S 店选车流程时，置为 true 即可恢复原行为。
     */
    @Value("${know-engine.chat.car-gate-enabled:false}")
    private boolean carGateEnabled;

    /**
     * RAG 对话生成专用 ChatModel，使用更强的模型和较低温度以提升回答质量
     */
    private StreamingChatModel ragChatModel;

    @PostConstruct
    public void init() {
        ragChatModel = OpenAiStreamingChatModel.builder()
                .apiKey(chatModelApiKey)
                .baseUrl(chatModelBaseUrl)
                .modelName(ragModelName)
                .logRequests(true)
                .logResponses(true)
                .temperature(0.2)
                .topP(0.9)
                .customParameters(Map.of("enable_thinking", false))
                .build();

        intentRecognitionService = AiServices.builder(IntentRecognitionService.class).chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder().id(memoryId).maxMessages(10).chatMemoryStore(databaseChatMemoryStore).build()).build();
    }

    /**
     * 统一流式对话入口。
     * <p>
     * 封装原本散落在 {@link com.mi.home.know.engine.chat.controller.ChatController} 中的完整对话流程：
     * 会话创建（可选）、异步标题生成、保存用户/助手消息、意图识别、不相关问题兜底通用对话、
     * 相关问题 RAG 对话。HTTP 接口与钉钉机器人回调均通过此方法复用同一条对话链路。
     *
     * @param userId         用户ID
     * @param content        用户问题
     * @param conversationId 会话ID（可选，为空则自动创建新会话）
     * @return 包含进度消息、[DONE] 事件及 LLM token 的 SSE 流
     */
    public Flux<String> chat(String userId, String content, String conversationId, ChatSource chatSource) {
        // 1. 处理会话：没有 conversationId 则创建新会话
        final String finalConversationId;
        if (conversationId == null || conversationId.isBlank()) {
            String tempTitle = content.substring(0, Math.min(content.length(), 20));
            finalConversationId = chatConversationService.createConversation(userId, tempTitle);
            log.info("创建新会话: conversationId={}, tempTitle={}", finalConversationId, tempTitle);

            // 异步：用虚拟线程调用 LLM 生成摘要标题，完成后回写到数据库
            Thread.ofVirtual().name("title-summary-" + finalConversationId).start(() -> {
                try {
                    OpenAiChatModel titleChatModel = OpenAiChatModel.builder()
                            .apiKey(chatModelApiKey)
                            .modelName(titleModelName)
                            .temperature(0.7)
                            .baseUrl(chatModelBaseUrl)
                            .customParameters(Map.of("enable_thinking", false))
                            .build();
                    TitleSummaryService titleSummaryService = AiServices.builder(TitleSummaryService.class)
                            .chatModel(titleChatModel)
                            .build();
                    String aiTitle = titleSummaryService.generateTitle(content);
                    chatConversationService.updateTitle(finalConversationId, aiTitle);
                    log.info("异步标题更新完成: conversationId={}, title={}", finalConversationId, aiTitle);
                } catch (Exception e) {
                    log.warn("异步标题生成失败, 保留临时标题: conversationId={}", finalConversationId, e);
                }
            });
        } else {
            finalConversationId = conversationId;
        }

        // 2. 保存用户消息
        String messageId = chatMessageService.saveUserMessage(finalConversationId, content);
        String assistantMessageId = chatMessageService.saveAssistantMessage(finalConversationId);

        // 3. 流式返回：先发送意图识别进度，再执行意图识别
        return Flux.just("[PROGRESS]:正在识别您的意图...")
                .concatWith(
                        Mono.fromCallable(() -> intentRecognitionService.chat(finalConversationId, content))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMapMany(intentRecognitionResult -> {
                                    // 意图识别完成后清除缓存，避免意图识别的AI响应污染后续RAG对话的历史记忆
                                    databaseChatMemoryStore.evictCache(finalConversationId);

                                    // 4. 如果用户问题不相关，使用一个通用的LLM做对话
                                    if (!intentRecognitionResult.related()) {
                                        StringBuilder contentBuilder = new StringBuilder();
                                        return Flux.concat(
                                                Flux.just("[PROGRESS]:正在为您生成回答..."),
                                                commonChatService.streamChat(userId, content)
                                                        .doOnNext(token -> contentBuilder.append(token))
                                                        .doOnComplete(() -> chatMessageService.updateContent(assistantMessageId, contentBuilder.toString()))
                                        );
                                    }

                                    // 5. 相关问题，走RAG流程（进度由内部组件发出）
                                    return ragChat(new ChatParam(userId, finalConversationId, messageId, content, assistantMessageId, intentRecognitionResult, chatSource));
                                })
                )
                .doOnError(e -> log.error("流式对话异常: conversationId={}", finalConversationId, e))
                .concatWith(Mono.just("[DONE]:" + finalConversationId));
    }

    /**
     * RAG 流式对话。
     * <p>
     * 1. 根据意图识别结果，判断是否需要车辆信息
     * 2. 如果车辆信息不完善，则返回车辆信息不完善提示
     * 3. 根据意图识别结果，判断是否需要车辆信息
     * </p>
     */
    public Flux<String> ragChat(ChatParam chatParam) {
        KnowEngineIntent intent = KnowEngineIntent.getIntent(chatParam.intentRecognitionResult());

        /**
         * 只有用户通过网页端访问时，才需要车辆信息；且仅在 car-gate-enabled=true 时生效。
         * 默认关闭：当前知识库是公开的产品资料与售后政策，提问不依赖「用户名下是否有车」。
         */
        if (carGateEnabled && chatParam.chatSource() == ChatSource.USER_WEB) {
            // 如果是维保服务、技术支持，则需要车辆信息
            if (intent == KnowEngineIntent.CAR_MAINTENANCE
                    || intent == KnowEngineIntent.CAR_TECH_SUPPORT) {
                if (chatParam.intentRecognitionResult().entities().car_id() == null) {
                    List<MyCar> myCars = myCarService.getCarByUserId(chatParam.userId());
                    if (CollectionUtils.isEmpty(myCars)) {
                        return Flux.just("[WARN]:您还没有添加车辆信息，请先添加车辆信息");
                    } else if (myCars.size() >= 1) {
                        return Flux.just("[CARD]:请先选择车辆")
                                .concatWith(Flux.just("[CARD_CHOICE_MYCAR]:" + JSON.toJSONString(MyCarConverter.INSTANCE.toVOList(myCars))));
                    }
                }
            }

            // 如果是营销政策，则需要车辆信息
            if (intent == KnowEngineIntent.CAR_MARKETING) {
                if (chatParam.intentRecognitionResult().entities().car_model() == null) {
                    List<CarInfo> carInfoList = carInfoService.getCarInfoByBrand(null);
                    return Flux.just("[CARD]:请先选择您要咨询的车辆")
                            .concatWith(Flux.just("[CARD_CHOICE_CAR]:" + JSON.toJSONString(CarInfoConverter.INSTANCE.toVOList(carInfoList))));
                }
            }
        }

        return doChat(chatParam);
    }

    /**
     * 流式对话
     * <p>
     * 使用 Flux.create() 将 RAG 管道各环节的进度消息与 LLM 流式输出桥接到同一个 Flux 中，
     * 确保进度消息在对应的 LLM token 之前到达前端。
     * <p>
     * 进度推送环节：
     * <ol>
     *   <li>问题改写 — 由 {@link KnowEngineQueryTransformer} 发送</li>
     *   <li>问题路由 — 由 {@link KnowEngineQueryRouter} 发送</li>
     *   <li>排序筛选 — 由 {@link ProgressAwareContentAggregator} 发送</li>
     *   <li>生成回答 — 由 {@link ProgressAwareContentAggregator} 在聚合完成后发送</li>
     * </ol>
     *
     * @param chatParam 对话参数
     */
    public Flux<String> doChat(ChatParam chatParam) {

        return Flux.<String>create(sink -> {
                    // 进度回调：同时写入 sink 和外部回调
                    Consumer<String> processCallback = sink::next;

                    // 构建查询改写器（带进度回调）
                    KnowEngineQueryTransformer queryTransformer = new KnowEngineQueryTransformer(chatModel, chatParam.messageId(), processCallback);

                    Filter accessibleByFilter = buildFilter(chatParam);

                    ProgressAwareContentRetriever embeddingRetriever = new ProgressAwareContentRetriever(KnowEngineElasticsearchContentRetriever.builder()
                            .configuration(ElasticsearchConfigurationKnn.builder().build())
                            .maxResults(retrievalPoolSize)
                            .minScore(0.5)
                            .embeddingModel(openAiEmbeddingModel)
                            .restClient(restClient)
                            .indexName(INDEX_NAME)
                            .knowledgeSegmentService(knowledgeSegmentService)
                            .filter(accessibleByFilter)
                            .build(), processCallback, "正在检索知识库内容...");

                    ProgressAwareContentRetriever fullTextRetriever = new ProgressAwareContentRetriever(KnowEngineElasticsearchContentRetriever.builder()
                            .configuration(ElasticsearchConfigurationFullText.builder().build())
                            .restClient(restClient)
                            .embeddingModel(openAiEmbeddingModel)
                            .knowledgeSegmentService(knowledgeSegmentService)
                            .indexName(INDEX_NAME)
                            .filter(accessibleByFilter)
                            .maxResults(retrievalPoolSize)
                            .build(), processCallback, "正在匹配关键词内容...");

                    // ========== SQL 闸门的"探测"检索器 ==========
                    // 只用它回答一个问题：这次的问题有没有把某张表的相关内容召回来？
                    // 召回了 → 问题可能跟结构化数据有关 → 才值得花一次 LLM 调用去写 SQL。
                    // 这是"检索结果兼任是否启用昂贵工具的判据"，比额外加一个意图分类器更可靠也更省钱。
                    ContentRetriever dataTableProbe = KnowEngineElasticsearchContentRetriever.builder()
                            .configuration(ElasticsearchConfigurationKnn.builder().build())
                            .maxResults(TABLE_PROBE_MAX_RESULTS)
                            .minScore(0.0)
                            .embeddingModel(openAiEmbeddingModel)
                            .restClient(restClient)
                            .indexName(INDEX_NAME)
                            .knowledgeSegmentService(knowledgeSegmentService)
                            .filter(accessibleByFilter.and(metadataKey(CONTENT_TYPE)
                                    .isIn(List.of(TYPE_TABLE_SUMMARY, TYPE_TABLE_COLUMN))))
                            .build();

                    // 意图识别抽到 car_id / order_id 时，问题天然指向结构化数据，
                    // 无论有没有上传过表格都要放行 SQL（覆盖业务库 Text2SQL 场景）
                    boolean structuredEntityPresent =
                            KnowEngineGateQueryRouter.hasStructuredEntity(chatParam.intentRecognitionResult());
                    // 图闸门：判"有没有实体"，不是猜"该不该查图"
                    boolean graphGateOpen = !graphGateEnabled
                            || KnowEngineGateQueryRouter.hasGraphEntity(chatParam.intentRecognitionResult());

                    ProgressAwareContentRetriever sqlRetriever = null;
                    try {
                        // 拼接静态表结构 + table_meta 中动态创建的表结构
                        String databaseStructure = buildDatabaseStructure();
                        sqlRetriever = new ProgressAwareContentRetriever(
                                KnowEngineSqlDatabaseContentRetriever.builder()
                                        .dataSource(dataSource)
                                        .promptTemplate(new PromptTemplate(textToSqlPrompt.getContentAsString(UTF_8)))
                                        .databaseStructure(databaseStructure)
                                        .chatModel(chatModel)
                                        .fallbackRetriever(embeddingRetriever)
                                        .dataTableProbe(dataTableProbe)
                                        .structuredEntityPresent(structuredEntityPresent)
                                        .build(), processCallback);
                    } catch (IOException e) {
                        log.warn("Error creating SQL retriever", e);
                    }

                    ProgressAwareContentRetriever neo4jRetriever = null;
                    try {
                        neo4jRetriever = new ProgressAwareContentRetriever(
                                KnowEngineNeo4jContentRetriever.builder()
                                        .graph(Neo4jGraph.builder()
                                                .driver(neo4jDriver)
                                                .build())
                                        .chatModel(chatModel)
                                        .promptTemplate(new PromptTemplate(textToCypherPrompt.getContentAsString(UTF_8)))
                                        .fallbackRetriever(embeddingRetriever)
                                        .build(), processCallback);
                    } catch (IOException e) {
                        log.warn("Error creating Neo4j retriever", e);
                    }

                    // ========== 实体图检索路 ==========
                    // 与上面的 Text2Cypher 路是两回事：那条查人工维护的结构化业务图（品牌-车型-配件），
                    // 直接产出答案文本；这条查从文档正文自动抽出来的实体图（图谱抽取的产物），
                    // 产出的是"该看哪些 chunk"，因此走和向量结果完全一样的融合/精排管线。
                    ProgressAwareContentRetriever entityGraphRetriever = null;
                    if (graphProperties.isEnabled() && graphStore.isAvailable()) {
                        entityGraphRetriever = new ProgressAwareContentRetriever(
                                new KnowEngineEntityGraphContentRetriever(
                                        graphStore,
                                        knowledgeSegmentService,
                                        graphQueryEntityService,
                                        graphProperties.getKnowledgeBase(),
                                        graphProperties.getQueryRowLimit(),
                                        graphProperties.getQueryMaxChunks(),
                                        buildAllowedAccessibleBy(chatParam)),
                                processCallback, "正在沿知识图谱追溯关联片段...");
                    } else if (graphProperties.isEnabled()) {
                        log.info("图谱抽取已启用但 Neo4j 不可用，本次跳过实体图检索");
                    }

                    // 精排模型可选：未下载模型时为 null，聚合器会自动降级为按 RRF 融合分排序
                    OnnxScoringModel scoringModel = BgeScoringModel.getInstance();
                    if (scoringModel == null) {
                        log.warn("未加载 BGE 精排模型，本次检索按 RRF 融合分排序（链路其余环节不受影响）");
                    }

                    // 使用带进度通知的聚合器包装原始聚合器
                    // 混合聚合器：SQL/Cypher 结构化结果直接透传，仅对向量/全文检索结果做 RRF 融合和重排序
                    ContentAggregator contentAggregator = new ProgressAwareContentAggregator(
                            new KnowEngineHybridContentAggregator(
                                    KnowEngineReRankingContentAggregator.builder()
                                            .scoringModel(scoringModel)
                                            .minScore(0.6)
                                            .maxResults(5)
                                            // 加权 RRF：两路分数口径不同，只能按名次融合 + 按来源取权重。
                                            // 具体权重用评测集定，别照抄通用场景的 0.7/0.3
                                            .rrfK(rrfK)
                                            .weightsBySource(Map.of(
                                                    SOURCE_VECTOR, vectorWeight,
                                                    SOURCE_KEYWORD, keywordWeight))
                                            .defaultWeight(0.5)
                                            .querySelector(queryToContents -> queryToContents.keySet().iterator().next())
                                            .build()
                            ),
                            processCallback, chatParam.assistantMessageId(), chatMessageService
                    );

                    String prompt = promptService.getPrompt(chatParam.intentRecognitionResult());

                    ContentInjector contentInjector = new DefaultContentInjector();

                    // 构建查询路由器（带进度回调）
                    // gate（默认）：主干（向量 ∥ 关键词）无条件并行，图/SQL 各自挂闸门叠加，不做"多选一"
                    // router：回退到原来基于 LLM 的互斥路由，出问题时可一键切回
                    Collection<ContentRetriever> trunkRetrievers = nonNullList(embeddingRetriever, fullTextRetriever);
                    QueryRouter queryRouter;
                    if ("router".equalsIgnoreCase(retrievalMode)) {
                        log.info("检索模式：router（LLM 互斥路由）");
                        queryRouter = new KnowEngineQueryRouter(
                                nonNullList(embeddingRetriever, fullTextRetriever, sqlRetriever, neo4jRetriever,
                                        entityGraphRetriever),
                                chatModel, processCallback);
                    } else {
                        log.info("检索模式：gate（主干无条件并行 + 闸门叠加），graphGateOpen={}, structuredEntityPresent={}, entityGraph={}",
                                graphGateOpen, structuredEntityPresent, entityGraphRetriever != null);
                        queryRouter = new KnowEngineGateQueryRouter(
                                new ArrayList<>(trunkRetrievers), neo4jRetriever, entityGraphRetriever,
                                sqlRetriever, graphGateOpen, processCallback);
                    }

                    RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                            .queryRouter(queryRouter)
                            .queryTransformer(queryTransformer)
                            .contentAggregator(contentAggregator)
                            .contentInjector(contentInjector)
                            .build();

                    KnowEngineChatAiService knowEngineChatAiService = AiServices.builder(KnowEngineChatAiService.class)
//                            .chatModel(chatModel)
                            .streamingChatModel(ragChatModel)
                            .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                                    .id(memoryId)
                                    .maxMessages(10)
                                    .chatMemoryStore(databaseChatMemoryStore)
                                    .build())
                            .systemMessage(prompt)
                            .retrievalAugmentor(retrievalAugmentor)
                            .build();

                    // 订阅 LLM 流式输出，桥接到 sink
                    AtomicBoolean firstToken = new AtomicBoolean(true);
                    StringBuilder contentBuilder = new StringBuilder();
                    Disposable disposable = knowEngineChatAiService.streamChat(chatParam.conversationId(), chatParam.content())
                            .doOnNext(token -> {
                                // 首个 token 到达时，如果之前没有发出"正在生成回答"，则补发
                                // （正常情况下由 ProgressAwareContentAggregator 已发出，此处为兜底）
                                if (firstToken.compareAndSet(true, false)) {
                                    // 标记已开始接收 token
                                }
                                contentBuilder.append(token);
                            })
                            .doOnComplete(() -> chatMessageService.updateContent(chatParam.assistantMessageId(), contentBuilder.toString()))
                            .subscribe(sink::next, sink::error, sink::complete);

                    // 取消时同步取消内部订阅
                    sink.onCancel(disposable::dispose);
                })
                .subscribeOn(Schedulers.boundedElastic())
                // publishOn 引入异步边界：boundedElastic 线程专用于执行阻塞 RAG 操作，
                // parallel 线程独立运行 drain loop，确保进度消息能及时推送到前端 SSE 响应
                .publishOn(Schedulers.parallel());
    }

    /**
     * 构建数据库结构描述
     * <p>
     * 将静态表结构（retrieve_tables.sql）与 table_meta 表中动态创建的表结构合并，
     * 作为 Text2SQL Prompt 的 databaseStructure 参数，使 LLM 感知所有可查询的表。
     */
    private String buildDatabaseStructure() throws IOException {
        StringBuilder sb = new StringBuilder();
        // 静态表结构
        sb.append(tablesSql.getContentAsString(UTF_8));

        // 从 table_meta 读取当前激活版本对应的动态表结构
        List<TableMeta> tableMetas = knowEngineTableMetaService.listActiveForQuery();
        if (!CollectionUtils.isEmpty(tableMetas)) {
            sb.append("\n\n");
            String dynamicSql = tableMetas.stream()
                    .filter(meta -> meta.getCreateSql() != null && !meta.getCreateSql().isBlank())
                    .map(TableMeta::getCreateSql)
                    .collect(Collectors.joining("\n\n"));
            sb.append(dynamicSql);
        }
        return sb.toString();
    }

    @Autowired
    private UserRoleService userRoleService;

    /**
     * SQL 闸门探测时最多取几条表格类 chunk。
     * 探测只为回答"有没有"，取几条就够，不需要完整召回。
     */
    private static final int TABLE_PROBE_MAX_RESULTS = 3;

    /**
     * 过滤掉为 null 的检索器。
     * <p>
     * SQL / 图检索器在构造失败（如 prompt 文件读取异常）时会是 null，
     * 直接 {@code List.of(...)} 会抛 NPE，把整个检索链拖垮。
     */
    private static List<ContentRetriever> nonNullList(ContentRetriever... retrievers) {
        List<ContentRetriever> list = new ArrayList<>(retrievers.length);
        for (ContentRetriever retriever : retrievers) {
            if (retriever != null) {
                list.add(retriever);
            }
        }
        return list;
    }

    /**
     * 构造权限过滤器
     *
     * @param chatParam
     * @return
     */
    private Filter buildFilter(ChatParam chatParam) {
        // 默认权限过滤器：允许访客权限
        Filter permissionFilter = metadataKey(ACCESSIBLE_BY).isEqualTo(RoleEnum.VISITOR.name());

        // 根据用户角色获取权限
        RoleEnum roleEnum = userRoleService.getUserRole(chatParam);

        // 获取该文档支持的所有权限
        String[] permissions = DocumentPermissionUtils.getDocumentAccessiblePermission(roleEnum);

        for (String permission : permissions) {
            // 非访客权限时，将权限用or连接，表示支持多种权限
            if (!RoleEnum.VISITOR.name().equals(permission)) {
                permissionFilter = permissionFilter.or(metadataKey(ACCESSIBLE_BY).isEqualTo(permission));
            }
        }

        return permissionFilter;
    }

    /**
     * 当前用户角色允许的可见范围值列表，与 {@link #buildFilter} 同源。
     * <p>
     * 向量/关键词路把可见范围下推给 ES 做过滤；图路没有可下推的过滤条件
     * （命中是"实体名"，和权限无关），所以把同一个白名单交给它在内存里校验，
     * 保证两条路的可见性口径完全一致——否则会出现"向量路挡住了、图路却把无权正文捞出来"。
     */
    private List<String> buildAllowedAccessibleBy(ChatParam chatParam) {
        RoleEnum roleEnum = userRoleService.getUserRole(chatParam);
        return List.of(DocumentPermissionUtils.getDocumentAccessiblePermission(roleEnum));
    }

}
