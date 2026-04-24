const API_BASE_URL = 'http://localhost:8080/api';
const TOKEN_KEY = 'aiReceptionistToken';

export const tokenStorage = {
  getToken: () => localStorage.getItem(TOKEN_KEY),
  setToken: (token) => localStorage.setItem(TOKEN_KEY, token),
  clearToken: () => localStorage.removeItem(TOKEN_KEY),
};

async function request(path, options = {}) {
  const token = tokenStorage.getToken();
  const headers = {
    'Content-Type': 'application/json',
    ...(options.headers || {}),
  };

  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers,
  });

  const data = await response.json().catch(() => ({}));

  if (!response.ok) {
    throw new Error(data?.message || 'Request failed');
  }

  return data;
}

export const api = {
  login: (payload) => request('/auth/login', { method: 'POST', body: JSON.stringify(payload) }),
  getKnowledge: (tenantId) => request(`/tenants/${tenantId}/knowledge`),
  createFaq: (tenantId, payload) =>
    request(`/tenants/${tenantId}/knowledge`, { method: 'POST', body: JSON.stringify(payload) }),
  sendChat: (payload) => request('/chat', { method: 'POST', body: JSON.stringify(payload) }),
  getLeads: (tenantId) => request(`/tenants/${tenantId}/leads`),
};

export { API_BASE_URL };
