import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, tokenStorage } from '../api';

export default function LoginPage() {
  const navigate = useNavigate();
  const [form, setForm] = useState({ email: '', password: '' });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const onChange = (event) => {
    setForm((prev) => ({ ...prev, [event.target.name]: event.target.value }));
  };

  const onSubmit = async (event) => {
    event.preventDefault();
    setError('');
    setLoading(true);
    try {
      const result = await api.login(form);
      const token = result?.data?.token;
      if (!token) throw new Error('Token missing in response');
      tokenStorage.setToken(token);
      navigate('/dashboard');
    } catch (e) {
      setError(e.message || 'Login failed');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="centered-page">
      <form className="card" onSubmit={onSubmit}>
        <h2>Login</h2>
        <p className="helper">Use your backend user credentials.</p>
        <label>
          Email
          <input type="email" name="email" value={form.email} onChange={onChange} required />
        </label>
        <label>
          Password
          <input type="password" name="password" value={form.password} onChange={onChange} required />
        </label>
        {error && <p className="error">{error}</p>}
        <button className="btn" disabled={loading} type="submit">
          {loading ? 'Signing in...' : 'Sign in'}
        </button>
      </form>
    </div>
  );
}
