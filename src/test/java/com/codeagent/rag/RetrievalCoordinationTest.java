package com.codeagent.rag;

import com.codeagent.rag.embedding.EmbeddingResolution;
import com.codeagent.rag.stage.TermFtsRetriever;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalCoordinationTest {
    @TempDir Path temp;
    @Test void mixedQueryAndExplicitHintAreSoftLexicalInputs() throws Exception {
        Path root=Files.createDirectories(temp.resolve("project"));
        Files.writeString(root.resolve("Store.java"),"class Store { void save() { String value = \"保存\"; } }");
        Files.writeString(root.resolve("Recovery.java"),"class Recovery { void recover() { String value = \"恢复事务\"; } }");
        try(var index=new SqliteRetrievalIndex(temp.resolve("index.db"));
            var service=new DefaultCodeRetrievalService(index,new EmbeddingResolution(Optional.empty(),"off",false))) {
            service.refresh(new IndexRefreshRequest(root,false));
            var request=new RetrievalRequest(root,"为什么恢复事务失败",10,16000,false,RetrievalIntent.CHUNKS,"store.save()");
            var hits=new TermFtsRetriever().retrieve(new RetrievalContext(request,index,Optional.empty()));
            assertTrue(hits.stream().anyMatch(h->h.filePath().equals("Store.java")));
            assertTrue(hits.stream().anyMatch(h->h.filePath().equals("Recovery.java")),"hint must not hard-filter related files");
            assertEquals(hits.size(),hits.stream().map(RetrievalCandidate::chunkId).distinct().count());
            assertTrue(hits.size()<=40);
            var mixed=new TermFtsRetriever().retrieve(new RetrievalContext(new RetrievalRequest(root,"看一下store.save()这个方法",10,16000,false,RetrievalIntent.CHUNKS),index,Optional.empty()));
            assertFalse(mixed.isEmpty());
            assertEquals("Store.java",mixed.get(0).filePath());
        }
    }
    @Test void freshnessDetectsSameSizeAndTimestampChangeDeletionAndRefresh() throws Exception {
        Path root=Files.createDirectories(temp.resolve("project")); Path file=root.resolve("Store.java");
        String source="class Store { void save() { int value = 1; } }";
        Files.writeString(file,source);
        try(var service=new DefaultCodeRetrievalService(new SqliteRetrievalIndex(temp.resolve("state.db")),new EmbeddingResolution(Optional.empty(),"off",false))) {
            var request=new RetrievalRequest(root,"save",10,16000,false,RetrievalIntent.CHUNKS);
            service.refresh(new IndexRefreshRequest(root,false));
            assertEquals("verified",service.search(request).diagnostics().fileFreshness().get("Store.java"));
            var time=Files.getLastModifiedTime(file); Files.writeString(file,source.replace("1","2")); Files.setLastModifiedTime(file,time);
            var changed=service.search(request);
            assertEquals("changed",changed.diagnostics().fileFreshness().get("Store.java")); assertTrue(changed.partial());
            assertTrue(SearchResultFormatter.formatForTool("save",changed).contains("read_file"));
            service.refresh(new IndexRefreshRequest(root,false));
            assertEquals("verified",service.search(request).diagnostics().fileFreshness().get("Store.java"));
            Files.delete(file);
            assertEquals("missing",service.search(request).diagnostics().fileFreshness().get("Store.java"));
        }
    }
    @Test void emptyIndexHasActionableDiagnosticEvenWithoutResults() throws Exception {
        Path root=Files.createDirectories(temp.resolve("empty"));
        var provider=org.mockito.Mockito.mock(com.codeagent.rag.embedding.EmbeddingProvider.class);
        try(var service=new DefaultCodeRetrievalService(new SqliteRetrievalIndex(temp.resolve("empty.db")),new EmbeddingResolution(Optional.of(provider),"local",false))) {
            var response=service.search(new RetrievalRequest(root,"恢复任务",10,16000,false,RetrievalIntent.CHUNKS));
            assertTrue(response.hits().isEmpty());
            assertTrue(response.diagnostics().degradedReasonCodes().contains("index_empty"));
            assertTrue(SearchResultFormatter.formatForTool("恢复任务",response).contains("index_empty"));
            org.mockito.Mockito.verify(provider,org.mockito.Mockito.never()).embedAll(org.mockito.ArgumentMatchers.anyList());
        }
    }

    @Test void checkerRejectsTraversalAndLinksOutsideProject() throws Exception {
        Path root=Files.createDirectories(temp.resolve("safe"));
        Path outside=temp.resolve("secret.java"); Files.writeString(outside,"private data");
        try(var index=new SqliteRetrievalIndex(temp.resolve("safe.db"))) {
            var hit=new RetrievalHit("../secret.java",1,1,"file","secret","old",1,Set.of(RetrievalSource.FTS_TERMS));
            assertEquals("unavailable",new RetrievalFreshnessChecker().check(root,index,List.of(hit)).get("../secret.java"));
            try {
                Files.createSymbolicLink(root.resolve("link.java"),outside);
                var link=new RetrievalHit("link.java",1,1,"file","link","old",1,Set.of(RetrievalSource.FTS_TERMS));
                assertEquals("unavailable",new RetrievalFreshnessChecker().check(root,index,List.of(link)).get("link.java"));
            } catch (java.nio.file.FileSystemException | UnsupportedOperationException unavailable) {
                // Windows without symlink privilege still executes the traversal boundary above.
            }
        }
    }
}
