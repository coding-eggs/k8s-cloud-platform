/**
 * 高级日期格式化函数
 * @param {Date|string|number} dateInput - 日期输入
 * @param {string} format - 格式字符串，支持：
 *   - 年份: YYYY(4位), YY(2位)
 *   - 月份: MM(2位), M(1-2位), MMM(Jan-Dec), MMMM(January-December)
 *   - 日期: DD(2位), D(1-2位)
 *   - 星期: ddd(Mon-Sun), dddd(Monday-Sunday)
 *   - 小时: HH(24小时制2位), H(24小时制), hh(12小时制2位), h(12小时制)
 *   - 分钟: mm(2位), m(1-2位)
 *   - 秒: ss(2位), s(1-2位)
 *   - 毫秒: SSS(3位)
 *   - AM/PM: A(大写), a(小写)
 *   - 时区: Z(时区偏移)
 * @returns {string} 格式化后的字符串
 */
const formatDate = (dateInput, format = 'YYYY-MM-DD HH:mm:ss') => {
    if (!dateInput) return ''

    const date = parseDate(dateInput)
    if (!date || isNaN(date.getTime())) {
        console.warn('无效的日期:', dateInput)
        return typeof dateInput === 'string' ? dateInput : ''
    }

    return formatDateInternal(date, format)
}

/**
 * 解析日期输入为 Date 对象
 */
const parseDate = (input) => {
    if (input instanceof Date) return input
    if (typeof input === 'number') return new Date(input)
    if (typeof input !== 'string') return null

    // 常见格式处理
    let str = input.trim()

    // 处理 ISO 8601 格式
    if (str.includes('T')) {
        return new Date(str)
    }

    // 处理常见的数据库格式
    if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(str)) {
        return new Date(str.replace(' ', 'T') + 'Z')
    }

    // 处理时间戳字符串
    if (/^\d{13}$/.test(str)) {
        return new Date(parseInt(str, 10))
    }
    if (/^\d{10}$/.test(str)) {
        return new Date(parseInt(str, 10) * 1000)
    }

    // 默认尝试
    return new Date(str)
}

/**
 * 内部格式化函数
 */
const formatDateInternal = (date, format) => {
    const year = date.getFullYear()
    const month = date.getMonth() + 1
    const day = date.getDate()
    const hours = date.getHours()
    const minutes = date.getMinutes()
    const seconds = date.getSeconds()
    const milliseconds = date.getMilliseconds()
    const dayOfWeek = date.getDay()
    const hours12 = hours % 12 || 12
    const ampm = hours >= 12 ? 'PM' : 'AM'

    // 星期几名称
    const dayNamesShort = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
    const dayNamesFull = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']
    const monthNamesShort = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
    const monthNamesFull = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December']

    // 构建替换映射
    const replacements = {
        // 年份
        YYYY: year.toString(),
        YY: year.toString().slice(-2),

        // 月份
        MMMM: monthNamesFull[date.getMonth()],
        MMM: monthNamesShort[date.getMonth()],
        MM: month.toString().padStart(2, '0'),
        M: month.toString(),

        // 日期
        DD: day.toString().padStart(2, '0'),
        D: day.toString(),

        // 星期
        dddd: dayNamesFull[dayOfWeek],
        ddd: dayNamesShort[dayOfWeek],

        // 小时
        HH: hours.toString().padStart(2, '0'),
        H: hours.toString(),
        hh: hours12.toString().padStart(2, '0'),
        h: hours12.toString(),

        // 分钟
        mm: minutes.toString().padStart(2, '0'),
        m: minutes.toString(),

        // 秒
        ss: seconds.toString().padStart(2, '0'),
        s: seconds.toString(),

        // 毫秒
        SSS: milliseconds.toString().padStart(3, '0'),

        // AM/PM
        A: ampm,
        a: ampm.toLowerCase(),

        // 时区偏移
        Z: () => {
            const offset = -date.getTimezoneOffset()
            const sign = offset >= 0 ? '+' : '-'
            const hoursOffset = Math.floor(Math.abs(offset) / 60)
            const minutesOffset = Math.abs(offset) % 60
            return `${sign}${hoursOffset.toString().padStart(2, '0')}:${minutesOffset.toString().padStart(2, '0')}`
        }
    }

    // 执行替换
    let result = format
    for (const [token, replacement] of Object.entries(replacements)) {
        if (format.includes(token)) {
            const value = typeof replacement === 'function' ? replacement() : replacement
            result = result.replace(new RegExp(token, 'g'), value)
        }
    }

    return result
}