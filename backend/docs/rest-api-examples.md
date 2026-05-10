# 🤖 Browser4 - REST API Examples

## 💬 For Beginners – Perform Actions & Extract Data

Use the `command` API to interact with webpages using natural language instructions.

### 🧾 Summary (Linux/macOS)

```bash
curl -X POST "http://localhost:8182/api/ai/command" \
  -H "Content-Type: text/plain" \
  -d '
    Visit https://www.amazon.com/dp/B08PP5MSVB
    Summarize the product.
  '
```


### 🧾 Summary (Windows PowerShell)

```powershell
Invoke-WebRequest -Uri "http://localhost:8182/api/ai/command" `
  -Method POST `
  -Headers @{ "Content-Type" = "text/plain" } `
  -Body '
    Visit https://www.amazon.com/dp/B08PP5MSVB
    Summarize the product.
  '
```


---

### 🧑‍🎨 Summary + Actions (Linux/macOS)

```bash
curl -X POST "http://localhost:8182/api/ai/command" \
  -H "Content-Type: text/plain" \
  -d '
    Visit https://www.amazon.com/dp/B08PP5MSVB
    Summarize the product.
    After page load: click #title, then scroll to the middle.
  '
```


---

### 📦 Summary + Data Extraction (Linux/macOS)

```bash
curl -X POST "http://localhost:8182/api/ai/command" \
  -H "Content-Type: text/plain" \
  -d '
    Visit https://www.amazon.com/dp/B08PP5MSVB
    Summarize the product.
    Extract: product name, price, ratings.
  '
```


---

### 🔗 Summary + Links + Actions (Linux/macOS)

```bash
curl -X POST "http://localhost:8182/api/ai/command" \
  -H "Content-Type: text/plain" \
  -d '
    Visit https://www.amazon.com/dp/B08PP5MSVB
    Summarize the product.
    Extract: product name, price, ratings.
    Find all links containing /dp/.
    After page load: click #title, then scroll to the middle.
  '
```


---

### 📄 JSON-Based Command (Linux/macOS)

```bash
curl -X POST "http://localhost:8182/api/ai/command" \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://www.amazon.com/dp/B08PP5MSVB",
    "pageSummaryPrompt": "Provide a brief introduction of this product.",
    "dataExtractionRules": "product name, price, and ratings",
    "uriExtractionRules": "all links containing `/dp/` on the page",
    "onPageReadyActions": ["click #title", "scroll to the middle"]
  }'
```


### 📄 JSON-Based Command (Windows PowerShell)

```powershell
Invoke-WebRequest -Uri "http://localhost:8182/api/ai/command" `
  -Method POST `
  -Headers @{ "Content-Type" = "application/json" } `
  -Body '{
    "url": "https://www.amazon.com/dp/B08PP5MSVB",
    "onBrowserLaunchedActions": [
      "clear browser cookies",
      "navigate to the home page",
      "click a random link"
    ],
    "onPageReadyActions": ["click #title", "scroll to the middle"],
    "pageSummaryPrompt": "Provide a brief introduction of this product.",
    "dataExtractionRules": "product name, price, and ratings",
    "uriExtractionRules": "all links containing `/dp/` on the page"
  }'
```


---

## 🧠 Free Chat with AI

Use the `chat` API to ask any question or test LLM capabilities.

### Ask via GET Request

```bash
curl "http://localhost:8182/api/ai/chat?prompt=What-is-the-most-fantastical-technology-today"
```


### Ask via POST Request (Linux/macOS)

```bash
curl -X POST "http://localhost:8182/api/ai/chat" \
  -H "Content-Type: application/json" \
  -d '
    What is the most fantastical technology today?
    You should return a list of 5 items.
  '
```


> 💡 **Tip:** Use this to verify if the LLM integration works correctly.

---

## 🎓 For Advanced Users – LLM + X-SQL

Use structured SQL-like queries combined with LLM extraction.

### Example Query

```bash
curl -X POST "http://localhost:8182/api/x/e" \
  -H "Content-Type: text/plain" \
  -d "
    SELECT
      llm_extract(dom, 'product name, price, ratings') AS llm_extracted_data,
      dom_base_uri(dom) AS url,
      dom_first_text(dom, '#productTitle') AS title,
      dom_first_slim_html(dom, 'img:expr(width > 400)') AS img
    FROM load_and_select('https://www.amazon.com/dp/B08PP5MSVB', 'body');
  "
```


### Sample Output

```json
{
  "llm_extracted_data": {
    "product name": "Apple iPhone 15 Pro Max",
    "price": "$1,199.00",
    "ratings": "4.5 out of 5 stars"
  },
  "url": "https://www.amazon.com/dp/B08PP5MSVB",
  "title": "Apple iPhone 15 Pro Max",
  "img": "<img src=\"https://example.com/image.jpg\" />"
}
```


---

## 🔍 Complex Data Extraction with X-SQL

```sql
SELECT
    llm_extract(dom, 'product name, price, ratings, score') AS llm_extracted_data,
    dom_first_text(dom, '#productTitle') AS title,
    dom_first_text(dom, '#bylineInfo') AS brand,
    dom_first_text(dom, '#price tr td:matches(^Price) ~ td') AS price,
    dom_first_text(dom, '#acrCustomerReviewText') AS ratings,
    str_first_float(dom_first_text(dom, '#reviewsMedley .AverageCustomerReviews span:contains(out of)'), 0.0) AS score
FROM load_and_select('https://www.amazon.com/dp/B08PP5MSVB  -i 1s -njr 3', 'body');
```


📚 **Example Code Repositories**:
* [Amazon Product Page Scraping (100+ fields)](https://github.com/platonai/exotic-amazon/tree/main/src/main/resources/sites/amazon/crawl/parse/sql/crawl)
* [All Amazon Page Types Scraping](https://github.com/platonai/exotic-amazon/tree/main/src/main/resources/sites/amazon/crawl/parse/sql/crawl)
