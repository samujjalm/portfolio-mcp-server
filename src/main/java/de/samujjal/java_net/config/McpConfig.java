package de.samujjal.java_net.config;

import de.samujjal.java_net.portfolio.PortfolioTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the portfolio tools with the MCP server. The Spring AI MCP server
 * auto-configuration discovers {@link ToolCallbackProvider} beans and exposes
 * their methods as MCP tools over the configured (SSE) transport.
 */
@Configuration
public class McpConfig {

    @Bean
    public ToolCallbackProvider portfolioToolCallbacks(PortfolioTools portfolioTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(portfolioTools)
                .build();
    }
}
