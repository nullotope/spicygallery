<template>
  <q-layout view="hHh lpR fFf">
    <q-header elevated class="app-header">
      <q-toolbar>
        <q-toolbar-title class="row items-center q-gutter-x-sm">
          <router-link to="/" class="app-title-link" data-testid="app-title">
            <span class="text-h6">🌶 SpicyGallery</span>
          </router-link>
        </q-toolbar-title>
        <q-tabs no-caps inline-label>
          <q-route-tab to="/" label="Entries" />
          <q-route-tab to="/pools" label="Pools" />
          <q-route-tab to="/settings" label="Settings" />
        </q-tabs>
        <ImportControls class="q-ml-sm" />
        <ThemeToggle class="q-ml-sm" />
      </q-toolbar>
    </q-header>
    <q-page-container>
      <router-view />
    </q-page-container>
  </q-layout>
</template>

<script setup lang="ts">
import ThemeToggle from 'src/components/ThemeToggle.vue';
import ImportControls from 'src/components/ImportControls.vue';
</script>

<style scoped>
.app-title-link {
  color: inherit;
  text-decoration: none;
}
</style>
