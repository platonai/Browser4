package ai.platon.pulsar.examples.async;

import ai.platon.pulsar.common.LinkExtractors;
import ai.platon.pulsar.skeleton.context.PulsarContexts;
import ai.platon.pulsar.dom.FeaturedDocument;
import ai.platon.pulsar.persist.WebPage;
import ai.platon.pulsar.skeleton.session.PulsarSession;

import java.util.concurrent.CompletableFuture;

class CrawlAsync {

    private static String url = "https://www.amazon.com/Best-Sellers/zgbs";

    public static void loadAsync() throws Exception {
        PulsarSession session = PulsarContexts.createSession();
        WebPage page = session.loadAsync(url).join();
    }

    public static void loadAsync2() throws Exception {
        PulsarSession session = PulsarContexts.createSession();
        FeaturedDocument document = session.loadAsync(url)
                .thenApply(session::parse)
                .join();
    }

    public static void loadAsync3() throws Exception {
        PulsarSession session = PulsarContexts.createSession();
        String title = session.loadAsync(url)
                .thenApply(session::parse)
                .thenApply(FeaturedDocument::guessTitle)
                .join();
    }

    public static void loadAll() throws Exception {
        PulsarSession session = PulsarContexts.createSession();
        LinkExtractors.fromResource("seeds10.txt").stream()
                .map(session::open).map(session::parse)
                .map(FeaturedDocument::guessTitle)
                .forEach(System.out::println);
    }

    public static void loadAllAsync2() throws Exception {
        PulsarSession session = PulsarContexts.createSession();

        CompletableFuture<?>[] futures = LinkExtractors.fromResource("seeds10.txt").stream()
                .map(url -> url + " -i 1d")
                .map(session::loadAsync)
                .map(f -> f.thenApply(session::parse))
                .map(f -> f.thenApply(FeaturedDocument::guessTitle))
                .map(f -> f.thenAccept(System.out::println))
                .toArray(CompletableFuture<?>[]::new);

        CompletableFuture.allOf(futures).join();
    }

    public static void loadAllAsync3() throws Exception {
        PulsarSession session = PulsarContexts.createSession();

        CompletableFuture<?>[] futures = session.loadAllAsync(LinkExtractors.fromResource("seeds10.txt")).stream()
                .map(f -> f.thenApply(session::parse))
                .map(f -> f.thenApply(FeaturedDocument::guessTitle))
                .map(f -> f.thenAccept(System.out::println))
                .toArray(CompletableFuture<?>[]::new);

        CompletableFuture.allOf(futures).join();
    }

    public static void loadAllAsync4() throws Exception {
        PulsarSession session = PulsarContexts.createSession();

        CompletableFuture<?>[] futures = session.loadAllAsync(LinkExtractors.fromResource("seeds10.txt")).stream()
                .map(f -> f.thenApply(session::parse)
                        .thenApply(FeaturedDocument::guessTitle)
                        .thenAccept(System.out::println)
                )
                .toArray(CompletableFuture<?>[]::new);

        CompletableFuture.allOf(futures).join();
    }

    public static void main(String[] args) throws Exception {
        loadAll();
        loadAllAsync2();
        loadAllAsync3();
        loadAllAsync4();
    }
}
