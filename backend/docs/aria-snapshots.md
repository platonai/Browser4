---
id: aria-snapshots
title: "Aria Snapshot"
---

## Overview

With Browser4's snapshot functionality you can access a YAML representation of the accessibility tree of a page,
which usually be friendly to AI agents to understand the page structure and interact with it.

```yaml
- banner:
  - heading /Browser4 enables AI to access the Web/ [level=1]
  - link "Get started":
    - /url: /docs/concepts
  - link "Star platonai/browser4 on GitHub":
    - /url: https://github.com/platonai/Browser4
  - link /[\\d]+k\\+ stargazers on GitHub/
```

## Aria snapshots

In Browser4, aria snapshots provide a YAML representation of the accessibility tree of a page.

The YAML format describes the hierarchical structure of accessible elements on the page, detailing **roles**, **attributes**, **values**, and **text content**.
The structure follows a tree-like syntax, where each node represents an accessible element, and indentation indicates
nested elements.

Each accessible element in the tree is represented as a YAML node:

```yaml
- role "name" [attribute=value]
```

- **role**: Specifies the ARIA or HTML role of the element (e.g., `heading`, `list`, `listitem`, `button`).
- **"name"**: Accessible name of the element. Quoted strings indicate exact values, `/patterns/` are used for regular expression.
- **[attribute=value]**: Attributes and values, in square brackets, represent specific ARIA attributes, such
  as `checked`, `disabled`, `expanded`, `level`, `pressed`, or `selected`.

Browser4's rendered ARIA snapshots follow Playwright's YAML shape for AI-oriented snapshots, so interactive elements can
also include stable `[ref=eN]` markers and `[cursor=pointer]` hints:

```yaml title="playwright-style snapshot"
- link "Get started" [ref=e39] [cursor=pointer]:
    - /url: /docs/intro
```

These values are derived from ARIA attributes or calculated based on HTML semantics. To inspect the accessibility tree
structure of a page, use the [Chrome DevTools Accessibility Tab](https://developer.chrome.com/docs/devtools/accessibility/reference#tab).

## Accessibility tree examples

### Headings with level attributes

Headings can include a `level` attribute indicating their heading level.

```html
<h1>Title</h1>
<h2>Subtitle</h2>
```

```yaml title="aria snapshot"
- heading "Title" [level=1]
- heading "Subtitle" [level=2]
```

### Text nodes

Standalone or descriptive text elements appear as text nodes.

```html
<div>Sample accessible name</div>
```

```yaml title="aria snapshot"
- text: Sample accessible name
```

### Inline multiline text

Multiline text, such as paragraphs, is normalized in the aria snapshot.

```html
<p>Line 1<br>Line 2</p>
```

```yaml title="aria snapshot"
- paragraph: Line 1 Line 2
```

### Links

Links display their text or composed content from pseudo-elements. The link’s destination may be matched using the
`/url` property.

```html
<a href="#more-info">Read more about Accessibility</a>
```

```yaml title="aria snapshot"
- link "Read more about Accessibility":
    - /url: "#more-info"
```

The value of `/url` may also be a regular expression:

```html
<a href="https://www.youtube.com/channel/UC46Zj8pDH5tDosqm1gd7WTg">YouTube channel</a>
```

```yaml title="aria snapshot"
- link:
  - /url: /https://www.youtube.com/channel/.*/
```

### Text boxes

Input elements of type `text` show their `value` attribute content.

```html
<input type="text" value="Enter your name">
```

```yaml title="aria snapshot"
- textbox: Enter your name
```

### Lists with items

Ordered and unordered lists include their list items.

```html
<ul aria-label="Main Features">
  <li>Feature 1</li>
  <li>Feature 2</li>
</ul>
```

```yaml title="aria snapshot"
- list "Main Features":
  - listitem: Feature 1
  - listitem: Feature 2
```

### Grouped elements

Groups capture nested elements, such as `<details>` elements with summary content.

```html
<details>
  <summary>Summary</summary>
  <p>Detail content here</p>
</details>
```

```yaml title="aria snapshot"
- group: Summary
```

### Attributes and states

Commonly used ARIA attributes, like `checked`, `disabled`, `expanded`, `level`, `pressed`, and `selected`, represent
control states.

#### Checkbox with `checked` attribute

```html
<input type="checkbox" checked>
```

```yaml title="aria snapshot"
- checkbox [checked]
```

#### Button with `pressed` attribute

```html
<button aria-pressed="true">Toggle</button>
```

```yaml title="aria snapshot"
- button "Toggle" [pressed=true]
```
