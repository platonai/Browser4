# Web Scraping Selectors Template

## Common Patterns

This file contains common CSS selector patterns for typical web scraping scenarios.

### Article/Blog Post Selectors

```css
/* Article container */
article, .article, .post, .entry

/* Title/Heading */
h1, .title, .post-title, .entry-title

/* Content body */
.content, .article-body, .post-content, .entry-content, main

/* Author */
.author, .byline, [rel="author"], .post-author

/* Publication date */
time, .date, .published, .post-date, .timestamp

/* Tags/Categories */
.tags a, .categories a, .post-tags a
```

### E-commerce Selectors

```css
/* Product container */
.product, .item, [data-product]

/* Product name */
.product-name, .product-title, h1.product

/* Price */
.price, .product-price, [data-price]

/* Description */
.description, .product-description

/* Images */
.product-image img, .gallery img

/* Reviews/Rating */
.rating, .stars, [data-rating]

/* Stock status */
.stock, .availability, [data-stock]
```

### Social Media Selectors

```css
/* Post container */
.post, .tweet, .status, [data-post]

/* Post content */
.post-content, .tweet-text, .status-text

/* Author/Username */
.username, .author, [data-username]

/* Timestamp */
.timestamp, time, [data-time]

/* Engagement metrics */
.likes, .shares, .comments, [data-likes]
```

### Form Selectors

```css
/* Form container */
form, .form, [role="form"]

/* Input fields */
input[type="text"], input[type="email"], textarea

/* Buttons */
button[type="submit"], .submit, .btn-submit

/* Labels */
label, .form-label

/* Validation messages */
.error, .validation-error, .field-error
```

## Usage

Reference these patterns in your scraping parameters:

```kotlin
val params = mapOf(
    "url" to "https://example.com",
    "selector" to ".product .product-name"
)
```

## Custom Patterns

Add your own patterns here for reuse across multiple scraping operations.
