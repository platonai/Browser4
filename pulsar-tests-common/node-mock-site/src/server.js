import {createApp} from './app.js';

const port = process.env.PORT ? parseInt(process.env.PORT, 10) : 39090;
(async () => {
  const app = await createApp();
  app.listen(port, () => {
    // eslint-disable-next-line no-console
    console.log(`MockSiteApplication (Node) listening on http://localhost:${port}`);
  });
})();
