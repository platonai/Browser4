package ai.platon.pulsar.examples.advanced;

import ai.platon.pulsar.browser.common.BlockRule;
import ai.platon.pulsar.common.LinkExtractors;
import ai.platon.pulsar.common.NetUtil;
import ai.platon.pulsar.common.config.CapabilityTypes;
import ai.platon.pulsar.persist.WebPage;
import ai.platon.pulsar.skeleton.PulsarSettings;
import ai.platon.pulsar.skeleton.context.PulsarContexts;
import ai.platon.pulsar.skeleton.crawl.common.url.ListenableHyperlink;
import ai.platon.pulsar.skeleton.crawl.event.JvmWebPageWebDriverEventHandler;
import ai.platon.pulsar.skeleton.crawl.fetch.driver.JvmWebDriver;
import ai.platon.pulsar.skeleton.session.PulsarSession;
import kotlin.coroutines.Continuation;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class JvmHighPerformanceCrawler {
    private final PulsarSession session;

    public JvmHighPerformanceCrawler() throws Exception {
        session = PulsarContexts.getOrCreateSession();
    }

    public void crawl() {
        // Crawl arguments:
        // -refresh: always re-fetch the page
        // -dropContent: do not persist page content
        // -interactLevel fastest: prioritize speed over data completeness
        String args = "-refresh -dropContent -interactLevel fastest";

        // Block non-essential resources to improve load speed.
        // WARNING: Blocking critical resources may break rendering or script execution.
        List<String> blockingUrls = new BlockRule().getBlockingUrls();

        String resource = "seeds/amazon/best-sellers/leaf-categories.txt";
        Iterable<String> urls = LinkExtractors.fromResource(resource);
        List<ListenableHyperlink> links = new ArrayList<>();
        for (String url : urls) {
            var hyperlink = ListenableHyperlink.create(url);
            hyperlink.setArgs(args);

            var eventHandlers = hyperlink.getEventHandlers().getBrowseEventHandlers().getOnWillNavigate();
            eventHandlers.addLast(new JvmWebPageWebDriverEventHandler() {
                @Override
                public Object invoke(WebPage page, JvmWebDriver driver, Continuation<? super Object> $completion) {
                    driver.addBlockedURLsAsync(blockingUrls);
                    return null;
                }
            });

            links.add(hyperlink);
        }

        session.submitAll(links);
    }

    public static void main(String[] args) throws Exception {
        // Highly recommended to enable proxies, or you will be blocked by Amazon
        String proxyHubURL = "http://localhost:8192/api/proxies";
        if (NetUtil.testHttpNetwork(proxyHubURL)) {
            System.setProperty(CapabilityTypes.PROXY_ROTATION_URL, proxyHubURL);
        }

        PulsarSettings.maxBrowserContexts(2);
        PulsarSettings.maxOpenTabs(8);
        PulsarSettings.withSequentialBrowsers();

        var crawler = new JvmHighPerformanceCrawler();
        crawler.crawl();
        PulsarContexts.await();
    }
}
