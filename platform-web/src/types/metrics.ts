/** 监控指标类型 —— 镜像 platform-api metrics.dto（字段名逐字一致） */

/** 单个采样点：ts = unix 秒，value = 原始数值（单位见 MetricSeriesResponse.unit） */
export interface MetricPoint {
  ts: number
  value: number
}

/** 一条序列：legend = 图例（用量 / RX / TX / 读 / 写），points = 时间序列点 */
export interface MetricSeries {
  legend: string
  points: MetricPoint[]
}

/** 一次指标查询的返回：unit = 单位（核 / 字节 / 字节/秒），series = 1~2 条线 */
export interface MetricSeriesResponse {
  unit: string
  series: MetricSeries[]
}
