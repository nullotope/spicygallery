import axios from 'axios';

/** Shared API client — same origin; dev proxy handles /api → the server. */
export const api = axios.create({
  baseURL: '/',
  timeout: 30_000,
});

export default api;
