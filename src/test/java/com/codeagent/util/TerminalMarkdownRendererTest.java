package com.codeagent.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalMarkdownRendererTest {

    @Test
    void rendersHeadingListTableAndCodeBlockToTerminalFriendlyText() {
        String markdown = """
                # 规划思考
                                
                1. **分析请求**
                - 列出当前目录
                                
                | 名称 | 说明 |
                | --- | --- |
                | src | 源码 |
                | pom.xml | Maven 配置 |
                                
                ```java
                System.out.println("hello");
                ```
                """;

        String rendered = TerminalMarkdownRenderer.render(markdown);

        assertTrue(rendered.contains("规划思考"));
        assertTrue(rendered.contains("1. 分析请求"));
        assertTrue(rendered.contains("- 列出当前目录"));
        assertTrue(rendered.contains("| 名称"));
        assertTrue(rendered.contains("| src"));
        assertTrue(rendered.contains("源码"));
        assertTrue(rendered.contains("┌─ code: java"));
        assertTrue(rendered.contains("└─ end"));
        assertTrue(rendered.contains("    System.out.println(\"hello\");"));
    }

    @Test
    void supportsIncrementalStreamingAppend() {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        java.io.PrintStream stream = new java.io.PrintStream(output);
        TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(stream);

        renderer.append("## 标题\n- 第一");
        renderer.append("项\n- 第二项\n");
        renderer.finish();

        String rendered = output.toString();
        assertTrue(rendered.contains("标题"));
        assertTrue(rendered.contains("- 第一项"));
        assertTrue(rendered.contains("- 第二项"));
    }

    @Test
    void preservesNestedListIndentation() {
        String markdown = """
                1. 总体分析
                  - 第一层补充
                    - 第二层补充
                """;

        String rendered = TerminalMarkdownRenderer.render(markdown);

        assertTrue(rendered.contains("1. 总体分析"));
        assertTrue(rendered.contains("  - 第一层补充"));
        assertTrue(rendered.contains("    - 第二层补充"));
    }

    @Test
    void fallsBackToKeyValueLayoutForLongTwoColumnTable() {
        String markdown = """
                | 目录名 | 说明 |
                | --- | --- |
                | src/main/java/com/codeagent | 这里存放 CodeAgent 的主要 Java 源码实现与相关模块 |
                """;

        String rendered = TerminalMarkdownRenderer.render(markdown);

        assertTrue(rendered.contains("目录名 / 说明"));
        assertTrue(rendered.contains("- src/main/java/com/codeagent"));
        assertTrue(rendered.contains("这里存放 CodeAgent 的主要 Java 源码实现与相关模块"));
    }

    @Test
    void wrapsWideMultiColumnTableInsideTerminalWidth() {
        String markdown = """
                | 特性 | StepFun (Step) | Kimi | GLM | DeepSeek |
                | --- | --- | --- | --- | --- |
                | 基础 URL | https://api.stepfun.com/v1 | https://api.moonshot.ai/v1 | 动态选择（glm-5v用多模态API，其他用编码API） | https://api.deepseek.com/chat/completions |
                | 推理能力 | ✅（需配置 reasoningformat="deepseek-style"） | ✅（需发送推理历史） | ✅ | ✅ |
                """;

        String rendered = TerminalMarkdownRenderer.render(markdown, 72);

        assertTrue(rendered.contains("| 特性"));
        assertFalse(rendered.contains("https://api.deepseek.com/chat/completions |"));
        for (String line : rendered.split("\\R")) {
            assertTrue(displayWidth(stripAnsi(line)) <= 72,
                    "line exceeds table width: " + stripAnsi(line));
        }
    }

    private static String stripAnsi(String value) {
        return value.replaceAll("\\u001B\\[[;\\d]*m", "");
    }

    private static int displayWidth(String value) {
        int width = 0;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            width += switch (script) {
                case HAN, HIRAGANA, KATAKANA, HANGUL -> 2;
                default -> isWideSymbol(cp) ? 2 : 1;
            };
            offset += Character.charCount(cp);
        }
        return width;
    }

    private static boolean isWideSymbol(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)
                || (cp >= 0x2329 && cp <= 0x232A)
                || (cp >= 0x2E80 && cp <= 0xA4CF)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE10 && cp <= 0xFE19)
                || (cp >= 0xFE30 && cp <= 0xFE6F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x1F000 && cp <= 0x1FAFF);
    }
}
