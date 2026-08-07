/**
 * 科室号源卡默认选中日期（票 50）：所有可约医生最早可约日（earliest_bookable.date）
 * 的最小值；全部无号或缺数据时回退到日期条首日。实时下发与历史回放共用，
 * 保证同一卡数据两种入口首屏一致。
 */
function defaultSelectedDate(card) {
  if (!card || card.status !== 'ok') return ''
  const dates = (card.doctors || [])
    .filter((doctor) => doctor.bookable && doctor.earliest_bookable && doctor.earliest_bookable.date)
    .map((doctor) => doctor.earliest_bookable.date)
    .sort()
  if (dates.length > 0) return dates[0]
  return (card.days && card.days[0]) || ''
}

module.exports = { defaultSelectedDate }
