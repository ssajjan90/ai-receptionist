import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { tokenStorage } from '../api';

const navItems = [
  { to: '/dashboard', label: 'Dashboard' },
  { to: '/knowledge-base', label: 'Knowledge Base' },
  { to: '/chat-test', label: 'Chat Test' },
  { to: '/leads', label: 'Leads' },
];

export default function Layout() {
  const navigate = useNavigate();

  const handleLogout = () => {
    tokenStorage.clearToken();
    navigate('/login');
  };

  return (
    <div className="app-shell">
      <header className="topbar">
        <h1>AI Receptionist Test Frontend</h1>
        <button className="btn secondary" onClick={handleLogout}>
          Logout
        </button>
      </header>
      <nav className="tabs">
        {navItems.map((item) => (
          <NavLink key={item.to} to={item.to} className={({ isActive }) => (isActive ? 'tab active' : 'tab')}>
            {item.label}
          </NavLink>
        ))}
      </nav>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}
