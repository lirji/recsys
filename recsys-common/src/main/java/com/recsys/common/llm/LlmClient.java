package com.recsys.common.llm;

/**
 * 生成式 LLM 客户端契约(单轮文本生成)。与 {@link com.recsys.common.embedding.EmbeddingClient}
 * 同属「外部模型客户端」家族:接口在 common,实现在具体模块(当前 recsys-embedding 的
 * {@code GeminiChatClient}),消费方通过 {@code ObjectProvider<LlmClient>} 可选注入,
 * **缺失/未就绪/调用失败一律降级**(消费方各自兜底,不让链路崩溃)。
 *
 * <p>当前唯一消费方:Query 理解层(拼写纠错 / 意图分类 / query 改写)。后续可复用于
 * 推荐理由生成、对话式推荐等(见 docs/04 §11、docs/05)。
 */
public interface LlmClient {

    /**
     * 单轮生成。给定系统提示(角色/约束)与用户提示(具体输入),返回模型文本输出。
     *
     * @param systemPrompt 系统提示,可为 null
     * @param userPrompt   用户提示
     * @return 模型输出文本;实现可在不可用时抛异常(调用方应 catch 并降级)
     */
    String complete(String systemPrompt, String userPrompt);

    /** 是否就绪(已配置 key / 模型可用)。未就绪时消费方应跳过 LLM 步骤走兜底逻辑。 */
    boolean isReady();

    /** 模型名(用于缓存 key / 日志)。 */
    String modelName();
}
