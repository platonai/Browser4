package ai.platon.pulsar.examples.rpa;

import ai.platon.pulsar.skeleton.common.options.LoadOptions;
import ai.platon.pulsar.skeleton.context.PulsarContexts;
import ai.platon.pulsar.skeleton.crawl.event.JvmWebPageWebDriverEventHandler;
import ai.platon.pulsar.skeleton.crawl.fetch.driver.JvmWebDriver;
import ai.platon.pulsar.persist.WebPage;
import ai.platon.pulsar.skeleton.session.PulsarSession;
import kotlin.coroutines.Continuation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class RPACrawler {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final PulsarSession session;

    public final Map<String, String> fieldSelectors;

    public RPACrawler() throws Exception {
        this(PulsarContexts.createSession());
    }

    public RPACrawler(PulsarSession session) {
        this.session = session;

        fieldSelectors = new HashMap<>(Map.of(
                "sku-name", ".sku-name",
                "news", ".news",
                "summary", ".summary"
        ));
    }

    public LoadOptions options(String args) {
        var options = session.options(args, null);
        var be = options.getEventHandlers().getBrowseEventHandlers();

        be.getOnWillComputeFeature().addLast(new JvmWebPageWebDriverEventHandler() {
            @Override
            public Object invoke(WebPage page, JvmWebDriver driver, Continuation<? super Object> continuation) {
                fieldSelectors.values().forEach(selector -> interact(selector, driver));
                return null;
            }
        });

        be.getOnFeatureComputed().addLast(new JvmWebPageWebDriverEventHandler() {
            @Override
            public Object invoke(WebPage page, JvmWebDriver driver, Continuation<? super Object> $completion) {
                logger.info("Feature computed");
                return null;
            }
        });

        return options;
    }

    private void interact(String selector, JvmWebDriver driver) {
        var delayedExecutor = CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS);
        var searchBoxSelector = ".form input[type=text]";

        driver.existsAsync(selector).thenAccept(exists -> {
            if (exists) {
                driver.clickAsync(selector)
                        .thenCompose(ignored -> driver.selectFirstTextOptionalAsync(selector))
                        .thenAcceptAsync(text -> driver.typeAsync(searchBoxSelector, text.orElse("").substring(1, 4)), delayedExecutor)
                        .thenRun(() -> logger.info("{} clicked", selector))
                        .join();
            }
        }).join();
    }

    public static void main(String[] argv) throws Exception {
        var url = "https://item.jd.com/10023632209832.html";
        var args = "-refresh -parse";

        var session = PulsarContexts.createSession();
        var crawler = new RPACrawler(session);

        var fields = session.scrape(url, crawler.options(args), crawler.fieldSelectors);
        System.out.println(fields);
    }
}
