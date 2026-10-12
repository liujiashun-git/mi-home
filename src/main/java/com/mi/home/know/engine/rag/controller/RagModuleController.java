package com.mi.home.know.engine.rag.controller;

import com.mi.home.know.engine.ai.model.IntentRecognitionResult;
import com.mi.home.know.engine.ai.service.CommonChatService;
import com.mi.home.know.engine.ai.service.IntentRecognitionService;
import com.mi.home.know.engine.ai.service.PromptService;
import com.mi.home.know.engine.chat.service.ChatMessageService;
import com.mi.home.know.engine.document.service.KnowledgeSegmentService;
import com.mi.home.know.engine.graph.config.GraphProperties;
import com.mi.home.know.engine.graph.model.GraphData;
import com.mi.home.know.engine.graph.model.GraphSearchResult;
import com.mi.home.know.engine.graph.service.GraphExtractionService;
import com.mi.home.know.engine.graph.service.GraphQueryEntityService;
import com.mi.home.know.engine.graph.store.GraphStore;
import com.mi.home.know.engine.rag.modules.KnowEngineElasticsearchContentRetriever;
import com.mi.home.know.engine.rag.modules.KnowEngineEntityGraphContentRetriever;
import com.mi.home.know.engine.rag.modules.KnowEngineNeo4jContentRetriever;
import com.mi.home.know.engine.rag.modules.KnowEngineQueryRouter;
import com.mi.home.know.engine.rag.modules.KnowEngineQueryTransformer;
import com.mi.home.know.engine.rag.modules.reranker.BgeScoringModel;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import dev.langchain4j.community.rag.content.retriever.neo4j.Neo4jGraph;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.experimental.rag.content.retriever.sql.SqlDatabaseContentRetriever;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.scoring.onnx.OnnxScoringModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.ReRankingContentAggregator;
import dev.langchain4j.rag.content.injector.ContentInjector;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.elasticsearch.ElasticsearchContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchConfigurationFullText;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchConfigurationKnn;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import org.elasticsearch.client.RestClient;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.mi.home.know.engine.rag.config.ElasticSearchConfiguration.INDEX_NAME;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.CHUNK_ID;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.DOC_ID;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * 用于ai的各个模块的功能测试
 */
@RestController
@RequestMapping("/ai/module")
public class RagModuleController {


    @Autowired
    private ChatModel chatModel;

    @Autowired
    private StreamingChatModel streamingChatModel;

    @Autowired
    private OpenAiEmbeddingModel openAiEmbeddingModel;

    @Autowired
    private RestClient restClient;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Driver neo4jDriver;

    @Autowired
    private KnowledgeSegmentService knowledgeSegmentService;

    @Autowired
    private PromptService promptService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private GraphExtractionService graphExtractionService;

    @Autowired
    private GraphQueryEntityService graphQueryEntityService;

    @Autowired
    private GraphStore graphStore;

    @Autowired
    private GraphProperties graphProperties;

    private ElasticsearchContentRetriever fullTextRetriever;

    private SqlDatabaseContentRetriever sqlRetriever;

    private KnowEngineNeo4jContentRetriever neo4jRetriever;

    private static final int MAX_RESULT = 5;

    private static final double MIN_SCORE = 0.5;

    @Value("classpath:prompts/text-to-cypher-prompt.txt")
    private Resource textToCypherPrompt;


    @PostConstruct
    public void init() throws IOException {

        this.fullTextRetriever = ElasticsearchContentRetriever.builder()
                .restClient(restClient)
                .configuration(ElasticsearchConfigurationFullText.builder().build())
                .maxResults(MAX_RESULT)
                .indexName(INDEX_NAME)
                .minScore(MIN_SCORE)
                .build();

        this.sqlRetriever = SqlDatabaseContentRetriever.builder().dataSource(dataSource)
                .promptTemplate(new PromptTemplate("textToSqlPrompt.getContentAsString(UTF_8)"))
                .databaseStructure("tablesSql.getContentAsString(UTF_8)")
                .chatModel(chatModel)
                .build();

        this.neo4jRetriever = KnowEngineNeo4jContentRetriever.builder()
                .graph(Neo4jGraph.builder()
                        .driver(neo4jDriver)
                        .build())
                .chatModel(chatModel)
                .promptTemplate(new PromptTemplate(textToCypherPrompt.getContentAsString(UTF_8)))
                .fallbackRetriever(fullTextRetriever)
                .build();
    }

