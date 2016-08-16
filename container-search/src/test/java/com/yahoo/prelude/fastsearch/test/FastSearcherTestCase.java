// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.prelude.fastsearch.test;

import com.yahoo.component.chain.Chain;
import com.yahoo.config.subscription.ConfigGetter;
import com.yahoo.container.search.Fs4Config;
import com.yahoo.fs4.mplex.*;
import com.yahoo.fs4.test.QueryTestCase;
import com.yahoo.language.Linguistics;
import com.yahoo.language.simple.SimpleLinguistics;
import com.yahoo.prelude.Ping;
import com.yahoo.prelude.Pong;
import com.yahoo.prelude.fastsearch.DocumentdbInfoConfig;
import com.yahoo.container.protect.Error;
import com.yahoo.document.GlobalId;
import com.yahoo.fs4.*;
import com.yahoo.processing.execution.Execution.Trace;
import com.yahoo.search.Query;
import com.yahoo.search.Result;
import com.yahoo.search.Searcher;
import com.yahoo.prelude.fastsearch.*;
import com.yahoo.prelude.query.WordItem;
import com.yahoo.search.dispatch.Dispatcher;
import com.yahoo.search.rendering.RendererRegistry;
import com.yahoo.search.result.ErrorMessage;
import com.yahoo.search.searchchain.Execution;
import com.yahoo.vespa.config.search.DispatchConfig;
import com.yahoo.yolean.trace.TraceNode;
import com.yahoo.yolean.trace.TraceVisitor;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.*;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Tests the Fast searcher
 *
 * @author bratseth
 */
@SuppressWarnings({ "rawtypes", "unchecked", "deprecation" })
public class FastSearcherTestCase {

    private final static DocumentdbInfoConfig documentdbInfoConfig = new DocumentdbInfoConfig(new DocumentdbInfoConfig.Builder());
    private MockBackend mockBackend;

    @Test
    public void testNoNormalizing() {
        Logger.getLogger(FastSearcher.class.getName()).setLevel(Level.ALL);
        FastSearcher fastSearcher = new FastSearcher(new MockBackend(),
                                                     new FS4ResourcePool(1),
                                                     new MockDispatcher(),
                                                     new SummaryParameters(null),
                                                     new ClusterParams("testhittype"),
                                                     new CacheParams(100, 1e64),
                                                     documentdbInfoConfig,
                                                     1);

        MockFSChannel.setEmptyDocsums(false);


        assertEquals(100, fastSearcher.getCacheControl().capacity()); // Default cache = 100Mb

        Result result = doSearch(fastSearcher, new Query("?query=ignored"), 0, 10);

        assertTrue(result.hits().get(0).getRelevance().getScore() > 1000);
    }

    @Test
    public void testNullQuery() {
        Logger.getLogger(FastSearcher.class.getName()).setLevel(Level.ALL);
        FastSearcher fastSearcher = new FastSearcher(new MockBackend(), 
                                                     new FS4ResourcePool(1),
                                                     new MockDispatcher(),
                                                     new SummaryParameters(null),
                                                     new ClusterParams("testhittype"),
                                                     new CacheParams(100, 1e64),
                                                     documentdbInfoConfig,
                                                     1);

        String query = "?junkparam=ignored";
        Result result = doSearch(fastSearcher,new Query(query), 0, 10);
        com.yahoo.search.result.ErrorMessage message = result.hits().getError();

        assertNotNull("Got error", message);
        assertEquals("Null query", message.getMessage());
        assertEquals(query, message.getDetailedMessage());
        assertEquals(Error.NULL_QUERY.code, message.getCode());
    }

