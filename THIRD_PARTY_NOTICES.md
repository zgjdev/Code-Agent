# Third-Party Notices

CodeAgent distributes the following retrieval components in its shaded JAR. This file is a notice, not a replacement for the complete license texts shipped by the upstream artifacts.

| Component | Pinned version/artifact | License | Upstream |
|---|---|---|---|
| LangChain4j in-process embeddings | `langchain4j-embeddings-bge-small-zh-v15-q:1.18.0-beta28` and transitive LangChain4j modules | Apache License 2.0 | https://github.com/langchain4j/langchain4j |
| ONNX Runtime Java | `com.microsoft.onnxruntime:onnxruntime:1.20.0` | MIT License | https://github.com/microsoft/onnxruntime |
| BGE-small-zh-v1.5 model weights/tokenizer | quantized resources bundled by the LangChain4j artifact | MIT License | https://huggingface.co/BAAI/bge-small-zh-v1.5 |

The shaded JAR also includes existing project dependencies. Their `META-INF/LICENSE*`, `META-INF/NOTICE*`, POM metadata, and upstream notices remain authoritative. Maven Shade reports overlapping generic NOTICE/LICENSE resource names; release engineering must retain this consolidated notice and review the dependency tree before publishing a new version.

Remote embedding providers are not bundled software. Their APIs are disabled unless a user explicitly selects a provider and grants project-scoped consent. Operators remain responsible for the provider's then-current service terms, data-processing terms, region requirements, and commercial-use conditions.