    @GetMapping("/router")
    public String testRouter(String query) {

        KnowEngineElasticsearchContentRetriever embeddingRetriever = KnowEngineElasticsearchContentRetriever.builder()
                .restClient(restClient)
                .embeddingModel(openAiEmbeddingModel)
                .configuration(ElasticsearchConfigurationKnn.builder().build())
                .maxResults(MAX_RESULT)
                .indexName(INDEX_NAME)
                .minScore(MIN_SCORE)
                .knowledgeSegmentService(knowledgeSegmentService)
                .build();

        KnowEngineQueryRouter knowEngineQueryRouter = new KnowEngineQueryRouter(List.of(embeddingRetriever, fullTextRetriever, sqlRetriever, neo4jRetriever), chatModel);
        Collection<ContentRetriever> contentRetrievers = knowEngineQueryRouter.route(new Query(query));
        return contentRetrievers.toString();
    }


    @GetMapping("testTransformer")
    public String testTransformer(String content) {
        String messageId = chatMessageService.saveUserMessage(UUID.randomUUID().toString(), content);
        KnowEngineQueryTransformer knowEngineQueryTransformer = new KnowEngineQueryTransformer(chatModel, messageId);
        Collection<Query> queries = knowEngineQueryTransformer.transform(new Query(content));
        System.out.println(queries);
        return JSON.toJSONString(queries);
    }

