package com.mi.home.know.engine.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步配置类
 * 启用异步事件处理和调度功能，配置自定义线程池
 */
@Slf4j
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /**
     * 事件监听专用线程池
     * 用于处理文档状态变更事件的异步监听
     */
    @Bean("eventListenerExecutor")
    public Executor eventListenerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // 核心线程数
        executor.setCorePoolSize(4);
        
        // 最大线程数
        executor.setMaxPoolSize(8);
        
        // 队列容量
        executor.setQueueCapacity(50);
        
        // 线程名前缀
        executor.setThreadNamePrefix("event-listener-");
        
        // 线程空闲时间
        executor.setKeepAliveSeconds(60);
        
        // 拒绝策略
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        // 优雅关闭
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        
        executor.initialize();

        log.info("事件监听线程池初始化完成");

        return executor;
    }

    /**
     * 后处理专用线程池（文档摘要 / 问题生成）。
     * <p>
     * 这类任务按批调用 LLM，单批可达数十秒、一批文档可持续数分钟。
     * 与事件监听池隔离：后处理跑满也不阻塞文档向量化事件的响应。
     */
    @Bean("postProcessExecutor")
    public Executor postProcessExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("post-process-");
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();

        log.info("后处理线程池初始化完成");

        return executor;
    }

    /**
     * 图谱抽取——文档级任务池。
     * <p>
     * 一个文档一个任务：读目标块 → fan-out 到 {@link #graphChunkExecutor} 逐块抽取 → 汇总。
     * <p>
     * <b>为什么块级任务要另起一个池</b>：文档级任务会<b>阻塞等待</b>自己的块级子任务完成。
     * 如果父子共用同一个池，池子被文档级任务占满后子任务排不进来，
     * 就形成"父等子、子等父"的自死锁。两个池把等待关系和执行关系分开，
     * 结构上杜绝这类问题（子任务还会在队列满时由父线程直接跑，保证一定有进展）。
     */
    @Bean("graphExtractionExecutor")
    public Executor graphExtractionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("graph-extract-");
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();

        log.info("图谱抽取线程池初始化完成");

        return executor;
    }

    /**
     * 图谱抽取——块级任务池。
     * <p>
     * 一个 chunk 一个任务。抽取是 per-chunk 的 LLM 调用，纯 IO 等待、
     * 块与块之间没有任何依赖，所以并发度直接换吞吐：
     * 线程数按"并发 LLM 调用数"设，不是按 CPU 核数设。
     * <p>
     * 队列满时用 CallerRunsPolicy：让调用方线程（文档级任务）自己跑这块，
     * 宁可这一篇文档慢一点，也不丢块、不无限堆积。
     */
    @Bean("graphChunkExecutor")
    public Executor graphChunkExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("graph-chunk-");
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();

        log.info("图谱块级抽取线程池初始化完成");

        return executor;
    }
}
