import { useState } from 'react';
import { api } from '../api';

const initialForm = {
  tenantId: '',
  question: '',
  answer: '',
};

export default function KnowledgeBasePage() {
  const [tenantId, setTenantId] = useState('');
  const [faqForm, setFaqForm] = useState(initialForm);
  const [faqs, setFaqs] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const loadFaqs = async () => {
    if (!tenantId) return;
    setLoading(true);
    setError('');
    try {
      const result = await api.getKnowledge(tenantId);
      setFaqs(result?.data || []);
    } catch (e) {
      setError(e.message || 'Failed to load FAQs');
    } finally {
      setLoading(false);
    }
  };

  const onCreateFaq = async (event) => {
    event.preventDefault();
    setLoading(true);
    setError('');
    try {
      await api.createFaq(faqForm.tenantId, {
        type: 'FAQ',
        question: faqForm.question,
        answer: faqForm.answer,
        active: true,
      });
      setFaqForm((prev) => ({ ...initialForm, tenantId: prev.tenantId }));
      setTenantId(faqForm.tenantId);
      const result = await api.getKnowledge(faqForm.tenantId);
      setFaqs(result?.data || []);
    } catch (e) {
      setError(e.message || 'Failed to create FAQ');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="split-grid">
      <section className="card">
        <h2>Create FAQ</h2>
        <form onSubmit={onCreateFaq} className="stack">
          <label>
            Tenant ID
            <input
              name="tenantId"
              value={faqForm.tenantId}
              onChange={(e) => setFaqForm((prev) => ({ ...prev, tenantId: e.target.value }))}
              required
            />
          </label>
          <label>
            Question
            <input
              name="question"
              value={faqForm.question}
              onChange={(e) => setFaqForm((prev) => ({ ...prev, question: e.target.value }))}
              required
            />
          </label>
          <label>
            Answer
            <textarea
              name="answer"
              value={faqForm.answer}
              onChange={(e) => setFaqForm((prev) => ({ ...prev, answer: e.target.value }))}
              rows={4}
              required
            />
          </label>
          <button className="btn" type="submit" disabled={loading}>
            {loading ? 'Saving...' : 'Create FAQ'}
          </button>
        </form>
      </section>

      <section className="card">
        <h2>List FAQs</h2>
        <div className="inline-controls">
          <input
            placeholder="Tenant ID"
            value={tenantId}
            onChange={(e) => setTenantId(e.target.value)}
          />
          <button className="btn secondary" onClick={loadFaqs} disabled={!tenantId || loading}>
            {loading ? 'Loading...' : 'Load'}
          </button>
        </div>
        {error && <p className="error">{error}</p>}
        <ul className="list">
          {faqs.map((item) => (
            <li key={item.id}>
              <strong>{item.question}</strong>
              <p>{item.answer}</p>
            </li>
          ))}
          {!faqs.length && <li className="muted">No FAQs loaded yet.</li>}
        </ul>
      </section>
    </div>
  );
}
