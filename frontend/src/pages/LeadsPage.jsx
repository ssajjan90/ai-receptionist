import { useState } from 'react';
import { api } from '../api';

export default function LeadsPage() {
  const [tenantId, setTenantId] = useState('');
  const [leads, setLeads] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const loadLeads = async () => {
    if (!tenantId) return;
    setLoading(true);
    setError('');
    try {
      const result = await api.getLeads(tenantId);
      setLeads(result?.data || []);
    } catch (e) {
      setError(e.message || 'Failed to load leads');
    } finally {
      setLoading(false);
    }
  };

  return (
    <section className="card">
      <h2>Leads</h2>
      <div className="inline-controls">
        <input value={tenantId} onChange={(e) => setTenantId(e.target.value)} placeholder="Tenant ID" />
        <button className="btn secondary" onClick={loadLeads} disabled={!tenantId || loading}>
          {loading ? 'Loading...' : 'Load Leads'}
        </button>
      </div>
      {error && <p className="error">{error}</p>}
      <ul className="list">
        {leads.map((lead) => (
          <li key={lead.id}>
            <div className="row">
              <strong>{lead.customerName}</strong>
              <span className="pill">{lead.status}</span>
            </div>
            <p>{lead.phone || lead.email || 'No contact provided'}</p>
            <p className="muted">{lead.requirement || 'No requirement provided'}</p>
          </li>
        ))}
        {!leads.length && <li className="muted">No leads loaded yet.</li>}
      </ul>
    </section>
  );
}
