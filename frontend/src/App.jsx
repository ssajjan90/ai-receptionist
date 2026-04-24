import { Navigate, Route, Routes } from 'react-router-dom';
import Layout from './components/Layout';
import DashboardPage from './pages/DashboardPage';
import LoginPage from './pages/LoginPage';
import KnowledgeBasePage from './pages/KnowledgeBasePage';
import ChatTestPage from './pages/ChatTestPage';
import LeadsPage from './pages/LeadsPage';
import { tokenStorage } from './api';

function ProtectedRoute({ children }) {
  const token = tokenStorage.getToken();
  if (!token) return <Navigate to="/login" replace />;
  return children;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/"
        element={
          <ProtectedRoute>
            <Layout />
          </ProtectedRoute>
        }
      >
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="dashboard" element={<DashboardPage />} />
        <Route path="knowledge-base" element={<KnowledgeBasePage />} />
        <Route path="chat-test" element={<ChatTestPage />} />
        <Route path="leads" element={<LeadsPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  );
}