    @Test
    public void testQueryWithRestrict() {
        mockBackend = new MockBackend();
        DocumentdbInfoConfig documentdbConfigWithOneDb =
            new DocumentdbInfoConfig(new DocumentdbInfoConfig.Builder().documentdb(new DocumentdbInfoConfig.Documentdb.Builder().name("testDb")));
        FastSearcher fastSearcher = new FastSearcher(mockBackend,
                                                     new FS4ResourcePool(1),
                                                     new MockDispatcher(), 
                                                     new SummaryParameters(null),
                                                     new ClusterParams("testhittype"),
                                                     new CacheParams(100, 1e64), 
                                                     documentdbConfigWithOneDb,
                                                     1);

        Query query = new Query("?query=foo&model.restrict=testDb");
        query.prepare();
        Result result = doSearch(fastSearcher, query, 0, 10);

        Packet receivedPacket = mockBackend.getChannel().getLastQueryPacket();
        byte[] encoded = QueryTestCase.packetToBytes(receivedPacket);
        System.out.println(Arrays.toString(encoded));
        byte[] correct = new byte[] {
            0, 0, 0, 100, 0, 0, 0, -38, 0, 0, 0, 0, 0, 16, 0, 6, 0, 10,
            QueryTestCase.ignored, QueryTestCase.ignored, QueryTestCase.ignored, QueryTestCase.ignored, // time left
            0, 0, -64, 4, 7, 100, 101, 102, 97, 117, 108, 116, 0, 0, 0, 1, 0, 0, 0, 5, 109, 97, 116, 99, 104, 0, 0, 0, 1, 0, 0, 0, 24, 100, 111, 99, 117, 109, 101, 110, 116, 100, 98, 46, 115, 101, 97, 114, 99, 104, 100, 111, 99, 116, 121, 112, 101, 0, 0, 0, 6, 116, 101, 115, 116, 68, 98, 0, 0, 0, 1, 0, 0, 0, 7, 68, 1, 0, 3, 102, 111, 111
        };
        QueryTestCase.assertEqualArrays(correct, encoded);
    }

    @Test
    public void testSearch() {
        FastSearcher fastSearcher = createFastSearcher();

        assertEquals(100, fastSearcher.getCacheControl().capacity()); // Default cache =100MB

        Result result = doSearch(fastSearcher,new Query("?query=ignored"), 0, 10);

        Execution execution = new Execution(chainedAsSearchChain(fastSearcher), Execution.Context.createContextStub());
        assertEquals(2, result.getHitCount());
        execution.fill(result);
        assertCorrectHit1((FastHit) result.hits().get(0));
        assertCorrectTypes1((FastHit) result.hits().get(0));
        for (int idx = 0; idx < result.getHitCount(); idx++) {
            assertTrue(!result.hits().get(idx).isCached());
        }

        // Repeat the request a couple of times, to verify whether the packet cache works
        result = doSearch(fastSearcher,new Query("?query=ignored"), 0, 10);
        assertEquals(2, result.getHitCount());
        execution.fill(result);
        assertCorrectHit1((FastHit) result.hits().get(0));
        for (int i = 0; i < result.getHitCount(); i++) {
            assertTrue(result.hits().get(i) + " should be cached",
                    result.hits().get(i).isCached());
        }

        // outside-range cache hit
        result = doSearch(fastSearcher,new Query("?query=ignored"), 6, 3);
        // fill should still work (nop)
        execution.fill(result);

        result = doSearch(fastSearcher,new Query("?query=ignored"), 0, 10);
        assertEquals(2, result.getHitCount());
        assertCorrectHit1((FastHit) result.hits().get(0));
        assertTrue("All hits are cached and the result knows it",
                result.isCached());
        for (int i = 0; i < result.getHitCount(); i++) {
            assertTrue(result.hits().get(i) + " should be cached",
                    result.hits().get(i).isCached());
        }

        clearCache(fastSearcher);

        result = doSearch(fastSearcher,new Query("?query=ignored"), 0, 10);
        assertEquals(2, result.getHitCount());
        execution.fill(result);
        assertCorrectHit1((FastHit) result.hits().get(0));
        assertTrue("All hits are not cached", !result.isCached());
        for (int i = 0; i < result.getHitCount(); i++) {
            assertTrue(!result.hits().get(i).isCached());
        }

        // Test that partial result sets can be retrieved from the cache
        clearCache(fastSearcher);
        result = doSearch(fastSearcher,new Query("?query=ignored"), 0, 1);
        assertEquals(1, result.getConcreteHitCount());
        execution.fill(result);

        result = doSearch(fastSearcher,new Query("?query=ignored"), 0, 2);
        assertEquals(2, result.getConcreteHitCount());
        execution.fill(result);
        // First hit should be cached but not second hit
        assertTrue(result.hits().get(0).isCached());
        assertFalse(result.hits().get(1).isCached());

        // Check that the entire result set is returned from cache now
        result = doSearch(fastSearcher,new Query("?query=ignored"), 0, 2);
        assertEquals(2, result.getConcreteHitCount());
        execution.fill(result);
        // both first and second should now be cached
        assertTrue(result.hits().get(0).isCached());
        assertTrue(result.hits().get(1).isCached());

        // Tests that the cache _hit_ is not returned if _another_
        // hit is requested
        clearCache(fastSearcher);

        result = doSearch(fastSearcher,new Query("?query=ignored"), 0, 1);
        assertEquals(1, result.getConcreteHitCount());

        result = doSearch(fastSearcher,new Query("?query=ignored"), 1, 1);
        assertEquals(1, result.getConcreteHitCount());

        for (int i = 0; i < result.getHitCount(); i++) {
            assertFalse("Hit " + i + " should not be cached.",
                result.hits().get(i).isCached());
        }
    }

