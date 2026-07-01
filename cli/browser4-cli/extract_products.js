JSON.stringify(
  Array.from(document.querySelectorAll('[data-component-type="s-search-result"]'))
    .map(card => {
      const titleEl = card.querySelector('h2 a, .s-line-clamp-4');
      const priceEl = card.querySelector('.a-price .a-offscreen');
      const ratingEl = card.querySelector('[aria-label*="stars"]');
      const imgEl = card.querySelector('img.s-image');
      const asin = card.getAttribute('data-asin') || '';
      return {
        title: titleEl ? titleEl.textContent.trim() : '',
        price: priceEl ? priceEl.textContent.trim() : '',
        rating: ratingEl ? ratingEl.getAttribute('aria-label') : '',
        url: titleEl ? titleEl.href : '',
        asin: asin,
        image: imgEl ? imgEl.src : ''
      };
    })
    .filter(p => p.title && p.price)
)
