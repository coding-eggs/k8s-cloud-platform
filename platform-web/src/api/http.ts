import axios, { AxiosError } from 'axios'
import { ElMessage } from 'element-plus'
import { getAccessToken, logout } from '@/auth/oauth'

/** 后端统一响应包装 ResponseData<T> */
export interface ApiResponse<T = unknown> {
  code: number
  msg: string
  data: T
}

declare module 'axios' {
  export interface AxiosRequestConfig {
    /**
     * 403 时不弹全局错误提示。
     *
     * <p>用于「按权限位已知会失败的可选拉取」——只读候选值、可降级的下拉等：这类请求失败是<b>预期内</b>的，
     * 弹错只会让用户看到与自己操作无关的红条（租户管理员进工作负载编辑器弹出"无权访问角色列表"即此类）。
     *
     * <p><b>使用规则</b>（见 docs/development/frontend-permission-conventions.md）：
     * 只有①纯只读候选值、②调用方自带降级路径、③失败不影响任何写操作的请求才允许带；
     * 写操作、主数据（列表/详情）与任何"失败即必须让用户知道"的请求<b>一律不允许</b>。
     * 更优先的做法是调用前按权限位短路（不发这个请求），本标志只是第二层收敛。
     */
    silent403?: boolean
  }
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

/** 会话已不可用 → 清会话（含服务端失效）后回登录页 */
function redirectToLogin(): void {
  // logout 现在要打一次 platform-auth 的 /session/logout：会话已失效时该调用是幂等 no-op，
  // 网络失败也不会阻断本地清理（见 oauth.ts）。跳到登录页放在 finally，保证一定发生。
  void logout().finally(() => {
    window.location.href = '/login'
  })
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
      if (!response.config?.silent403) ElMessage.error(body.msg || '请求失败')
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
      if (!error.config?.silent403) ElMessage.error(body.msg || '权限不足')
      return Promise.reject(error)
    }
    if (status === 403) {
      redirectToLogin()
      return Promise.reject(error)
    }
    const msg = body?.msg || error.message || '网络错误'
    if (!error.config?.silent403) ElMessage.error(msg)
    return Promise.reject(error)
  },
)

export default http
