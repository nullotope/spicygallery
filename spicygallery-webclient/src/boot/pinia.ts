import { defineBoot } from '#q-app/wrappers';
import { createPinia } from 'pinia';

/**
 * Installs Pinia. Without this boot the FIRST useStore() call in the real app
 * throws "Cannot read properties of undefined (reading '_s')" and blanks the
 * whole page — component tests can't catch it because they mount with an
 * explicit createPinia() plugin (this exact bug shipped in slice 1).
 */
export default defineBoot(({ app }) => {
  app.use(createPinia());
});
