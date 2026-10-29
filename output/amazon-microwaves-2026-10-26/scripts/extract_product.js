(() => {
  const q = s => document.querySelector(s);
  const nAll = s => document.querySelectorAll(s).length;
  const txt = s => { const e = q(s); return e ? e.innerText.replace(/\s+/g, ' ').trim() : ''; };
  const abs = u => { try { return new URL(u, location.origin).href; } catch (e) { return u; } };
  const stars = v => { const m = (v || '').match(/([0-9.]+)\s*out of 5/); return m ? m[1] : ''; };
  const clean = s => (s || '').replace(/\s+/g, ' ').trim();

  const asin = (q('input#ASIN') || {}).value
    || (location.pathname.match(/\/(?:dp|gp\/product)\/([A-Z0-9]{10})/) || [])[1] || '';

  // ---------- key/value tables -------------------------------------------
  const kvFrom = root => {
    const out = {};
    if (!root) return out;
    root.querySelectorAll('tr').forEach(tr => {
      const cells = Array.from(tr.querySelectorAll('th, td')).map(c => clean(c.innerText));
      if (cells.length >= 2) {
        const k = cells[0].replace(/:$/, '');
        const v = cells[cells.length - 1];
        if (k && v && !out[k]) out[k] = v;
      }
    });
    root.querySelectorAll('li').forEach(li => {
      const t = clean(li.innerText);
      const i = t.indexOf(':');
      if (i > 0 && i < 60) {
        const k = t.slice(0, i).trim(); const v = t.slice(i + 1).trim();
        if (k && v && !out[k]) out[k] = v;
      }
    });
    return out;
  };

  const detailsRoot = q('#productDetails_feature_div') || q('#prodDetails') || q('#detailBullets_feature_div');
  const kv = kvFrom(detailsRoot);
  const kvOverview = kvFrom(q('#productOverview_feature_div'));

  const bodyText = document.body ? document.body.innerText : '';
  const bestSellersRank = (() => {
    const m = bodyText.match(/Best Sellers Rank:?[\s\S]{0,400}?(?=\n[A-Z][a-z]+ [A-Z]|\n\n|$)/);
    return m ? clean(m[0]).slice(0, 600) : '';
  })();
  const rankPair = (() => {
    const tr = Array.from(document.querySelectorAll('tr, li')).find(r => /Best Sellers Rank/i.test(clean(r.innerText)));
    if (tr) return { html: tr.innerHTML.replace(/\s+/g, ' ').trim(), text: clean(tr.innerText) };
    return { html: '', text: '' };
  })();

  // ---------- variations --------------------------------------------------
  const htmlAll = document.documentElement.innerHTML;
  const mVar = htmlAll.match(/num_total_variations"\s*:\s*"?(\d+)/);
  const mPar = htmlAll.match(/parentAsin"\s*:\s*"([A-Z0-9]{10})"/);
  const jsSeg = (() => {
    const s = Array.from(document.querySelectorAll('script:not([src])')).find(x => x.textContent.indexOf('num_total_variations') >= 0 || x.textContent.indexOf('asinVariationValues') >= 0);
    return s ? s.textContent.replace(/\s+/g, ' ').trim().slice(0, 1500) : '';
  })();

  // ---------- images ------------------------------------------------------
  const li = q('#landingImage');
  const imgSrc = li ? (li.getAttribute('data-old-hires') || li.getAttribute('src') || '') : '';
  let dyn = '';
  if (li) {
    const raw = li.getAttribute('data-a-dynamic-image');
    if (raw) { try { dyn = JSON.stringify(Object.keys(JSON.parse(raw))); } catch (e) { dyn = raw.slice(0, 1200); } }
  }
  const galleryUrls = Array.from(document.querySelectorAll('#altImages li img'))
    .map(i => i.getAttribute('src') || '')
    .filter(u => u && u.indexOf('play-icon') < 0 && u.indexOf('grey-pixel') < 0 && u.indexOf('transparent-pixel') < 0)
    .map(u => u.replace(/\._[A-Z0-9,_]+_\./, '._AC_SL1200_.'));

  // ---------- A+ / description -------------------------------------------
  const aplusRoot = q('#aplus, #aplus_feature_div');
  const aplusImgs = Array.from(document.querySelectorAll('#aplus img, #aplus_feature_div img, #aplusBrandStory_feature_div img'))
    .map(i => i.getAttribute('data-src') || i.getAttribute('src') || '')
    .filter(u => u && u.indexOf('grey-pixel') < 0 && u.indexOf('transparent-pixel') < 0).slice(0, 12);
  const aplusVideos = Array.from(document.querySelectorAll('#aplus video, #aplus [data-video-url]'))
    .map(v => v.getAttribute('data-video-url') || v.getAttribute('src') || '').filter(Boolean).slice(0, 8);
  const descText = clean((q('#productDescription') || {}).innerText || '')
    || clean((q('#aplus') || {}).innerText || '').replace(/^Product description\s*/i, '').slice(0, 4000);

  // ---------- price -------------------------------------------------------
  const money = s => { const m = (s || '').match(/\$[\d,]+(?:\.\d{2})?/); return m ? m[0] : ''; };
  const num = s => { const v = parseFloat((s || '').replace(/[^0-9.]/g, '')); return isNaN(v) ? 0 : v; };
  const apexTxt = clean((q('#apex_desktop') || {}).innerText || '');
  const price = money(clean((q('#corePrice_feature_div') || {}).innerText)) || money(apexTxt) || clean((q('#corePrice_feature_div') || {}).innerText);
  const buyboxPrice = money(clean((q('#buybox') || {}).innerText)) || price;
  const listPriceRaw = (apexTxt.match(/List Price:\s*(\$[\d,]+(?:\.\d{2})?)/) || [])[1]
    || money(clean((q('#listPrice') || {}).innerText)) || '';
  const pctOff = (apexTxt.match(/-(\d+)%/) || [])[1] || '';
  const yousave = (() => {
    if (listPriceRaw && price && num(listPriceRaw) > num(price)) {
      const diff = (num(listPriceRaw) - num(price)).toFixed(2);
      return '$' + diff + (pctOff ? ' (' + pctOff + '%)' : '');
    }
    return pctOff ? '-' + pctOff + '%' : '';
  })();

  // ---------- rating ------------------------------------------------------
  const hist = {};
  document.querySelectorAll('a[aria-label*="percent of reviews have"], #histogramTable a').forEach(a => {
    const lab = a.getAttribute('aria-label') || clean(a.innerText);
    const m = lab.match(/(\d+)\s*percent of reviews have\s*(\d)\s*star/i);
    if (m) hist['score' + m[2] + 'percent'] = m[1] + '%';
  });
  if (!Object.keys(hist).length) {
    const ht = clean((q('#histogramTable') || {}).innerText);
    (ht.match(/(\d)\s*star\s*(\d+)%/g) || []).forEach(s => {
      const m = s.match(/(\d)\s*star\s*(\d+)%/);
      if (m) hist['score' + m[1] + 'percent'] = m[2] + '%';
    });
  }
  // histogram is emitted 5,4,3,2,1 without star labels -> fall back to order
  if (!Object.keys(hist).length) {
    const pcts = (clean((q('#histogramTable') || {}).innerText).match(/(\d+)%/g) || []).map(x => x);
    ['5', '4', '3', '2', '1'].forEach((s, i) => { if (pcts[i]) hist['score' + s + 'percent'] = pcts[i]; });
  }

  // ---------- seller / delivery ------------------------------------------
  const soldBy = (bodyText.match(/Sold by:\s*([^\n]{1,60})/) || [])[1] || clean((q('#sellerProfileTriggerId') || {}).innerText || '');
  const shipsFrom = (bodyText.match(/Ships from:\s*([^\n]{1,60})/) || [])[1] || clean((q('#fulfillerInfoFeature_feature_div') || {}).innerText || '');
  const sellerLink = Array.from(document.querySelectorAll('a[href*="seller="], a[href*="/sp?"]'))
    .map(a => a.getAttribute('href') || '').find(h => /seller=[A-Z0-9]{5,}/.test(h)) || '';
  const sellerID = (sellerLink.match(/seller=([A-Z0-9]{5,})/) || [])[1] || '';
  const otherSellers = (bodyText.match(/New & Used \((\d+)\)/) || [])[1]
    || (bodyText.match(/\((\d+)\)\s*from/) || [])[1]
    || (bodyText.match(/(\d+)\s+other sellers/) || [])[1] || '';
  const questions = (bodyText.match(/(\d+)\s+answered questions?/i) || [])[0] || '';
  const coupon = clean((q('#promoPriceBlockMessage') || {}).innerText || '') || (bodyText.match(/Apply \$?[\d.]+ coupon[^\n]{0,40}/i) || [])[0] || '';

  // ---------- bought together --------------------------------------------
  const simsBox = Array.from(document.querySelectorAll('div[id*="sims-simsContainer"], div[id*="sims-consolidated"], div#sims_fbt, div[id*="sims-productBundle"]'))
    .find(d => /Frequently bought together|bought together/i.test(d.innerText));
  const simsText = simsBox ? clean(simsBox.innerText).slice(0, 2000) : '';
  const simsImgs = simsBox ? Array.from(simsBox.querySelectorAll('img')).map(i => i.getAttribute('src') || '').filter(Boolean).slice(0, 6) : [];
  const simsMeta = simsBox ? Array.from(simsBox.querySelectorAll('[data-a-state]')).map(e => (e.getAttribute('data-a-state') || '').slice(0, 200)).filter(Boolean).slice(0, 4) : [];

  // ---------- breadcrumb --------------------------------------------------
  const crumbAnchors = Array.from(document.querySelectorAll('#wayfinding-breadcrumbs_feature_div a, #productCategory a'));
  const crumbLinks = crumbAnchors.map(a => abs(a.getAttribute('href') || ''));
  const crumbNames = crumbAnchors.map(a => clean(a.innerText));

  // ---------- badges ------------------------------------------------------
  const badgeTxt = [txt('#zeitgeistBadge_feature_div'), txt('#acBadge_feature_div'), clean((q('#apex_desktop') || {}).innerText || '').slice(0, 500)].join(' ');
  const isBS = /#?1 Best Seller|Best Seller\b/i.test(badgeTxt) && !!q('#zeitgeistBadge_feature_div, [aria-label*="Best Seller"]');
  const isAC = /Amazon's Choice/i.test(badgeTxt) || !!q('#acBadge_feature_div, [aria-label*="Amazon\'s Choice"]');

  return JSON.stringify({
    url: location.origin + '/dp/' + asin,
    finalUrl: location.href,
    asin: asin,
    title: txt('#productTitle'),
    brand: clean((q('#bylineInfo') || {}).innerText || ''),
    brandHref: (() => { const a = q('#bylineInfo'); return a ? abs(a.getAttribute('href') || '') : ''; })(),
    overviewbullets: Object.entries(kvOverview).map(([k, v]) => k + ': ' + v).join(' | '),
    featurebullets: txt('#feature-bullets'),
    detailbullets: JSON.stringify(kv).slice(0, 6000),
    desc: descText,
    rank: rankPair.html,
    rank2: rankPair.text,
    bestSellerRankPage: bestSellersRank,
    pasin: mPar ? mPar[1] : '',
    totalvariations: mVar ? mVar[1] : '',
    jsVariables: jsSeg,
    soldby: clean(soldBy),
    shipsfrom: clean(shipsFrom),
    sellerID: sellerID,
    sellerHref: sellerLink ? abs(sellerLink) : '',
    feedbackurl: sellerLink ? abs(sellerLink) : '',
    quantity: (() => { const o = Array.from(document.querySelectorAll('#quantity option')).map(x => x.textContent.trim()); return o.length ? '(' + o.join(', ') + ')' : ''; })(),
    globaldeliverto: txt('#glow-ingress-line2'),
    deliverto: txt('#mir-layout-DELIVERY_BLOCK'),
    price: price,
    buyboxprice: buyboxPrice,
    listprice: listPriceRaw,
    instock: txt('#availability').slice(0, 60),
    stock_status: (clean((q('#availability') || {}).innerText || '').split(/\n/)[0] || '').slice(0, 60),
    isaddcart: q('#add-to-cart-button') ? 1 : 0,
    isbuy: q('#buy-now-button') ? 1 : 0,
    isac: isAC ? 1 : 0,
    isbs: isBS ? 1 : 0,
    iscoupon: coupon ? 1 : 0,
    couponText: clean(coupon),
    isprime: (() => {
      const scope = q('#apex_desktop') || q('#buybox') || document.body;
      return scope.querySelector('i.a-icon-prime, .a-icon-prime, #prime-badge') ? 1 : 0;
    })(),
    withdeal: txt('#dealBadge_feature_div'),
    yousave: yousave,
    img: li ? li.outerHTML.replace(/\s+/g, ' ').trim().slice(0, 1200) : '',
    imgsrc: imgSrc,
    dynamicimgsrcs: dyn,
    gallery: '(' + galleryUrls.join(', ') + ')',
    detailimgs: '(' + aplusImgs.join(', ') + ')',
    detailvideos: '(' + aplusVideos.concat(Array.from(document.querySelectorAll('#vseVideosContainer a, .vse-player-container a')).map(a => a.getAttribute('href') || '').filter(Boolean)).slice(0, 10).join(', ') + ')',
    isa: aplusRoot ? 1 : 0,
    boughttogethermetadata: '(' + simsMeta.join(', ') + ')',
    boughttogetherimgs: '(' + simsImgs.join(', ') + ')',
    boughttogether: simsText,
    othersellernum: clean(otherSellers),
    qanum: clean(questions),
    score: stars((q('#acrPopover') || {}).title || '') || stars(clean((q('#acrPopover') || {}).innerText || '')),
    reviews: (clean((q('#acrCustomerReviewText') || {}).innerText || '').match(/[\d,]+/) || [''])[0],
    starnum: (clean((q('#acrCustomerReviewText') || {}).innerText || '').match(/[\d,]+/) || [''])[0],
    score5percent: hist.score5percent || '',
    score4percent: hist.score4percent || '',
    score3percent: hist.score3percent || '',
    score2percent: hist.score2percent || '',
    score1percent: hist.score1percent || '',
    scoresbyfeature: '',
    weight: kv['Item Weight'] || kv['Weight'] || '',
    volume: kv['Item Dimensions D x W x H'] || kv['Product Dimensions'] || '',
    reviewsmention: '',
    onsaletime: kv['Date First Available'] || '',
    marketplaceID: 'ATVPDKIKX0DER',
    reviewsurl: location.origin + '/product-reviews/' + asin,
    sellsameurl: location.origin + '/gp/offer-listing/' + asin,
    categorypath: '(' + crumbLinks.join(', ') + ')',
    categoryname: crumbNames[crumbNames.length - 1] || '',
    categorynamelevel: crumbNames.join('|'),
    referer: document.referrer || '',
    numchars: bodyText.length,
    numlinks: nAll('a[href]'),
    numimgs: nAll('img'),
    height: document.documentElement.scrollHeight
  });
})()