    @GetMapping("testRetriever")
    public Flux<String> testRetriever(String query, String chatMessageId, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");

//        ElasticsearchConfiguration configuration = ElasticsearchConfigurationHybrid.builder().build();

//        ElasticsearchContentRetriever contentRetriever = ElasticsearchContentRetriever.builder()
//                .restClient(restClient)
//                .embeddingModel(openAiEmbeddingModel)
//                .configuration(configuration)
//                .maxResults(5)
//                .indexName("know-engine")
//                .minScore(0.5)
//                .build();

        ElasticsearchContentRetriever fullTextRetriever = ElasticsearchContentRetriever.builder()
                .restClient(restClient)
                .configuration(ElasticsearchConfigurationFullText.builder().build())
                .maxResults(MAX_RESULT)
                .indexName(INDEX_NAME)
                .minScore(MIN_SCORE)
                .build();

        KnowEngineElasticsearchContentRetriever embeddingRetriever = KnowEngineElasticsearchContentRetriever
                .builder()
                .restClient(restClient)
                .embeddingModel(openAiEmbeddingModel)
                .configuration(ElasticsearchConfigurationKnn.builder().build())
                .maxResults(MAX_RESULT)
                .indexName(INDEX_NAME)
                .minScore(MIN_SCORE)
                .knowledgeSegmentService(knowledgeSegmentService)
                .build();


        KnowEngineQueryTransformer queryTransformer = new KnowEngineQueryTransformer(chatModel, chatMessageId);

        // 创建检索增强器
        RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                .queryRouter(new DefaultQueryRouter(embeddingRetriever, fullTextRetriever))
                .queryTransformer(queryTransformer)
                .build();

        CommonChatService aiService = AiServices.builder(CommonChatService.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
                .retrievalAugmentor(retrievalAugmentor)
                .build();

        Flux<String> result = aiService.streamChat(UUID.randomUUID().toString(), query);

        return result;

    }

    @GetMapping("testPromptRouter")
    public String testPromptRouter(String query) {
        IntentRecognitionResult intentRecognitionResult = AiServices.builder(IntentRecognitionService.class).chatModel(chatModel).build().chat(UUID.randomUUID().toString(), query);
        return promptService.getPrompt(intentRecognitionResult);
    }

    @GetMapping("testPromptRouter1")
    public String testPromptRouter1(String query) {


        IntentRecognitionResult intentRecognitionResult = AiServices.builder(IntentRecognitionService.class).chatModel(chatModel).build().chat(UUID.randomUUID().toString(), query);
        String prompt = promptService.getPrompt(intentRecognitionResult);
        ContentInjector contentInjector = new DefaultContentInjector(PromptTemplate.from(prompt));

        List<Content> testContents = List.of(
                Content.from(TextSegment.from("Java是一种面向对象的编程语言，具有跨平台、安全性高等特点，广泛应用于企业级开发。")),
                Content.from(TextSegment.from("Python是一种解释型的高级编程语言，以简洁易读的语法著称，常用于数据科学和人工智能领域。")),
                Content.from(TextSegment.from("JavaScript是一种脚本语言，主要用于Web前端开发，也可以通过Node.js进行服务端编程。")),
                Content.from(TextSegment.from("Java虚拟机（JVM）是运行Java字节码的虚拟机，它使得Java具有跨平台能力。Spring是最流行的Java开发框架。")),
                Content.from(TextSegment.from("Go语言由Google开发，以高并发和简洁语法为特色，常用于微服务和云原生开发。"))
        );
        System.out.println(JSON.toJSONString(contentInjector.inject(testContents, new UserMessage(query))));
        return ((UserMessage) contentInjector.inject(testContents, new UserMessage(query))).singleText();
    }

    // ==================== 图谱抽取验收接口 ====================
    // 抽取是"每块两次 LLM 调用"的链路，出了问题必须能一段一段单独验，
    // 而不是扔一篇文档进去从日志里猜。下面三个接口按验收顺序排：
    // 抽取产物 → 查询侧实体 → 图检索命中，对应实施指南里的第 2/3 步验收。

    /**
     * 验收 1：直接看模型从一段文本里抽出了什么实体和关系（不落库）。
     * <p>
     * 例：{@code /ai/module/testGraphExtract?text=小米SU7 Max搭载宁德时代三元锂电池}
     */
    @GetMapping("testGraphExtract")
    public String testGraphExtract(String text) {
        if (text == null || text.isBlank()) {
            return "请传入 text 参数，例如 ?text=小米SU7 Max搭载宁德时代提供的三元锂电池";
        }
        GraphData graph = graphExtractionService.extractText(text);
        JSONObject result = new JSONObject();
        result.put("entities", graph.nodes());
        result.put("relations", graph.relations());
        result.put("entityCount", graph.nodes().size());
        result.put("relationCount", graph.relations().size());
        return JSON.toJSONString(result);
    }

    /**
     * 验收 2：看查询侧实体抽取（这一步用的是改写后的 query，直接传改写结果即可）。
     * <p>
     * 返回两部分：实际送进模型的 prompt，以及抽出来的实体名——
     * 抽不出实体时先看 prompt，再看模型输出，别一上来就怀疑图库。
     */
    @GetMapping("testGraphQueryEntity")
    public String testGraphQueryEntity(String query) {
        if (query == null || query.isBlank()) {
            return "请传入 query 参数，例如 ?query=小米SU7 Max用的是哪家的电池";
        }
        JSONObject result = new JSONObject();
        result.put("prompt", graphQueryEntityService.buildPrompt(query));
        result.put("entities", graphQueryEntityService.extract(query));
        return JSON.toJSONString(result);
    }

    /**
     * 验收 3：实体名 → 图节点 → chunk ID（"图当路由器"的完整前半程）。
     * <p>
     * 只做到 chunk ID，不取正文——正好验证"图的产出是 chunk，不是内容"这个不变量；
     * 想连正文一起看就走 {@code /ai/module/testGraphChunks}。
     */
    @GetMapping("testGraphSearchNodes")
    public String testGraphSearchNodes(String query) {
        if (query == null || query.isBlank()) {
            return "请传入 query 参数";
        }
        List<String> entities = graphQueryEntityService.extract(query);
        GraphSearchResult graphResult = graphStore.searchNodes(
                graphProperties.getKnowledgeBase(), entities, graphProperties.getQueryRowLimit());
        JSONObject result = new JSONObject();
        result.put("entities", entities);
        result.put("nodes", graphResult.nodes());
        result.put("relations", graphResult.relations());
        result.put("chunkIds", graphResult.chunkIds());
        return JSON.toJSONString(result);
    }

    /**
     * 验收 4：完整图路——实体 → 图节点 → chunk ID → 正文。
     * <p>
     * 这条链路能跑通，一个多跳关系问题就能通过图路召回正确 chunk。
     */
    @GetMapping("testGraphChunks")
    public String testGraphChunks(String query, String accessibleBy) {
        if (query == null || query.isBlank()) {
            return "请传入 query 参数；accessibleBy 可选（不传则不限制可见范围）";
        }
        List<String> allowed = (accessibleBy == null || accessibleBy.isBlank())
                ? List.of()
                : Arrays.stream(accessibleBy.split(",")).map(String::trim).toList();
        KnowEngineEntityGraphContentRetriever retriever = new KnowEngineEntityGraphContentRetriever(
                graphStore,
                knowledgeSegmentService,
                graphQueryEntityService,
                graphProperties.getKnowledgeBase(),
                graphProperties.getQueryRowLimit(),
                graphProperties.getQueryMaxChunks(),
                allowed);
        List<Content> contents = retriever.retrieve(new Query(query));
        return contents.stream()
                .map(content -> String.format("[chunkId=%s] [docId=%s] %s",
                        content.textSegment().metadata().getString(CHUNK_ID),
                        content.textSegment().metadata().toMap().get(DOC_ID),
                        content.textSegment().text()))
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    @GetMapping("testReranker")
    public String testReranker(String query) {
        if (query == null || query.isBlank()) {
            query = "什么是Java？";
        }

        // 1. 获取 BGE-RERANKER 单例（模型可选，未下载时为 null）
        OnnxScoringModel scoringModel = BgeScoringModel.getInstance();
        if (scoringModel == null) {
            return "BGE 精排模型未加载：src/main/resources/model/bge-reranker-model/ 下缺少 "
                    + "model_quantized.onnx 与 tokenizer.json，该测试接口不可用。";
        }

        // 2. 构造测试文档，模拟检索结果
        List<Content> testContents = List.of(
                Content.from(TextSegment.from("Java是一种面向对象的编程语言，具有跨平台、安全性高等特点，广泛应用于企业级开发。")),
                Content.from(TextSegment.from("Python是一种解释型的高级编程语言，以简洁易读的语法著称，常用于数据科学和人工智能领域。")),
                Content.from(TextSegment.from("JavaScript是一种脚本语言，主要用于Web前端开发，也可以通过Node.js进行服务端编程。")),
                Content.from(TextSegment.from("Java虚拟机（JVM）是运行Java字节码的虚拟机，它使得Java具有跨平台能力。Spring是最流行的Java开发框架。")),
                Content.from(TextSegment.from("Go语言由Google开发，以高并发和简洁语法为特色，常用于微服务和云原生开发。"))
        );

        // 3. 构建 ReRankingContentAggregator
        ContentAggregator aggregator = ReRankingContentAggregator.builder()
                .scoringModel(scoringModel)
                .build();

        // 4. 直接调用 ContentAggregator 进行重排序
        Query queryObj = new Query(query);
        List<Content> rerankedContents = aggregator.aggregate(Map.of(queryObj, List.of(testContents)));

        // 5. 格式化输出结果
        return rerankedContents.stream()
                .map(content -> {
                    TextSegment segment = content.textSegment();
                    Double rerankedScore = (Double) content.metadata().get(ContentMetadata.RERANKED_SCORE);
                    return String.format("[rerankedScore=%.4f] %s",
                            rerankedScore != null ? rerankedScore : 0.0,
                            segment.text());
                })
                .collect(Collectors.joining("\n\n---\n\n"));
    }
}
