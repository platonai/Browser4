# Browser4 log format explained

Browser4 has carefully designed the logging and metrics subsystem to record every event that occurs in the system. This document explains the format of typical logs.

Browser4 splits all logs into several separate files:

```
logs/pulsar.log    - the default logs
logs/pulsar.pg.log - mainly reports the status of load tasks
logs/pulsar.m.log  - the metrics
```

The status of loading tasks is the primary concern. You can gain insight into the state of the entire system just by noticing a few symbols: 💯 💔 🗙 ⚡💿 🔃🤺。

Here are 5 example logs which report the status of loaded tasks:

```
2022-09-24 11:46:26.045  INFO [-worker-14] a.p.p.c.c.L.Task - 3313. 💯 ⚡ U for N got 200 580.92 KiB in 1m14.277s, fc:1 | 75/284/96/277/6554 | 106.32.12.75 | 3xBpaR2 | https://www.walmart.com/ip/Restored-iPhone-7-32GB-Black-T-Mobile-Refurbished/329207863  -expires PT24H -ignoreFailure -itemExpires PT1M -outLinkSelector a[href~=/ip/] -parse -requireSize 300000
2022-09-24 11:46:09.190  INFO [-worker-32] a.p.p.c.c.L.Task - 3738. 💯 💿 U  got 200 452.91 KiB in 55.286s, last fetched 9h32m50s ago, fc:1 | 49/171/82/238/6172 | 121.205.220.179 | https://www.walmart.com/ip/Boost-Mobile-Apple-iPhone-SE-2-Cell-Phone-Black-64GB-Prepaid-Smartphone/490934488  -expires PT24H -ignoreFailure -itemExpires PT1M -outLinkSelector a[href~=/ip/] -parse -requireSize 300000
2022-09-24 11:46:28.567  INFO [-worker-17] a.p.p.c.c.L.Task - 2269. 💯 🔃 U for SC got 200 565.07 KiB <- 543.41 KiB in 1m22.767s, last fetched 16m58s ago, fc:6 | 58/230/98/295/6272 | 27.158.125.76 | 9uwu602 | https://www.walmart.com/ip/Straight-Talk-Apple-iPhone-11-64GB-Purple-Prepaid-Smartphone/356345388?variantFieldId=actual_color  -expires PT24H -ignoreFailure -itemExpires PT1M -outLinkSelector a[href~=/ip/] -parse -requireSize 300000
2022-09-24 11:47:18.390  INFO [r-worker-8] a.p.p.c.c.L.Task - 3732. 💔 ⚡ U for N got 1601 0 <- 0 in 32.201s, fc:1/1 Retry(1601) rsp: CRAWL, rrs: EMPTY_0B | 2zYxg52 | https://www.walmart.com/ip/Apple-iPhone-7-256GB-Jet-Black-AT-T-Locked-Smartphone-Grade-B-Used/182353175?variantFieldId=actual_color  -expires PT24H -ignoreFailure -itemExpires PT1M -outLinkSelector a[href~=/ip/] -parse -requireSize 300000
2022-09-24 11:47:13.860  INFO [-worker-60] a.p.p.c.c.L.Task - 2828. 🗙 🗙 U for SC got 200 0 <- 348.31 KiB <- 684.75 KiB in 0s, last fetched 18m55s ago, fc:2 | 34/130/52/181/5747 | 60.184.124.232 | 11zTa0r2 | https://www.walmart.com/ip/Walmart-Family-Mobile-Apple-iPhone-11-64GB-Black-Prepaid-Smartphone/209201965?athbdg=L1200  -expires PT24H -ignoreFailure -itemExpires PT1M -outLinkSelector a[href~=/ip/] -parse -requireSize 300000
```

The following example log reports a retrying page:

```
2022-09-24 11:46:12.167  INFO [-worker-62] a.p.p.c.i.S.Task - 3744. 🤺 Trying 2th 10s later | U  got 1601 0 <- 0 in 1m0.612s, last fetched 10s ago, fc:1/1 Retry(1601) rsp: CRAWL | https://www.walmart.com/ip/iPhone-7-128GB-Silver-Boost-Mobile-Used-Grade-B/662547852 
```

This document explains each field in the logs.

## Part I: general information pre-defined by the logging system

```
Date       Time          LogLevel  ThreadName   LogName
2022-09-24 11:46:12.167  INFO      [-worker-62] a.p.p.c.i.S.Task -
2022-09-24 11:46:09.190  INFO      [-worker-32] a.p.p.c.c.L.Task -
```

## Part II: PageId, TaskStatus, PageStatus, PageCategory, FetchReason, FetchCode, PageSize and FetchTime

