import { API_BASE_URL } from '../api';

export default function DashboardPage() {
  return (
    <section className="card">
      <h2>Dashboard</h2>
      <p>This lightweight UI helps test your AI Receptionist backend APIs.</p>
      <ul>
        <li>API Base URL: <code>{API_BASE_URL}</code></li>
        <li>Use the tabs to create FAQs, run chat tests, and check leads.</li>
        <li>Authorization header is automatically attached when token exists.</li>
      </ul>
    </section>
  );
}
