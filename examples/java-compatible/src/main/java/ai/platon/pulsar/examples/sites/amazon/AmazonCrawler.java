package ai.platon.pulsar.examples.sites.amazon;

import ai.platon.pulsar.skeleton.context.PulsarContexts;

import java.util.Map;

class AmazonCrawler {

    public static void main(String[] argv) throws Exception {
        PulsarContexts.createSession().scrapeOutPages(
                "https://www.amazon.com/Best-Sellers/zgbs",
                "-refresh -outLink a[href~=/dp/]",
                Map.of("title", "#title", "ratings", "#acrCustomerReviewText")
        );
    }
}
