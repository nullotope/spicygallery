import type { RouteRecordRaw } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('pages/EntriesPage.vue'),
  },
  {
    path: '/entries/:id',
    component: () => import('pages/EntryPage.vue'),
    props: true,
  },
  {
    path: '/tags',
    component: () => import('pages/TagsPage.vue'),
  },
  {
    path: '/concepts',
    component: () => import('pages/ConceptsPage.vue'),
  },
  {
    path: '/concepts/:id',
    component: () => import('pages/ConceptWorkbenchPage.vue'),
    props: true,
  },
  {
    path: '/pools',
    component: () => import('pages/PoolsPage.vue'),
  },
  {
    path: '/pools/:id',
    component: () => import('pages/PoolPage.vue'),
    props: true,
  },
  {
    path: '/settings',
    component: () => import('pages/SettingsPage.vue'),
  },
  {
    path: '/:catchAll(.*)*',
    component: () => import('pages/EntriesPage.vue'),
  },
];

export default routes;
