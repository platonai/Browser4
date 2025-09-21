import request from 'supertest';
import {createApp} from '../src/app.js';

let app;
beforeAll(async () => {
  app = await createApp({loggerFormat: 'tiny'});
});

describe('MockSiteApplication Node basic endpoints', () => {
  test('GET / returns welcome text', async () => {
    const res = await request(app).get('/');
    expect(res.status).toBe(200);
    expect(res.text).toContain('Welcome!');
  });

  test('GET /hello returns hello world', async () => {
    const res = await request(app).get('/hello');
    expect(res.status).toBe(200);
    expect(res.text).toBe('Hello, World!');
  });

  test('GET /text content-type text/plain', async () => {
    const res = await request(app).get('/text');
    expect(res.headers['content-type']).toMatch(/text\/plain/);
    expect(res.text).toContain('plain text');
  });

  test('GET /csv returns CSV', async () => {
    const res = await request(app).get('/csv');
    expect(res.headers['content-type']).toMatch(/text\/csv/);
    expect(res.text.split('\n').length).toBeGreaterThanOrEqual(4);
  });

  test('GET /json returns json body', async () => {
    const res = await request(app).get('/json');
    expect(res.headers['content-type']).toMatch(/application\/json/);
    expect(res.body.message).toMatch(/Hello, World!/);
  });

  test('GET /robots.txt returns robots rules', async () => {
    const res = await request(app).get('/robots.txt');
    expect(res.status).toBe(200);
    expect(res.text).toContain('User-agent: *');
  });

  test('GET /amazon/home.htm returns html or error html', async () => {
    const res = await request(app).get('/amazon/home.htm');
    expect(res.headers['content-type']).toMatch(/text\/html|text\/plain/);
  });

  test('GET /amazon/product.htm returns html fallback if file missing', async () => {
    const res = await request(app).get('/amazon/product.htm');
    expect(res.headers['content-type']).toMatch(/text\/html/);
    expect(res.text).toMatch(/Mock Product|<!doctype/i);
  });

  test('GET /not-exist returns 404 json', async () => {
    const res = await request(app).get('/not-exist');
    expect(res.status).toBe(404);
    expect(res.body.error).toBe('Not Found');
  });
});

