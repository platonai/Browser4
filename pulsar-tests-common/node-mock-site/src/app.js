import express from 'express';
import morgan from 'morgan';
import {readAmazonHome, readAmazonProduct} from './resources.js';
import {createEcommerceRouter, initEcommerceData} from './ec.js';

export async function createApp(options = {}) {
  const app = express();
  const loggerFormat = options.loggerFormat || (process.env.NODE_ENV === 'production' ? 'combined' : 'dev');
  app.use(morgan(loggerFormat));

  // Initialize ecommerce data once (ignore errors but log)
  await initEcommerceData();

  // Root
  app.get('/', (_req, res) => {
    res.type('text/plain').send('Welcome! This site is used for internal test.');
  });

  app.get('/hello', (_req, res) => {
    res.type('text/plain').send('Hello, World!');
  });

  app.get('/text', (_req, res) => {
    res.type('text/plain').send('Hello, World! This is a plain text.');
  });

  app.get('/csv', (_req, res) => {
    res.type('text/csv').send(['1,2,3,4,5,6,7','a,b,c,d,e,f,g','1,2,3,4,5,6,7','a,b,c,d,e,f,g'].join('\n'));
  });

  app.get('/json', (_req, res) => {
    res.json({message: 'Hello, World! This is a json.'});
  });

  app.get('/robots.txt', (_req, res) => {
    const robots = `User-agent: *\nDisallow: /exec/obidos/account-access-login\nDisallow: /exec/obidos/change-style\nDisallow: /exec/obidos/flex-sign-in\nDisallow: /exec/obidos/handle-buy-box\nDisallow: /exec/obidos/tg/cm/member/\nDisallow: /gp/aw/help/id=sss\nDisallow: /gp/cart\nDisallow: /gp/flex\nDisallow: /gp/product/e-mail-friend\nDisallow: /gp/product/product-availability\nDisallow: /gp/product/rate-this-item\nDisallow: /gp/sign-in\nDisallow: /gp/reader\nDisallow: /gp/sitbv3/reader`;
    res.type('text/plain').send(robots);
  });

  app.get('/amazon/home.htm', async (_req, res) => {
    try {
      const html = await readAmazonHome();
      res.type('text/html').send(html);
    } catch (e) {
      res.status(500).type('text/plain').send('Failed to load amazon/home.htm: ' + e.message);
    }
  });

  app.get('/amazon/product.htm', async (_req, res) => {
    try {
      const html = await readAmazonProduct();
      res.type('text/html').send(html);
    } catch (e) {
      const fallback = `<!doctype html><html><head><title>Mock Product</title></head><body><h1>Mock Product</h1><p>Original product file missing. Reason: ${e.message}</p></body></html>`;
      res.status(200).type('text/html').send(fallback);
    }
  });

  // Ecommerce mock routes (/ec/*)
  app.use('/ec', createEcommerceRouter());

  // 404 handler
  app.use((req, res) => {
    res.status(404).json({error: 'Not Found', path: req.path});
  });

  return app;
}

