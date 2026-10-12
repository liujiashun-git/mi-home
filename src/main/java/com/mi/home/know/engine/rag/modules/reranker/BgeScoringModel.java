package com.mi.home.know.engine.rag.modules.reranker;


import dev.langchain4j.model.scoring.onnx.OnnxScoringModel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * BGE-RERANKER 评分模型的单例封装
 * <p>
 * 基于 OnnxScoringModel，在 Java 进程内通过 ONNX Runtime 本地运行 BGE-RERANKER 模型，
 * 用于 RAG 流程中的文档重排序（Reranking）。
 * <p>
 * <b>可选加载（重要）：</b>
 * 精排是"可选增强"而不是"必需依赖"。模型文件缺失时这里只记录警告并让 {@link #getInstance()} 返回
 * {@code null}，由 {@code KnowEngineReRankingContentAggregator} 自动降级为按 RRF 融合分排序。
 * 这样做的原因：
 * <ul>
 *   <li>模型是 100MB+ 的二进制文件，不适合进 Git 仓库，别人 clone 下来必然没有；</li>
 *   <li>如果启动阶段直接抛异常，整个应用起不来，链路一步都走不了 —— 一个可选组件不该有这种权力；</li>
 *   <li>降级路径本身也是设计的一部分：精排失败时退回粗排顺序，保证"失败不空手"。</li>
 * </ul>
 * <p>
 * 使用方式：
 * <pre>
 * OnnxScoringModel scoringModel = BgeScoringModel.getInstance();   // 可能为 null
 * if (scoringModel != null) {
 *     ContentAggregator aggregator = ReRankingContentAggregator.builder()
 *         .scoringModel(scoringModel)
 *         .minScore(0.5)
 *         .build();
 * }
 * </pre>
 * <p>
 * 启用精排的步骤：
 * <ol>
 *   <li>从 HuggingFace 下载 ONNX 格式的 BGE-RERANKER 模型，
 *       推荐 {@code onnx-community/bge-reranker-v2-m3-ONNX}（中文场景效果好）</li>
 *   <li>需要 {@code model_quantized.onnx} 和 {@code tokenizer.json} 两个文件</li>
 *   <li>放到 {@code src/main/resources/model/bge-reranker-model/} 下</li>
 * </ol>
 */
@Slf4j
@Component
public class BgeScoringModel {

    /**
     * classpath 下的模型文件路径
     * 需要先下载对应的文件，放到 resources 目录下
     */
    private static final String CLASSPATH_MODEL = "model/bge-reranker-model/model_quantized.onnx";
    private static final String CLASSPATH_TOKENIZER = "model/bge-reranker-model/tokenizer.json";

    /**
     * 单例实例，使用 volatile 保证多线程可见性。
     * 模型文件缺失时保持为 null（不是错误状态，是"未启用精排"）。
     */
    private static volatile OnnxScoringModel instance;

    /**
     * 是否已经尝试过加载。
     * 避免模型文件确实不存在时，每次调用都去 classpath 里找一遍。
     */
    private static volatile boolean loadAttempted = false;

    /**
     * Spring 容器启动时预加载模型，避免首次问答时触发模型加载导致延迟。
     * <p>
     * 注意：加载失败不抛异常，只打日志 —— 精排不可用不应该阻塞整个应用启动。
     */
    @PostConstruct
    public void init() {
        loadModel();
    }

    /**
     * 获取 OnnxScoringModel 单例实例
     *
     * @return 模型实例；<b>模型文件不存在时返回 {@code null}</b>，调用方必须判空
     */
    public static OnnxScoringModel getInstance() {
        if (instance == null && !loadAttempted) {
            synchronized (BgeScoringModel.class) {
                if (instance == null && !loadAttempted) {
                    loadModel();
                }
            }
        }
        return instance;
    }

    /**
     * 精排模型是否可用
     */
    public static boolean isAvailable() {
        return getInstance() != null;
    }

    /**
     * 加载 ONNX 模型与 tokenizer，初始化 OnnxScoringModel 实例。
     * <p>
     * 任何失败都只记录警告，不向上抛异常。
     */
    private static synchronized void loadModel() {
        if (instance != null || loadAttempted) {
            return;
        }
        loadAttempted = true;

        try {
            String modelPath = resolveClasspathToFilePath(CLASSPATH_MODEL);
            String tokenizerPath = resolveClasspathToFilePath(CLASSPATH_TOKENIZER);

            log.info("正在初始化 BGE-RERANKER 评分模型...");
            log.info("模型路径: {}", modelPath);
            log.info("Tokenizer路径: {}", tokenizerPath);

            instance = new OnnxScoringModel(modelPath, tokenizerPath, 8192);

            log.info("BGE-RERANKER 评分模型初始化完成，精排已启用");
        } catch (Exception e) {
            instance = null;
            log.warn("BGE-RERANKER 模型不可用，精排环节将自动降级为按 RRF 融合分排序；" +
                    "检索链路的其余环节不受影响。原因: {}", e.getMessage());
            log.warn("如需启用精排，请下载 bge-reranker-v2-m3 的 ONNX 模型，" +
                    "把 model_quantized.onnx 和 tokenizer.json 放到 src/main/resources/model/bge-reranker-model/ 下");
        }
    }

    /**
     * 将 classpath 资源解析为文件绝对路径
     * <p>
     * 优先尝试直接获取文件路径（IDE 或解压目录下的资源），
     * 如果资源在 JAR 包内则复制到临时文件后返回临时文件路径。
     *
     * @param classpathResource classpath 下的资源名称
     * @return 资源的绝对文件路径
     * @throws IllegalArgumentException 资源不存在（由调用方 {@link #loadModel()} 捕获并降级）
     */
    private static String resolveClasspathToFilePath(String classpathResource) {
        URL resource = BgeScoringModel.class.getClassLoader().getResource(classpathResource);
        if (resource == null) {
            throw new IllegalArgumentException("classpath 下未找到资源: " + classpathResource);
        }

        // 尝试直接获取文件路径（适用于 IDE 运行或解压目录）
        try {
            File file = new File(resource.toURI());
            if (file.exists()) {
                return file.getAbsolutePath();
            }
        } catch (Exception e) {
            // 资源在 JAR 包内，无法直接转 File，走下方临时文件逻辑
            log.debug("资源在 JAR 包内，将复制到临时文件: {}", classpathResource);
        }

        // 资源在 JAR 包内，复制到临时文件
        try (InputStream is = BgeScoringModel.class.getClassLoader().getResourceAsStream(classpathResource)) {
            Path tempFile = Files.createTempFile("bge-reranker-", "." + classpathResource.substring(classpathResource.lastIndexOf('.') + 1));
            tempFile.toFile().deleteOnExit();
            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("已将 classpath 资源复制到临时文件: {}", tempFile.toAbsolutePath());
            return tempFile.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new RuntimeException("无法从 classpath 复制资源到临时文件: " + classpathResource, e);
        }
    }
}
