export function load(key: string, fallback = '') {
  try { return localStorage.getItem(key) ?? fallback } catch { return fallback }
}
export function save(key: string, value: string) {
  try { localStorage.setItem(key, value) } catch { /* setting lasts this session */ }
}
