(() => {
  const q = s => document.querySelector(s);
  const info = s => { const e = q(s); return e ? { found: true, len: e.innerText.length, txt: e.innerText.replace(/\s+/g, ' ').slice(0, 220) } : { found: false }; };
  const sels = ['#productOverview_feature_div', '#productDetails_feature_div', '#productDetails_detailBullets_sections1',
    '#productDetails_techSpec_section_1', '#detailBullets_feature_div', '#prodDetails', '#tabular-buybox',
    '#merchantInfoFeature_feature_div', '#sellerProfileTriggerId', '#fulfillerInfoFeature_feature_div',
    '#olp_feature_div', '#availability', '#histogramTable', '#askATFLink', '#askATF_feature_div',
    '#sims-consolidated-1', '#sims_fbt', '#aplus', '#aplus_feature_div', '#productDescription',
    '#productDescription_feature_div', '#acrPopover', '#acrCustomerReviewText', '#zeitgeistBadge_feature_div',
    '#acBadge_feature_div', '#promoPriceBlockMessage', '#corePrice_feature_div', '#apex_desktop', '#dealBadge_feature_div'];
  const out = {}; sels.forEach(s => out[s] = info(s));

  const body = document.body.innerText;
  out.__bestSellerRankLines = (body.match(/[^\n]*Best Sellers Rank[^\n]*/gi) || []).slice(0, 3);
  out.__soldByLines = (body.match(/[^\n]*(Sold by|Ships from)[^\n]*/gi) || []).slice(0, 6);
  out.__otherSellers = (body.match(/[^\n]*(other sellers|Other Sellers|more buying choices|used|options)[^\n]*/gi) || []).slice(0, 8);
  out.__questions = (body.match(/[^\n]*question[^\n]*/gi) || []).slice(0, 5);
  out.__histogramHtml = (q('#histogramTable') ? q('#histogramTable').innerHTML.replace(/\s+/g, ' ').slice(0, 900) : '');
  out.__detailRows = Array.from(document.querySelectorAll('#detailBullets_feature_div li, #productDetails_detailBullets_sections1 tr, #productDetails_techSpec_section_1 tr'))
    .map(r => r.innerText.replace(/\s+/g, ' ').trim()).slice(0, 40);
  out.__aplusImgs = Array.from(document.querySelectorAll('#aplus img, #aplus_feature_div img')).map(i => (i.getAttribute('src') || i.getAttribute('data-src') || '').slice(0, 100)).slice(0, 6);
  out.__simsText = Array.from(document.querySelectorAll('[id*="sims"], [data-a-component*="sims"]')).map(e => e.id).slice(0, 10);
  out.__primeBadges = Array.from(document.querySelectorAll('[class*="prime"], [aria-label*="Prime"]')).map(e => (e.className || '').toString().slice(0, 60)).slice(0, 6);
  return JSON.stringify(out, null, 1);
})()
