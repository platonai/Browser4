# browser4-wordcount

A Browser4 plugin that provides a simple word count tool for plain text.

## Usage

The plugin registers the `wordcount` domain with the tool `wordcount.getWordCount(text: String)`, which returns a `WordCountResult` containing:

- `words`: number of whitespace-separated words
- `chars`: total character count
- `charsNoSpaces`: non-whitespace character count
- `lines`: number of lines (`newline count + 1`; `0` for an empty string)

Enable or disable the plugin with the property `wordcount.enabled` (default `true`).
