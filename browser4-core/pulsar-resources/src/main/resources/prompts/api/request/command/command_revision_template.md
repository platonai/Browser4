Convert a JSON automation command into clear, numbered steps in plain language.

### 🎯 Task:
Transform the structured JSON command into human-readable instructions that explain what the automation will do.

### 🔧 Guidelines:
* Start with "Visit [url]"
* Convert each action in "onBrowserLaunchedActions" to numbered sub-steps under "When browser launches:"
* Convert each action in "onPageReadyActions" to numbered sub-steps under "When page is ready:"
* Add steps for page summarization, data extraction, and link collection if specified
* Use clear, concise numbered instructions
* Maintain logical action sequence

### 📋 Example Output Format:
```
{PLACEHOLDER_REQUEST_PLAIN_COMMAND_TEMPLATE}
```

### 📥 JSON Command to Convert:

```json-text
{PLACEHOLDER_JSON_VALUE}
```

### 📤 Expected Output:
Return only the numbered steps - no explanations or additional text.