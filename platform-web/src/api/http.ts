import axios, { AxiosError } from 'axios'
import { ElMessage } from 'element-plus'
import { getAccessToken, logout } from '@/auth/oauth'

/** 后端统一响应包装 ResponseData<T> */
export interface ApiResponse<T = unknown> {
  code: number
  msg: string
  data: T
}

const SUCCESS_CODE = 200
const UNLOGIN_CODE = 4003

const http = axios.create({
  baseURL: '/api',
  timeout: 120_000, // 集群新增含连通性探测，放宽超时
})

http.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

function redirectToLogin(): void {
  logout()
  window.location.href = '/login'
}

http.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResponse | undefined
    // 非 ResponseData 结构（理论上不会）直接透传
    if (!body || typeof body !== 'object' || !('code' in body)) {
      return response.data
    }
    if (body.code === UNLOGIN_CODE) {
      redirectToLogin()
      return Promise.reject(new Error(body.msg))
    }
    if (body.code !== SUCCESS_CODE) {
      ElMessage.error(body.msg || '请求失败')
      return Promise.reject(new Error(body.msg || `错误码 ${body.code}`))
    }
    // 成功：直接返回 data，调用方拿到即业务数据
    return body.data as never
  },
  (error: AxiosError<ApiResponse>) => {
    const status = error.response?.status
    const body = error.response?.data
    // 401 = 未登录/令牌失效 → 跳登录
    if (status === 401) {
      redirectToLogin()
      return Promise.reject(error)
    }
    // 403 且带 ResponseData body = 已登录但无权限（如非管理员访问管理端）→ 只提示，不当作登录失效
    if (status === 403 && body?.code !== undefined) {
      ElMessage.error(body.msg || '权限不足')
      return Promise.reject(error)
    }
    if (status === 403) {
      redirectToLogin()
      return Promise.reject(error)
    }
    const msg = body?.msg || error.message || '网络错误'
    ElMessage.error(msg)
    return Promise.reject(error)
  },
)

export default http
