package com.globalpulse.news.ai.pool;

/**
 * LlmProvider — Strategy Pattern.
 * Interface agnóstica para qualquer provedor de LLM.
 * Groq, OpenAI, Ollama, etc. — todos implementam esta interface.
 */
public interface LlmProvider {

    /** Nome do provedor (ex: "groq", "openai", "ollama") */
    String providerName();

    /** Executa uma chamada e retorna o texto gerado */
    String complete(String apiKey, String systemPrompt, String userPrompt, int maxTokens) throws Exception;

    /** Estima tokens (aproximado: chars / 4) */
    default int estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }
}
