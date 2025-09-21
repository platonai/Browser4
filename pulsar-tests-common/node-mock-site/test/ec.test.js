import request from 'supertest';
import {createApp} from '../src/app.js';
import {JSDOM} from 'jsdom';

let app;
beforeAll(async () => {
  app = await createApp({loggerFormat: 'tiny'});
});

function parse(html){
  return new JSDOM(html).window.document;
}

function collectIds(doc) {
  const all = doc.querySelectorAll('[id]');
  const ids = new Map();
  for (const el of all) {
    const id = el.id;
    ids.set(id, (ids.get(id)||0)+1);
  }
  return ids;
}

describe('Ecommerce /ec mock site', () => {
  test('GET /ec/ home lists 20 categories', async () => {
    const res = await request(app).get('/ec/');
    expect(res.status).toBe(200);
    const doc = parse(res.text);
    const links = doc.querySelectorAll('#category-list a[id^="cat-link-"]');
    expect(links.length).toBe(20);
  });

  test('Category pages contain only products of that category', async () => {
    // Get categories first
    const home = await request(app).get('/ec/');
    const doc = parse(home.text);
    const catLink = doc.querySelector('#category-list a');
    expect(catLink).toBeTruthy();
    const href = catLink.getAttribute('href');
    const url = new URL('http://x'+href); // dummy origin
    const node = url.searchParams.get('node');
    const catRes = await request(app).get(`/ec/b?node=${node}`);
    expect(catRes.status).toBe(200);
    const catDoc = parse(catRes.text);
    const cards = [...catDoc.querySelectorAll('.product-card')];
    expect(cards.length).toBeGreaterThanOrEqual(5);
    for (const c of cards) {
      expect(c.getAttribute('data-category-id')).toBe(node);
    }
  });

  test('Product page has expected ids', async () => {
    // pick first product from first category
    const home = await request(app).get('/ec/');
    const doc = parse(home.text);
    const firstCatHref = doc.querySelector('#category-list a').getAttribute('href');
    const url = new URL('http://x'+firstCatHref);
    const node = url.searchParams.get('node');
    const catRes = await request(app).get(`/ec/b?node=${node}`);
    const catDoc = parse(catRes.text);
    const prodHref = catDoc.querySelector('.product-card a').getAttribute('href');
    const prodRes = await request(app).get(prodHref);
    expect(prodRes.status).toBe(200);
    const prodDoc = parse(prodRes.text);
    const root = prodDoc.querySelector('#product-page');
    expect(root).toBeTruthy();
    const pid = root.getAttribute('data-product-id');
    expect(pid).toBeTruthy();
    expect(prodDoc.querySelector('#product-price')).toBeTruthy();
    expect(prodDoc.querySelector('#product-features')).toBeTruthy();
    expect(prodDoc.querySelector('#product-specs')).toBeTruthy();
  });

  test('Missing category param returns 400', async () => {
    const res = await request(app).get('/ec/b');
    expect(res.status).toBe(400);
    expect(res.text).toMatch(/Missing category parameter/);
  });

  test('Unknown category returns 404', async () => {
    const res = await request(app).get('/ec/b?node=NOPE');
    expect(res.status).toBe(404);
  });

  test('Unknown product returns 404', async () => {
    const res = await request(app).get('/ec/dp/DOESNOTEXIST');
    expect(res.status).toBe(404);
  });

  test('Prices show two decimals on category page', async () => {
    const home = await request(app).get('/ec/');
    const doc = parse(home.text);
    const firstCatHref = doc.querySelector('#category-list a').getAttribute('href');
    const url = new URL('http://x'+firstCatHref);
    const node = url.searchParams.get('node');
    const catRes = await request(app).get(`/ec/b?node=${node}`);
    const catDoc = parse(catRes.text);
    const prices = [...catDoc.querySelectorAll('.product-price')].map(e=>e.textContent.trim());
    expect(prices.length).toBeGreaterThan(0);
    for (const p of prices) {
      expect(p).toMatch(/\$\d+\.\d{2}$/); // enforce two decimals
    }
  });

  test('No duplicate IDs on product page', async () => {
    const home = await request(app).get('/ec/');
    const doc = parse(home.text);
    const firstCatHref = doc.querySelector('#category-list a').getAttribute('href');
    const url = new URL('http://x'+firstCatHref);
    const node = url.searchParams.get('node');
    const catRes = await request(app).get(`/ec/b?node=${node}`);
    const catDoc = parse(catRes.text);
    const prodHref = catDoc.querySelector('.product-card a').getAttribute('href');
    const prodRes = await request(app).get(prodHref);
    const prodDoc = parse(prodRes.text);
    const idMap = collectIds(prodDoc);
    for (const [id, count] of idMap.entries()) {
      expect(count).toBe(1);
    }
  });

  test('All products reachable and counts per category between 5 and 12', async () => {
    const metaRes = await request(app).get('/ec/meta');
    expect(metaRes.status).toBe(200);
    const meta = metaRes.body;
    expect(meta.categories).toBe(20);
    expect(meta.products).toBeGreaterThanOrEqual(100);

    const home = await request(app).get('/ec/');
    const doc = parse(home.text);
    const catLinks = [...doc.querySelectorAll('#category-list a')];
    const seen = new Set();
    for (const a of catLinks) {
      const href = a.getAttribute('href');
      const u = new URL('http://x'+href);
      const node = u.searchParams.get('node');
      const catRes = await request(app).get(`/ec/b?node=${node}`);
      expect(catRes.status).toBe(200);
      const catDoc = parse(catRes.text);
      const cards = [...catDoc.querySelectorAll('.product-card')];
      expect(cards.length).toBeGreaterThanOrEqual(5);
      expect(cards.length).toBeLessThanOrEqual(12);
      for (const c of cards) {
        const pid = c.getAttribute('data-product-id');
        seen.add(pid);
      }
    }
    expect(seen.size).toBe(meta.products);
  });
});
