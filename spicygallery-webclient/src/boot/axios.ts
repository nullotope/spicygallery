import { boot } from 'quasar/wrappers';
import { api } from './api';

export default boot(({ app }) => {
  app.config.globalProperties.$api = api;
});

export { api };
