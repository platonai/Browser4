package ai.platon.pulsar.examples;

import ai.platon.pulsar.common.LinkExtractors;
import ai.platon.pulsar.common.urls.Hyperlink;
import ai.platon.pulsar.skeleton.context.PulsarContext;
import ai.platon.pulsar.skeleton.context.PulsarContexts;
import ai.platon.pulsar.skeleton.crawl.common.url.ParsableHyperlink;
import ai.platon.pulsar.dom.FeaturedDocument;
import ai.platon.pulsar.persist.WebPage;

import java.util.List;
import java.util.stream.Collectors;

public class ContinuousCrawler {

    private static void onParse(WebPage page, FeaturedDocument document) {
        // do something wonderful with the document
        System.out.println(document.getTitle() + "\t|\t" + document.getBaseURI());

        // extract more links from the document
        List<Hyperlink> urls = document.selectHyperlinks("a[href~=/dp/]");
        PulsarContexts.create().submitAll(urls);
    }

    public static void main(String[] args) throws InterruptedException {
        List<Hyperlink> urls = LinkExtractors.fromResource("seeds.txt")
                .stream()
                .map(seed -> new ParsableHyperlink(seed, ContinuousCrawler::onParse))
                .collect(Collectors.toList());
        PulsarContext context = PulsarContexts.create().submitAll(urls);
        // feel free to submit millions of urls here
        // ...
        context.await();
    }
}
