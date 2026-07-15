import React, { useState, FormEvent } from 'react';

interface LoginPageProps {
  onLoginSuccess: (token: string, user: { id: string; username: string; bankInstitution?: string }) => void;
}

type Mode = 'login' | 'register';

/** 工商银行机构列表 */
const BANK_LIST = [
  { value: '', label: '请选择所属机构' },
  { value: '总行营业部', label: '总行营业部' },
  { value: '北京分行', label: '北京分行' },
  { value: '上海分行', label: '上海分行' },
  { value: '天津分行', label: '天津分行' },
  { value: '重庆分行', label: '重庆分行' },
  { value: '广东分行', label: '广东分行' },
  { value: '深圳分行', label: '深圳分行' },
  { value: '浙江分行', label: '浙江分行' },
  { value: '江苏分行', label: '江苏分行' },
  { value: '山东分行', label: '山东分行' },
  { value: '四川分行', label: '四川分行' },
  { value: '湖北分行', label: '湖北分行' },
  { value: '湖南分行', label: '湖南分行' },
  { value: '福建分行', label: '福建分行' },
  { value: '河南分行', label: '河南分行' },
  { value: '河北分行', label: '河北分行' },
  { value: '辽宁分行', label: '辽宁分行' },
  { value: '陕西分行', label: '陕西分行' },
  { value: '安徽分行', label: '安徽分行' },
  { value: '江西分行', label: '江西分行' },
];

const LoginPage: React.FC<LoginPageProps> = ({ onLoginSuccess }) => {
  const [mode, setMode] = useState<Mode>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [bankInstitution, setBankInstitution] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError('');

    if (!username.trim() || !password.trim()) {
      setError('请输入用户名和密码');
      return;
    }

    if (mode === 'register' && password !== confirmPassword) {
      setError('两次输入的密码不一致');
      return;
    }

    if (!bankInstitution) {
      setError('请选择银行机构');
      return;
    }

    if (password.length < 6) {
      setError('密码长度不能少于6位');
      return;
    }

    setLoading(true);

    try {
      const endpoint =
        mode === 'login' ? '/api/auth/login' : '/api/auth/register';
      const res = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: username.trim(), password, bankInstitution }),
      });

      const data = await res.json();

      if (!res.ok) {
        setError(data.detail || '操作失败，请重试');
        return;
      }

      // 存储 Token
      localStorage.setItem('auth_token', data.accessToken);
      localStorage.setItem('user_info', JSON.stringify(data.user));

      onLoginSuccess(data.accessToken, data.user);
    } catch {
      setError('网络错误，请检查后端服务是否启动');
    } finally {
      setLoading(false);
    }
  };

  const toggleMode = () => {
    setMode(mode === 'login' ? 'register' : 'login');
    setError('');
    setConfirmPassword('');
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-blue-50 via-white to-blue-100">
      <div className="w-full max-w-md">
        {/* 头部 */}
        <div className="text-center mb-8">
          <div className="w-16 h-16 rounded-2xl bg-gradient-to-br from-blue-500 to-blue-700 flex items-center justify-center mx-auto mb-4 shadow-lg">
            <svg
              className="w-8 h-8 text-white"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z"
              />
            </svg>
          </div>
          <h1 className="text-2xl font-bold text-gray-800">
            智能尽调智能体
          </h1>
          <p className="text-gray-500 mt-2 text-sm">
            {mode === 'login' ? '登录您的账户' : '创建新账户'}
          </p>
        </div>

        {/* 表单卡片 */}
        <div className="bg-white rounded-2xl shadow-xl p-8">
          <form onSubmit={handleSubmit} className="space-y-5">
            {/* 用户名 */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">
                用户名
              </label>
              <input
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="请输入用户名"
                autoFocus
                className="w-full px-4 py-2.5 rounded-xl border border-gray-300 bg-gray-50
                           text-sm text-gray-800 placeholder-gray-400
                           focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent
                           transition-all duration-200"
              />
            </div>

            {/* 密码 */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">
                密码
              </label>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="请输入密码（至少6位）"
                className="w-full px-4 py-2.5 rounded-xl border border-gray-300 bg-gray-50
                           text-sm text-gray-800 placeholder-gray-400
                           focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent
                           transition-all duration-200"
              />
            </div>

            {/* 确认密码（仅注册） */}
            {mode === 'register' && (
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">
                  确认密码
                </label>
                <input
                  type="password"
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  placeholder="请再次输入密码"
                  className="w-full px-4 py-2.5 rounded-xl border border-gray-300 bg-gray-50
                             text-sm text-gray-800 placeholder-gray-400
                             focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent
                             transition-all duration-200"
                />
              </div>
            )}

            {/* 银行机构 */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">
                银行机构
              </label>
              <select
                value={bankInstitution}
                onChange={(e) => setBankInstitution(e.target.value)}
                className="w-full px-4 py-2.5 rounded-xl border border-gray-300 bg-gray-50
                           text-sm text-gray-800
                           focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent
                           transition-all duration-200 appearance-none
                           bg-[url('data:image/svg+xml;charset=UTF-8,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20width%3D%2212%22%20height%3D%2212%22%20viewBox%3D%220%200%2012%2012%22%3E%3Cpath%20fill%3D%22%239CA3AF%22%20d%3D%22M6%208L1%203h10z%22%2F%3E%3C%2Fsvg%3E')]
                           bg-[length:12px_12px] bg-[right_16px_center] bg-no-repeat"
              >
                {BANK_LIST.map((bank) => (
                  <option key={bank.value} value={bank.value} disabled={bank.value === ''}>
                    {bank.label}
                  </option>
                ))}
              </select>
            </div>

            {/* 错误提示 */}
            {error && (
              <div className="bg-red-50 border border-red-200 text-red-700 text-sm rounded-xl px-4 py-3">
                {error}
              </div>
            )}

            {/* 提交按钮 */}
            <button
              type="submit"
              disabled={loading}
              className="w-full py-2.5 bg-blue-600 hover:bg-blue-700 disabled:opacity-50
                         disabled:cursor-not-allowed text-white rounded-xl font-medium
                         transition-all duration-200 active:scale-[0.98] flex items-center justify-center gap-2"
            >
              {loading ? (
                <>
                  <div className="animate-spin rounded-full h-4 w-4 border-2 border-white border-t-transparent" />
                  处理中...
                </>
              ) : mode === 'login' ? (
                '登录'
              ) : (
                '注册'
              )}
            </button>
          </form>
        </div>

        {/* 切换登录/注册 */}
        <p className="text-center text-sm text-gray-500 mt-6">
          {mode === 'login' ? '还没有账户？' : '已有账户？'}
          <button
            onClick={toggleMode}
            className="ml-1 text-blue-600 hover:text-blue-700 font-medium"
          >
            {mode === 'login' ? '立即注册' : '去登录'}
          </button>
        </p>
      </div>
    </div>
  );
};

export default LoginPage;
