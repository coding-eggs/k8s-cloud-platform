/**
 * Axios 统一封装 — 拦截器处理后端 ResponseData 和异常
 */

const api = axios.create({
    baseURL: '',
    timeout: 30000,
    withCredentials: true,
});

// 请求拦截器
api.interceptors.request.use(
    config => {
        return config;
    },
    error => Promise.reject(error)
);

// 响应拦截器 — 统一处理 ResponseData<T> 格式
api.interceptors.response.use(
    response => {
        const data = response.data;

        // Spring Security / OAuth2 直接返回非 JSON（如登录页）时透传
        if (response.request.responseType === '' && typeof data !== 'object' || data === null) {
            return Promise.resolve(response);
        }

        // ResponseData: { code, msg, data }
        if (data.code !== undefined && data.msg !== undefined) {
            const successCodes = [200];
            if (!successCodes.includes(data.code)) {
                ElementPlus.ElMessage.error(data.msg || '请求失败');
                return Promise.reject({ code: data.code, msg: data.msg });
            }
            // 成功时把 data 层剥掉，调用方直接拿到业务数据
            return Promise.resolve(data.data);
        }

        return Promise.resolve(data);
    },
    error => {
        const status = error.response?.status;
        let msg = '网络异常';

        switch (status) {
            case 401:
                msg = '未登录或登录已过期，请重新登录';
                ElementPlus.ElMessage.warning(msg);
                // 跳转到登录页（保留当前路径供登录后返回）
                const currentPath = window.location.pathname + window.location.search;
                if (!window.location.pathname.includes('/login.html')) {
                    window.location.href = '/login.html?redirect=' + encodeURIComponent(currentPath);
                }
                break;
            case 403:
                msg = '没有操作权限';
                ElementPlus.ElMessage.error(msg);
                break;
            case 404:
                msg = '请求的资源不存在';
                break;
            case 500:
                msg = error.response?.data?.msg || '服务器内部错误';
                ElementPlus.ElMessage.error(msg);
                break;
            default:
                if (!error.response) {
                    msg = '网络连接失败，请检查服务是否启动';
                } else {
                    msg = error.response.data?.msg || `请求失败 (${status})`;
                }
        }

        return Promise.reject({ code: status, msg });
    }
);

