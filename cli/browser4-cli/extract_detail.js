JSON.stringify({
  title: (document.querySelector('#productTitle') || {}).textContent?.trim() || '',
  price: (document.querySelector('.a-price .a-offscreen') || {}).textContent?.trim() || '',
  rating: (document.querySelector('#acrPopover') || {}).getAttribute?.('title') || (document.querySelector('[data-hook="rating-out-of-text"]') || {}).textContent?.trim() || '',
  reviews: (document.querySelector('#acrCustomerReviewText') || {}).textContent?.trim() || '',
  features: Array.from(document.querySelectorAll('#feature-bullets li span.a-list-item')).map(el => el.textContent.trim()).slice(0, 5),
  availability: (document.querySelector('#availability span') || {}).textContent?.trim() || '',
  bestSeller: !!document.querySelector('.badge-link') || !!document.querySelector('[data-badge="best-seller"]'),
  answeredQuestions: (document.querySelector('#askATFLink') || {}).textContent?.trim() || ''
})
