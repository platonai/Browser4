package ai.platon.pulsar.examples;

import ai.platon.pulsar.skeleton.context.PulsarContexts;
import ai.platon.pulsar.dom.FeaturedDocument;
import ai.platon.pulsar.persist.WebPage;
import ai.platon.pulsar.skeleton.session.PulsarSession;
import com.google.gson.Gson;
import org.jsoup.nodes.Element;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class BasicUsage {

    public static String PRODUCT_DETAIL_URL = "https://www.amazon.com/dp/B08PP5MSVB";

    public static void main(String[] args) throws Exception {

        // create a pulsar session
        PulsarSession session = PulsarContexts.createSession();
        // The main url we are playing with
        String url = PRODUCT_DETAIL_URL;
        // load a page, or fetch it from the Internet if it does not exist or has expired
        WebPage page = session.load(url, "-expires 1d");
        // submit a url to the URL pool, and it will be processed in a crawl loop
        session.submit(url, "-expires 1d");
        // parse the page content into a Jsoup document
        FeaturedDocument document = session.parse(page, false);
        // do something with the document
        // ...

        // or, load and parse
        FeaturedDocument document2 = session.loadDocument(url, "-expires 1d");
        // do something with the document
        // ...

        // load all pages with links specified by -outLink
        List<WebPage> pages = session.loadOutPages(url, "-expires 1d -itemExpires 7d -outLink a[href~=item]");
        // load the portal page and submit the out links specified by the `-outLink` option to the URL pool
        session.submitForOutPages(url, "-expires 1d -itemExpires 7d -outLink a[href~=item]");
        // load, parse and scrape fields
        List<Map<String, String>> fields = session.scrape(url, "-expires 1d", "li[data-sku]",
                Arrays.asList(".p-name em", ".p-price"));
        // load, parse and scrape named fields
        List<Map<String, String>> fields2 = session.scrape(url, "-i 1d", "li[data-sku]",
                Map.of("name", ".p-name em", "price", ".p-price"));

        System.out.println("== document");
        System.out.println(document.getTitle());
        System.out.println(document.selectFirstOptional("title").map(Element::text));

        System.out.println("== document2");
        System.out.println(document2.getTitle());
        System.out.println(document2.selectFirstOptional("title").map(Element::text));

        System.out.println("== pages");
        System.out.println(pages.stream().map(WebPage::getUrl).collect(Collectors.toList()));

        Gson gson = new Gson();
        System.out.println("== fields");
        System.out.println(gson.toJson(fields));

        System.out.println("== fields2");
        System.out.println(gson.toJson(fields2));
    }
}
