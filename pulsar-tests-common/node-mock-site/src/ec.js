import express from 'express';
import {promises as fs} from 'fs';
import path from 'path';
import {fileURLToPath} from 'url';
import {load as loadHtml} from 'cheerio';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

let ecData = null; // {meta, categories, products}
let ecIndexes = null; // {categoriesById, productsById, productsByCategory}
let templates = null; // {category, product}
let initPromise = null;

const DATA_RELATIVE = 'pulsar-tests-common/src/main/resources/static/generated/mock-amazon/data/products.json';
const CATEGORY_TEMPLATE_RELATIVE = 'pulsar-tests-common/src/main/resources/static/generated/mock-amazon/list/index.html';
const PRODUCT_TEMPLATE_RELATIVE = 'pulsar-tests-common/src/main/resources/static/generated/mock-amazon/product/index.html';

async function resolveRepoRoot() {
  let dir = path.resolve(__dirname, '../../..');
  for (let i = 0; i < 6; i++) {
    try {
      const hasPom = await fs.stat(path.join(dir, 'pom.xml')).catch(() => null);
      if (hasPom) return dir;
    } catch { /* ignored */ }
    dir = path.dirname(dir);
  }
  return path.resolve(__dirname, '../../..');
}

async function loadJSONOnce() {
  if (ecData) return ecData;
  const root = await resolveRepoRoot();
  const dataPath = path.join(root, DATA_RELATIVE);
  const raw = await fs.readFile(dataPath, 'utf8');
  const data = JSON.parse(raw);
  // Basic validation
  if (!Array.isArray(data.categories) || data.categories.length !== 20) {
    throw new Error('Expected exactly 20 categories');
  }
  if (!Array.isArray(data.products) || data.products.length < 100) {
    throw new Error('Expected >= 100 products');
  }
  ecData = data;
  return ecData;
}

function buildIndexes(data) {
  const categoriesById = new Map();
  for (const c of data.categories) categoriesById.set(c.id, c);
  const productsById = new Map();
  const productsByCategory = new Map();
  for (const p of data.products) {
    productsById.set(p.id, p);
    if (!categoriesById.has(p.categoryId)) {
      // Skip inconsistent product silently
      continue;
    }
    if (!productsByCategory.has(p.categoryId)) productsByCategory.set(p.categoryId, []);
    productsByCategory.get(p.categoryId).push(p);
  }
  // Deterministic ordering by product id for stable pages
  for (const arr of productsByCategory.values()) arr.sort((a,b)=> a.id.localeCompare(b.id));
  return {categoriesById, productsById, productsByCategory};
}

async function loadTemplatesOnce() {
  if (templates) return templates;
  const root = await resolveRepoRoot();
  const catPath = path.join(root, CATEGORY_TEMPLATE_RELATIVE);
  const prodPath = path.join(root, PRODUCT_TEMPLATE_RELATIVE);
  const [catHtml, prodHtml] = await Promise.all([
    fs.readFile(catPath, 'utf8'),
    fs.readFile(prodPath, 'utf8')
  ]);
  templates = {category: catHtml, product: prodHtml};
  return templates;
}

export async function initEcommerceData() {
  if (initPromise) return initPromise;
  initPromise = (async () => {
    const data = await loadJSONOnce();
    ecIndexes = buildIndexes(data);
    await loadTemplatesOnce();
    // eslint-disable-next-line no-console
    console.log(`[ec] Loaded categories=${data.categories.length} products=${data.products.length} seed=${data.meta?.seed}`);
  })().catch(e => {
    // eslint-disable-next-line no-console
    console.error('[ec] Initialization failed', e);
  });
  return initPromise;
}

function formatPrice(price, currency) {
  if (typeof price !== 'number') return '';
  const symbol = currency === 'USD' ? '$' : (currency || '');
  return symbol + price.toFixed(2);
}

function renderError(code, message) {
  return `<!doctype html><html><head><meta charset='utf-8'><title>Error ${code}</title></head><body><div id="error-page" class="error-code-${code}"><h1>Error ${code}</h1><p>${message}</p></div></body></html>`;
}

function renderHome(data) {
  const items = data.categories.map(c => `<li class="category-item" data-category-id="${c.id}"><a id="cat-link-${c.id}" href="/ec/b?node=${c.id}">${c.name}</a></li>`).join('\n');
  return `<!doctype html><html><head><meta charset='utf-8'><title>Mock EC Home</title><link rel="canonical" href="/ec/"/></head><body><nav><ul id="category-list">${items}</ul></nav></body></html>`;
}

function buildProductCard(p) {
  const price = formatPrice(p.price, p.currency);
  const badges = (p.badges||[]).map(b=>`<span class="badge">${b}</span>`).join(' ');
  return `<article class="product-card" id="product-${p.id}" data-product-id="${p.id}" data-category-id="${p.categoryId}">
    <h2 class="product-title"><a href="/ec/dp/${p.id}">${p.name}</a></h2>
    <div class="product-meta">
      <span class="product-price" data-product-id="${p.id}">${price}</span>
      <span class="product-rating" data-rating="${p.rating}">${p.rating?.toFixed(1) ?? ''}</span>
      <span class="product-rating-count" data-rating-count="${p.ratingCount}">${p.ratingCount ?? ''}</span>
      <div class="product-badges">${badges}</div>
    </div>
  </article>`;
}

