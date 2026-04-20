
Convert a natural language web automation request into a structured JSON configuration for automated browser interaction and data extraction.

### 🎯 Task:
Transform the user's conversational instructions into a precise JSON object that defines browser automation workflow, page interactions, and data extraction requirements.

### 📋 JSON Structure:
Use this template with only the fields relevant to the request:

```json-text
{PLACEHOLDER_REQUEST_JSON_COMMAND_TEMPLATE}
```

### 🔧 Field Guidelines:

**Required Fields:**
* `url`: Always preserve as `{PLACEHOLDER_URL}` - do not modify this placeholder

**Optional Fields (include only if relevant):**
* `onBrowserLaunchedActions`: Pre-navigation setup actions (cookies, authentication, etc.)
    - Execute in the specified order before loading the target page
    - Common actions: cookie management, login, navigation setup

* `onPageReadyActions`: Page interaction steps after initial load
    - Execute in the specified order once the page is fully loaded
    - Common actions: clicking, scrolling, form filling, waiting

* `pageSummaryPrompt`: Natural language instructions for page content summarization
    - Be specific about what aspects to focus on
    - Include desired output format or structure

* `dataExtractionRules`: Structured data extraction specifications
    - Define exact field names and expected formats
    - Specify selectors, patterns, or identification methods

* `uriExtractionRules`: Natural language instructions to extract URIs from the page
    - Will be converted into regex patterns automatically
    - Example: "extract all product links containing '/dp/'"

* `xsql`: X-SQL query to run on the page's DOM
  - Use for advanced data extraction or processing
  - Should be a valid X-SQL query string

### ⚡ Conversion Rules:
* **Specificity**: Convert vague instructions into concrete, executable actions
* **Order**: Sequence actions logically (setup → navigation → interaction → extraction)
* **Clarity**: Use precise language that eliminates ambiguity
* **Relevance**: Only include fields that address the user's actual requirements
* **Actionability**: Every instruction should be directly executable by automation

### 🌐 Language Support:
Process requests in any language and output JSON with English field values.

### 📥 User Request:

```text
{PLACEHOLDER_REQUEST_PLAIN_COMMAND_TEMPLATE}
```

### 📤 Expected Output:
Return only the JSON object - no explanations or additional text.