    private Chain<Searcher> chainedAsSearchChain(Searcher topOfChain) {
        List<Searcher> searchers = new ArrayList<>();
        searchers.add(topOfChain);
        return new Chain<>(searchers);
    }

    private Result doSearch(Searcher searcher, Query query, int offset, int hits) {
        query.setOffset(offset);
        query.setHits(hits);
        return createExecution(searcher).search(query);
    }

    private Execution createExecution(Searcher searcher) {
        Execution.Context context = new Execution.Context(null, null, null, new RendererRegistry(Collections.emptyList()), new SimpleLinguistics());
        return new Execution(chainedAsSearchChain(searcher), context);
    }

    private void doFill(Searcher searcher, Result result) {
        createExecution(searcher).fill(result);
    }

    @Test
    public void requireThatPropertiesAreReencoded() throws Exception {
        FastSearcher fastSearcher = createFastSearcher();

        assertEquals(100, fastSearcher.getCacheControl().capacity()); // Default cache =100MB

        Query query = new Query("?query=ignored");
        query.getRanking().setQueryCache(true);
        Result result = doSearch(fastSearcher, query, 0, 10);

        Execution execution = new Execution(chainedAsSearchChain(fastSearcher), Execution.Context.createContextStub());
        assertEquals(2, result.getHitCount());
        execution.fill(result);

        Packet receivedPacket = mockBackend.getChannel().getLastReceived();
        ByteBuffer buf = ByteBuffer.allocate(1000);
        receivedPacket.encode(buf);
        buf.flip();
        byte[] actual = new byte[buf.remaining()];
        buf.get(actual);

        byte IGNORE = 69;
        byte[] expected = new byte[] { 0, 0, 0, -85, 0, 0, 0, -37, 0, 0, 48, 17, 0, 0, 0, 0,
                // query timeout
                IGNORE, IGNORE, IGNORE, IGNORE,
                // "default" - rank profile
                7, 'd', 'e', 'f', 'a', 'u', 'l', 't', 0, 0, -128, 0,
                // 3 property entries (rank, match, caches)
                0, 0, 0, 3,
                // rank: sessionId => qrserver.0.XXXXXXXXXXXXX.0
                0, 0, 0, 4, 'r', 'a', 'n', 'k', 0, 0, 0, 1, 0, 0, 0, 9, 's', 'e', 's', 's', 'i', 'o', 'n', 'I', 'd', 0, 0, 0, 26, 'q', 'r', 's', 'e', 'r', 'v', 'e', 'r', '.',
                IGNORE, '.', IGNORE, IGNORE, IGNORE, IGNORE, IGNORE, IGNORE, IGNORE, IGNORE, IGNORE, IGNORE, IGNORE, IGNORE, IGNORE, '.', IGNORE,
                // match: documentdb.searchdoctype => test
                0, 0, 0, 5, 'm', 'a', 't', 'c', 'h', 0, 0, 0, 1, 0, 0, 0, 24, 'd', 'o', 'c', 'u', 'm', 'e', 'n', 't', 'd', 'b', '.', 's', 'e', 'a', 'r', 'c', 'h', 'd', 'o', 'c', 't', 'y', 'p', 'e', 0, 0, 0, 4, 't', 'e', 's', 't',
                // sessionId => qrserver.0.XXXXXXXXXXXXX.0
                0, 0, 0, 6, 'c', 'a', 'c', 'h', 'e', 's', 0, 0, 0, 1, 0, 0, 0, 5, 'q', 'u', 'e', 'r', 'y', 0, 0, 0, 4, 't', 'r', 'u', 'e',
                // flags
                0, 0, 0, 2
        };
        //System.out.println(Arrays.toString(actual));
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; ++i) {
            if (expected[i] == IGNORE) {
                actual[i] = IGNORE;
            }
        }
        assertArrayEquals(expected, actual);
    }

    private FastSearcher createFastSearcher() {
        mockBackend = new MockBackend();
        ConfigGetter<DocumentdbInfoConfig> getter = new ConfigGetter<>(DocumentdbInfoConfig.class);
        DocumentdbInfoConfig config = getter.getConfig("file:src/test/java/com/yahoo/prelude/fastsearch/test/documentdb-info.cfg");

        MockFSChannel.resetDocstamp();
        Logger.getLogger(FastSearcher.class.getName()).setLevel(Level.ALL);
        return new FastSearcher(mockBackend,
                                new FS4ResourcePool(1),
                                new MockDispatcher(), 
                                new SummaryParameters(null),
                                new ClusterParams("testhittype"), 
                                new CacheParams(100, 1e64), 
                                config,
                                1);
    }

    @Ignore
    public void testSinglePhaseCachedSupersets() {
        Logger.getLogger(FastSearcher.class.getName()).setLevel(Level.ALL);
        MockFSChannel.resetDocstamp();
        FastSearcher fastSearcher = new FastSearcher(new MockBackend(),
                                                     new FS4ResourcePool(1),
                                                     new MockDispatcher(),
                                                     new SummaryParameters(null),
                                                     new ClusterParams("testhittype"),
                                                     new CacheParams(100, 1e64),
                                                     documentdbInfoConfig,
                                                     1);

        CacheControl c = fastSearcher.getCacheControl();

        Result result = doSearch(fastSearcher,new Query("?query=ignored"), 0, 2);
        Query q = new Query("?query=ignored");
        ((WordItem) q.getModel().getQueryTree().getRoot()).setUniqueID(1);
        QueryPacket queryPacket = QueryPacket.create(q);
        CacheKey k = new CacheKey(queryPacket);
        PacketWrapper p = c.lookup(k, q);
        assertEquals(1, p.getResultPackets().size());

        result = doSearch(fastSearcher,new Query("?query=ignored"), 1, 1);
        p = c.lookup(k, q);
        // ensure we don't get redundant QueryResultPacket instances
        // in the cache
        assertEquals(1, p.getResultPackets().size());

        assertEquals(1, result.getConcreteHitCount());
        for (int i = 0; i < result.getHitCount(); i++) {
            assertTrue(result.hits().get(i).isCached());
        }

        result = doSearch(fastSearcher,new Query("?query=ignored"), 0, 1);
        p = c.lookup(k, q);
        assertEquals(1, p.getResultPackets().size());
        assertEquals(1, result.getConcreteHitCount());
        for (int i = 0; i < result.getHitCount(); i++) {
            assertTrue(result.hits().get(i).isCached());
        }

    }

    @Test
    public void testMultiPhaseCachedSupersets() {
        Logger.getLogger(FastSearcher.class.getName()).setLevel(Level.ALL);
        MockFSChannel.resetDocstamp();
        FastSearcher fastSearcher = new FastSearcher(new MockBackend(),
                                                     new FS4ResourcePool(1),
                                                     new MockDispatcher(),
                                                     new SummaryParameters(null),
                                                     new ClusterParams("testhittype"),
                                                     new CacheParams(100, 1e64),
                                                     documentdbInfoConfig,
                                                     1);

        Result result = doSearch(fastSearcher,new Query("?query=ignored"), 0, 2);
        result = doSearch(fastSearcher,new Query("?query=ignored"), 1, 1);
        assertEquals(1, result.getConcreteHitCount());
        for (int i = 0; i < result.getHitCount(); i++) {
            assertTrue(result.hits().get(i).isCached());
            if (!result.hits().get(i).isMeta()) {
                assertTrue(result.hits().get(i).getFilled().isEmpty());
            }
        }

        result = doSearch(fastSearcher,new Query("?query=ignored"), 0, 1);
        assertEquals(1, result.getConcreteHitCount());
        for (int i = 0; i < result.getHitCount(); i++) {
            assertTrue(result.hits().get(i).isCached());
            if (!result.hits().get(i).isMeta()) {
                assertTrue(result.hits().get(i).getFilled().isEmpty());
            }
        }

    }

    // TODO: Enable this - it fails when on vpn
    @Ignore
    public void testPing() throws IOException, InterruptedException {
        Logger.getLogger(FastSearcher.class.getName()).setLevel(Level.ALL);
        BackendTestCase.MockServer server = new BackendTestCase.MockServer();
        FS4ResourcePool listeners = new FS4ResourcePool(new Fs4Config());
        Backend backend = listeners.getBackend(server.host.getHostString(),server.host.getPort());
        FastSearcher fastSearcher = new FastSearcher(backend,
                                                     new FS4ResourcePool(1),
                                                     new MockDispatcher(),
                                                     new SummaryParameters(null),
                                                     new ClusterParams("testhittype"),
                                                     new CacheParams(0, 0.0d),
                                                     documentdbInfoConfig,
                                                     1);
        server.dispatch.packetData = BackendTestCase.PONG;
        Chain<Searcher> chain = new Chain<>(fastSearcher);
        Execution e = new Execution(chain, Execution.Context.createContextStub());
        Pong pong = e.ping(new Ping());
        assertEquals(127, pong.getPongPacket(0).getDocstamp());
        backend.shutdown();
        listeners.deconstruct();
        server.dispatch.socket.close();
        server.dispatch.connection.close();
        server.worker.join();
        assertEquals(1, pong.getPongPacketsSize());
        Pong other = new Pong();
        other.setPingInfo(null);
        other.addError(ErrorMessage.createServerIsMisconfigured("as usual"));
        pong.merge(other);
        assertEquals(1, pong.getErrors().size());
        assertEquals(1, pong.getPongPackets().size());
        assertEquals("", other.getPingInfo());
        pong.setPingInfo("blbl");
        assertEquals("Result of pinging using blbl error : Service is misconfigured (as usual)",
                     pong.toString());
        assertEquals("Result of pinging error : Service is misconfigured (as usual)",
                     other.toString());
    }

    private void clearCache(FastSearcher fastSearcher) {
        fastSearcher.getCacheControl().clear();
    }

    private void assertCorrectTypes1(FastHit hit) {
        assertEquals(String.class, hit.getField("TITLE").getClass());
        assertEquals(Integer.class, hit.getField("BYTES").getClass());
    }

    private void assertCorrectHit1(FastHit hit) {
        assertEquals(
                "StudyOfMadonna.com - Interviews, Articles, Reviews, Quotes, Essays and more..",
                hit.getField("TITLE"));
        assertEquals("352", hit.getField("WORDS").toString());
        assertEquals(2003., hit.getRelevance().getScore(), 0.01d);
        assertEquals("index:0/234/0/" + FastHit.asHexString(hit.getGlobalId()), hit.getId().toString());
        assertEquals("9190", hit.getField("BYTES").toString());
        assertEquals("testhittype", hit.getSource());
    }

    private static class MockBackend extends Backend {

        private MockFSChannel channel;

        public MockBackend() {
            channel = new MockFSChannel(this, 1);
        }

        public FS4Channel openChannel() {
            return channel;
        }

        public MockFSChannel getChannel() { return channel; }

        public void shutdown() {}
    }


    /**
     * A channel which returns hardcoded packets of the same type as fdispatch
     */
    private static class MockFSChannel extends FS4Channel {

        public MockFSChannel(Backend backend, Integer channelId) {}

        private Packet lastReceived = null;

        private QueryPacket lastQueryPacket = null;

        /** Initial value of docstamp */
        private static int docstamp = 1088490666;

        private static boolean emptyDocsums = false;

        public synchronized boolean sendPacket(BasicPacket bPacket) {
            Packet packet = (Packet) bPacket;

            try {
                packet.encode(ByteBuffer.allocate(65536), 0);
            } catch (BufferTooSmallException e) {
                throw new RuntimeException("Too small buffer to encode packet in mock backend.");
            }
            if (packet instanceof QueryPacket) {
                lastQueryPacket = (QueryPacket) packet;
            } else if (!(packet instanceof GetDocSumsPacket)) {
                throw new RuntimeException(
                        "Mock channel don't know what to reply to " + packet);
            }
            lastReceived = packet;
            return true;
        }

        /** Change docstamp to invalidate cache */
        public static void resetDocstamp() {
            docstamp = 1088490666;
        }

        /** Flip sending (in)valid docsums */
        public static void setEmptyDocsums(boolean d) {
            emptyDocsums = d;
        }

        /** Returns the last query packet received or null if none */
        public QueryPacket getLastQueryPacket() {
            return lastQueryPacket;
        }

        public Packet getLastReceived() {
            return lastReceived;
        }

        public BasicPacket[] receivePackets(long timeout, int packetCount) {
            List packets = new java.util.ArrayList();

            if (lastReceived instanceof QueryPacket) {
                lastQueryPacket = (QueryPacket) lastReceived;
                QueryResultPacket result = QueryResultPacket.create();

                result.setDocstamp(docstamp);
                result.setChannel(0);
                result.setTotalDocumentCount(2);
                result.setOffset(lastQueryPacket.getOffset());

                if (lastQueryPacket.getOffset() == 0
                        && lastQueryPacket.getLastOffset() >= 1) {
                    result.addDocument(
                            new DocumentInfo(DocsumDefinitionTestCase.createGlobalId(123),
                                    2003, 234, 1000));
                }
                if (lastQueryPacket.getOffset() <= 1
                        && lastQueryPacket.getLastOffset() >= 2) {
                    result.addDocument(
                            new DocumentInfo(DocsumDefinitionTestCase.createGlobalId(456),
                                    1855, 234, 1001));
                }
                packets.add(result);
            } else if (lastReceived instanceof GetDocSumsPacket) {
                addDocsums(packets, lastQueryPacket);
            }
            while (packetCount >= 0 && packets.size() > packetCount) {
                packets.remove(packets.size() - 1);
            }

            return (Packet[]) packets.toArray(new Packet[packets.size()]);
        }

        /** Adds the number of docsums requested in queryPacket.getHits() */
        private void addDocsums(List packets, QueryPacket queryPacket) {
            int numHits = queryPacket.getHits();

            if (lastReceived instanceof GetDocSumsPacket) {
                numHits = ((GetDocSumsPacket) lastReceived).getNumDocsums();
            }
            for (int i = 0; i < numHits; i++) {
                ByteBuffer buffer;

                if (emptyDocsums) {
                    buffer = createEmptyDocsumPacketData();
                } else {
                    int[] docids = {
                        123, 456, 789, 789, 789, 789, 789, 789, 789,
                        789, 789, 789 };

                    buffer = createDocsumPacketData(docids[i],
                            DocsumDefinitionTestCase.docsum4);
                }
                buffer.position(0);
                packets.add(PacketDecoder.decode(buffer));
            }
            packets.add(EolPacket.create());
        }

        private ByteBuffer createEmptyDocsumPacketData() {
            ByteBuffer buffer = ByteBuffer.allocate(16);

            buffer.limit(buffer.capacity());
            buffer.position(0);
            buffer.putInt(12); // length
            buffer.putInt(205); // a code for docsumpacket
            buffer.putInt(0); // channel
            buffer.putInt(0); // dummy location
            return buffer;
        }

        private ByteBuffer createDocsumPacketData(int docid, byte[] docsumData) {
            ByteBuffer buffer = ByteBuffer.allocate(docsumData.length + 4 + 8 + GlobalId.LENGTH);

            buffer.limit(buffer.capacity());
            buffer.position(0);
            buffer.putInt(docsumData.length + 8 + GlobalId.LENGTH);
            buffer.putInt(205); // Docsum packet code
            buffer.putInt(0);
            byte[] rawGid = DocsumDefinitionTestCase.createGlobalId(docid).getRawId();
            buffer.put(rawGid);
            buffer.put(docsumData);
            return buffer;
        }

        public void close() {}
    }

    @Test
    public void null_summary_is_included_in_trace() {
        String summary = null;
        assertThat(getTraceString(summary), containsString("summary=[null]"));
    }

    @Test
    public void non_null_summary_is_included_in_trace() {
        String summary = "all";
        System.out.println(getTraceString(summary));
        assertThat(getTraceString(summary), containsString("summary='all'"));
    }

    private String getTraceString(String summary) {
        FastSearcher fastSearcher = createFastSearcher();

        Query query = new Query("?query=ignored");
        query.getPresentation().setSummary(summary);
        query.setTraceLevel(2);

        Result result = doSearch(fastSearcher, query, 0, 10);
        doFill(fastSearcher, result);

        Trace trace = query.getContext(false).getTrace();
        final AtomicReference<String> fillTraceString = new AtomicReference<>();


        trace.traceNode().accept(new TraceVisitor() {
            @Override
            public void visit(TraceNode traceNode) {
                if (traceNode.payload() instanceof String && traceNode.payload().toString().contains("fill to dispatch"))
                    fillTraceString.set((String) traceNode.payload());

            }
        });

        return fillTraceString.get();
    }

    /** Just a stub for now */
    private static class MockDispatcher extends Dispatcher {

        public MockDispatcher() {
            super(new DispatchConfig(new DispatchConfig.Builder()), new FS4ResourcePool(1));
        }

        public void fill(Result result, String summaryClass) {
        }

    }

}
