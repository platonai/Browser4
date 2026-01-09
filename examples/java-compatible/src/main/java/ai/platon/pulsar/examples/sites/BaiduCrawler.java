package ai.platon.pulsar.examples.sites;

import ai.platon.pulsar.skeleton.context.PulsarContexts;
import ai.platon.pulsar.skeleton.session.PulsarSession;

public class BaiduCrawler {
    public static void main(String[] args) throws Exception {
        var url = "https://www.baidu.com/";
        try (var session = PulsarContexts.createSession()) {
            session.open(url);
        }
    }
}
