package com.mi.home.know.engine.graph.model;

/**
 * 模型"拒绝配合"：用散文回答而不是约定的 JSON。
 * <p>
 * 典型场景是目录页、版权页这类<b>本来就没有实体</b>的 chunk，模型会回一句
 * "这段文本没有可抽取的实体"而不是返回 {@code []}。
 * <p>
 * <b>这是一个合法结果，不是错误。</b>调用方必须正常结束该块任务、不重试——
 * 同样的输入重放没有意义，当成错误去重试只会白烧 LLM 额度。
 * 区分「失败」与「合法的空结果」是异步 LLM 流水线的基本功。
 */
public class ModelDeclinedException extends RuntimeException {

    public ModelDeclinedException(String message) {
        super(message);
    }
}
