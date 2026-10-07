/** Calico（B7）前端共享常量 */

/** IPReservation 池归属 label：所属地址池名。创建时写入；缺失才反查（CIDR 包含），反查后反写，避免每次重复推导 */
export const CALICO_POOL_LABEL = 'platform.k8s.io/ippool'

/** Calico 自动创建的默认池名（ns 未绑定池时的兜底候选来源；实际集群核实为 default-ipv4-ippool / default-ipv6-ippool） */
export const DEFAULT_IPV4_POOL = 'default-ipv4-ippool'
export const DEFAULT_IPV6_POOL = 'default-ipv6-ippool'
