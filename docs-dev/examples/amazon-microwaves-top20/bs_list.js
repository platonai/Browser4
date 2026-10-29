(() => {
  const nodes = Array.from(document.querySelectorAll('#gridItemRoot'));
  const out = [];
  let i = 0;
  for (const el of nodes) {
    i++;
    const rankEl = el.querySelector('.zg-bdg-text');
    const linkEl = el.querySelector('a.a-link-normal[href*="/dp/"], a[href*="/dp/"]');
    const titleEl = el.querySelector('div[class*="line-clamp"], .p13n-sc-truncate-desktop-type2, .p13n-sc-truncate');
    const imgEl = el.querySelector('img');
    const ratingEl = el.querySelector('.a-icon-alt');
    const priceEl = el.querySelector('span[class*="p13n-sc-price"], ._cDEzb_p13n-sc-price_3mJ9Z');
    const reviewsEl = el.querySelector('.a-size-small');
    const href = linkEl ? linkEl.getAttribute('href') : '';
    out.push({
      order: i,
      rank: rankEl ? rankEl.textContent.trim() : '',
      asin: (href.match(/\/(?:dp|gp\/product)\/([A-Z0-9]{10})/) || [])[1] || '',
      href: href,
      title: titleEl ? titleEl.textContent.trim() : (linkEl ? linkEl.textContent.trim() : ''),
      listImg: imgEl ? imgEl.getAttribute('src') : '',
      listRating: ratingEl ? ratingEl.textContent.trim() : '',
      listPrice: priceEl ? priceEl.textContent.trim() : '',
      listReviews: reviewsEl ? reviewsEl.textContent.trim() : ''
    });
  }
  return JSON.stringify({ pageUrl: location.href, pageTitle: document.title, count: i, items: out });
})()
