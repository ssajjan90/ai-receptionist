import { useState } from 'react';
import { api } from '../api';

const initialForm = {
  tenantId: '',
  customerName: '',
  customerPhone: '',
  message: '',
};

export default function ChatTestPage() {
  const [form, setForm] = useState(initialForm);
  const [loading, setLoading] = useState(false);
  const [response, setResponse] = useState(null);
  const [error, setError] = useState('');

  const onChange = (event) => {
    setForm((prev) => ({ ...prev, [event.target.name]: event.target.value }));
  };

  const onSubmit = async (event) => {
    event.preventDefault();
    setLoading(true);
    setError('');
    setResponse(null);
    try {
      const result = await api.sendChat({
        tenantId: Number(form.tenantId),
        customerName: form.customerName,
        customerPhone: form.customerPhone,
        message: form.message,
        channel: 'CHAT',
      });
      setResponse(result?.data);
    } catch (e) {
      setError(e.message || 'Chat request failed');
    } finally {
      setLoading(false);
    }
  };

  return (
    <section className="card">
      <h2>Chat Test</h2>
      <form onSubmit={onSubmit} className="stack">
        <label>
          Tenant ID
          <input name="tenantId" value={form.tenantId} onChange={onChange} required />
        </label>
        <label>
          Customer Name
          <input name="customerName" value={form.customerName} onChange={onChange} required />
        </label>
        <label>
          Customer Phone
          <input name="customerPhone" value={form.customerPhone} onChange={onChange} required />
        </label>
        <label>
          Message
          <textarea name="message" value={form.message} onChange={onChange} rows={4} required />
        </label>
        <button className="btn" type="submit" disabled={loading}>
          {loading ? 'Sending...' : 'Send Message'}
        </button>
      </form>

      {error && <p className="error">{error}</p>}
      {response && (
        <div className="result-box">
          <h3>Response</h3>
          <pre>{JSON.stringify(response, null, 2)}</pre>
        </div>
      )}
    </section>
  );
}