```
PageId    TaskStatus PageStatus  PageCategory   FetchReason     FetchCode      PageSize                        FetchTime
3313.     💯         ⚡           U            for N           got 200         580.92 KiB                     in 1m14.277s
3738.     💯         💿           U                            got 200         452.91 KiB                     in 55.286s
2269.     💯         🔃           U            for SC          got 200         565.07 KiB <- 543.41 KiB       in 1m22.767s
3732.     💔         ⚡           U            for N           got 1601        0 <- 0 in 32.201s
2828.     🗙          🗙           U            for SC          got 200          0 <- 348.31 KiB <- 684.75 KiB  in 0s
```

`PageId` is the id of the WebPage object and is unique process-wide.

`TaskStatus` is a unicode symbol, can be one of the following:

- 💯 - Task is success
- 💔 - Task is failed
- 🗙 - Task is canceled
- 🤺 - Task is retrying

`PageStatus` is a unicode symbol, can be one of the following:

- ⚡ - Page is first fetched from the Internet
- 💿 - Page is loaded from hard disk
- 🔃 - Page is updated from the Internet
- 🗙 - Page is canceled and remains unchanged

`FetchReason` indicates why the page was fetched. The reason can be one of the following:

- The page was never fetched
- The page has expired since the last fetch
- The -refresh option is applied, so the page should be refreshed
- The page was scheduled to fetch
- Last fetch was failed and retried
- Last fetched page has no content
- Last fetched page content was too small
- Required fields missed in the last fetched page content
- The page was temporary moved



`FetchReason` contains one or two characters, defined as follows:

```
symbols[DO_NOT_FETCH] = ""
symbols[NEW_PAGE] = "N"
symbols[REFRESH] = "RR"
symbols[EXPIRED] = "EX"
symbols[SCHEDULED] = "SD"
symbols[RETRY] = "RT"
symbols[NO_CONTENT] = "NC"
symbols[SMALL_CONTENT] = "SC"
symbols[MISS_FIELD] = "MF"
symbols[TEMP_MOVED] = "TM"
symbols[UNKNOWN] = "U"
```

`FetchCode` is a number describing the fetch phase state, inherited from standard HTTP error codes, and is usually one of the following:

```
200 - success
1601 - retry
```

All possible codes are defined in `ProtocolStatusCodes.java`.

## Part III - PrevFetchTime, FetchCount, FetchFailure, DOMStatistic, ProxyIP, and PrivacyContext

```
PrevFetchTime               FetchCount        FetchFailure                           DOMStatistic         ProxyIP           PrivacyContext
                            fc:1 |                                                   75/284/96/277/6554 | 106.32.12.75    | 3xBpaR2
last fetched 9h32m50s ago,  fc:1 |                                                   49/171/82/238/6172 | 121.205.220.179
last fetched 16m58s ago,    fc:6 |                                                   58/230/98/295/6272 | 27.158.125.76   | 9uwu602
                            fc:1/1            Retry(1601) rsp: CRAWL, rrs: EMPTY_0B                                       | 2zYxg52
last fetched 18m55s ago,    fc:2 |                                                   34/130/52/181/5747 | 60.184.124.232  | 11zTa0r2
```

`PrevFetchTime` is the time when the previous fetch completed.

`FetchCount` is the count of all fetch executions, excluding cancelled fetches.

`FetchFailure` is the failure information of the previous fetch execution, and it is empty if it succeeds.

`DOMStatistic` contains simple statistics on the HTML document, calculated using JavaScript in a real browser, in the following format:

```
58/230/98/295/6272
58/230/98/295/6272 (i/a/nm/st/h)
```

Where:

- i: anchor count in the HTML document
- a: image count
- nm: number count
- st: small text count
- h: scroll height of the document in pixels

`DOMStatistic` indicates whether the page was fetched correctly; a fully loaded page usually has a scroll height higher than 5,000 pixels, and pages below this value may need to be re-fetched.

For other fields, such as ProxyIP and PrivacyContext, no explanation is needed.

## Part IV: the task URL

```
URL
https://www.walmart.com/ip/329207863  -expires PT24H -ignoreFailure -itemExpires PT1M -outLinkSelector a[href~=/ip/] -parse -requireSize 300000
https://www.walmart.com/ip/490934488  -expires PT24H -ignoreFailure -itemExpires PT1M -outLinkSelector a[href~=/ip/] -parse -requireSize 300000
https://www.walmart.com/ip/356345388  -expires PT24H -ignoreFailure -itemExpires PT1M -outLinkSelector a[href~=/ip/] -parse -requireSize 300000
https://www.walmart.com/ip/182353175  -expires PT24H -ignoreFailure -itemExpires PT1M -outLinkSelector a[href~=/ip/] -parse -requireSize 300000
https://www.walmart.com/ip/209201965  -expires PT24H -ignoreFailure -itemExpires PT1M -outLinkSelector a[href~=/ip/] -parse -requireSize 300000
```

The URL field is the URL to fetch, which can be followed by load arguments or load options. 
For details, check [Load Options](zh/get-started/3load-options.md).