function renderCategoryPage(category, products, templateHtml) {
  const $ = loadHtml(templateHtml);
  // Adjust title
  $('title').text(`Category: ${category.name}`);
  // Set container id
  if (!$('#category-page').length) {
    $('body').prepend(`<div id="category-page" data-category-id="${category.id}" style="display:none;"></div>`);
  } else {
    $('#category-page').attr('data-category-id', category.id);
  }
  // Inject products into #product-list
  const cards = products.map(buildProductCard).join('\n');
  const list = $('#product-list');
  if (list.length) {
    list.html(cards);
  } else {
    $('body').append(`<div id="product-list">${cards}</div>`);
  }
  return $.html();
}

function renderProductPage(product, category, templateHtml) {
  const $ = loadHtml(templateHtml);
  $('title').text(`Product: ${product.name}`);
  // Tag root
  const main = $('main.main-content');
  if (main.length) {
    main.attr('id', 'product-page');
    main.attr('data-product-id', product.id);
  }
  // Product title
  const titleEl = $('.product-title a, .product-title');
  if (titleEl.length) titleEl.first().text(product.name);
  // Price section
  const priceSection = $('#priceSection');
  priceSection.html(`<div class="price-box"><span id="product-price">${formatPrice(product.price, product.currency)}</span></div>`);
  // Rating
  if ($('.product-rating').length) {
    $('.product-rating').first().append(`<span id="product-rating" data-rating="${product.rating}">${product.rating?.toFixed(1)}</span>`);
  } else {
    main.prepend(`<div class="product-rating"><span id="product-rating" data-rating="${product.rating}">${product.rating?.toFixed(1)}</span></div>`);
  }
  // Category link
  main.prepend(`<div><a id="product-category-link" href="/ec/b?node=${category.id}">${category.name}</a></div>`);
  // Features
  const features = (product.features||[]).map(f=>`<li>${f}</li>`).join('');
  main.append(`<ul id="product-features">${features}</ul>`);
  // Specs
  const specsRows = Object.entries(product.specs||{}).map(([k,v])=>`<tr><th>${k}</th><td>${v}</td></tr>`).join('');
  main.append(`<table id="product-specs">${specsRows}</table>`);
  // Badges injection
  if ($('.product-badges').length) {
    const badgesHtml = (product.badges||[]).map(b=>`<span class="badge">${b}</span>`).join(' ');
    $('.product-badges').html(badgesHtml);
  }
  // Alt attributes for main images
  $('img').each((_, el) => {
    const src = $(el).attr('src')||'';
    if (/unsplash|placeholder|product/i.test(src)) {
      $(el).attr('alt', product.name);
    }
  });
  return $.html();
}

export function createEcommerceRouter() {
  const r = express.Router();

  // Inline placeholder image for deterministic asset serving
  const placeholderPngBase64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9YqF2q8AAAAASUVORK5CYII='; // 1x1 transparent
  r.get('/static/img/placeholder.png', (req, res) => {
    const buf = Buffer.from(placeholderPngBase64, 'base64');
    res.set('Cache-Control','public, max-age=31536000, immutable');
    res.type('image/png').send(buf);
  });

  // Meta info (counts) for diagnostics
  r.get('/meta', (req, res) => {
    if (!ecData) return res.status(500).json({error:'Ecommerce not initialized'});
    res.json({categories: ecData.categories.length, products: ecData.products.length, seed: ecData.meta?.seed});
  });

  // Serve static assets (placeholder image) from local public folder if present
  const staticDir = path.join(__dirname, 'public');
  r.use('/static', express.static(staticDir, {fallthrough: true}));

  r.get('/', (_req, res) => {
    if (!ecData) return res.status(500).send(renderError(500,'Ecommerce not initialized'));
    res.type('text/html').send(renderHome(ecData));
  });

  r.get('/b', (req, res) => {
    if (!ecData) return res.status(500).send(renderError(500,'Ecommerce not initialized'));
    const node = req.query.node;
    if (!node) {
      console.warn('[ec] 400 /ec/b missing category parameter');
      return res.status(400).type('text/html').send(renderError(400,'Missing category parameter'));
    }
    const {categoriesById, productsByCategory} = ecIndexes || {};
    const category = categoriesById?.get(node);
    if (!category) {
      console.warn(`[ec] 404 /ec/b category not found node=${node}`);
      return res.status(404).type('text/html').send(renderError(404,'Category not found'));
    }
    const products = productsByCategory?.get(node) || [];
    const html = renderCategoryPage(category, products, templates.category);
    res.type('text/html').send(html);
  });

  r.get('/dp/:productId', (req, res) => {
    if (!ecData) return res.status(500).send(renderError(500,'Ecommerce not initialized'));
    const id = req.params.productId;
    const {productsById, categoriesById} = ecIndexes || {};
    const product = productsById?.get(id);
    if (!product) {
      console.warn(`[ec] 404 /ec/dp product not found id=${id}`);
      return res.status(404).type('text/html').send(renderError(404,'Product not found'));
    }
    const category = categoriesById?.get(product.categoryId);
    if (!category) {
      console.warn(`[ec] 404 /ec/dp inconsistent category id=${id}`);
      return res.status(404).type('text/html').send(renderError(404,'Product not found'));
    }
    const html = renderProductPage(product, category, templates.product);
    res.type('text/html').send(html);
  });

  // Fallback inside /ec
  r.use((req, res) => {
    console.warn(`[ec] 404 ${req.originalUrl}`);
    return res.status(404).type('text/html').send(renderError(404,'Not found'));
  });

  return r;
}
