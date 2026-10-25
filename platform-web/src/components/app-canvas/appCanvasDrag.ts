/**
 * 节点面板拖拽时写进 dataTransfer 的类型键。
 *
 * <p>面板（写入）和画布（读取）必须用同一个字符串 —— 拼错不会报错，只会表现为「拖过去没反应」。
 */
export const APP_NODE_MIME = 'application/x-app-node'
