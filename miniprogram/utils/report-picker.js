const CONSENT_KEY = 'report_ai_consent_v1'

function confirmConsent() {
  const accepted = my.getStorageSync({ key: CONSENT_KEY }).data
  if (accepted) return Promise.resolve()
  return new Promise((resolve, reject) => {
    my.confirm({
      title: '报告解读说明',
      content: '请确认你有权上传该报告。内容将发送至火山方舟多模态模型处理，请先遮盖姓名、身份证号、手机号和就诊卡号；报告图片原图会留存于你的历史会话中供回看。仅供参考，不替代医生诊断。',
      confirmButtonText: '同意并继续',
      cancelButtonText: '取消',
      success: (result) => {
        if (!result.confirm) return reject(new Error('已取消'))
        my.setStorageSync({ key: CONSENT_KEY, data: true })
        resolve()
      },
    })
  })
}

function chooseImages(sourceType) {
  return new Promise((resolve, reject) => {
    my.chooseImage({
      count: sourceType === 'camera' ? 1 : 5,
      sourceType: [sourceType],
      success: (result) => {
        const paths = result.apFilePaths || result.tempFilePaths || []
        my.compressImage({
          apFilePaths: paths,
          compressLevel: 2,
          success: (compressed) => {
            const compressedPaths = compressed.apFilePaths || []
            if (!compressedPaths.length) return reject(new Error('图片压缩失败，请重新选择'))
            resolve(compressedPaths.map((path, index) => ({
              path,
              name: `报告图片 ${index + 1}`,
              kind: 'image',
            })))
          },
          fail: () => reject(new Error('图片压缩失败，请重新选择')),
        })
      },
      fail: () => reject(new Error('未选择图片')),
    })
  })
}

function choosePdf() {
  // chooseFileFromDisk 基础库 2.9.77 才新增；低版本客户端调用会直接抛错，先给可读提示
  if (!my.canIUse('chooseFileFromDisk')) {
    return Promise.reject(new Error('当前客户端不支持选择PDF文件，请升级支付宝客户端'))
  }
  return new Promise((resolve, reject) => {
    let settled = false
    // 原生文件选择器弹出会把小程序切到后台（onAppHide）；开发者工具模拟器未实现该能力时
    // 无任何 UI、也不触发 onAppHide，表现为点击后静默无响应。以 onAppHide 区分
    // 「用户正在选文件」与「选择器根本没弹出」，超时未弹出则给出明确提示。
    let pickerOpened = false
    const onHide = () => { pickerOpened = true }
    if (my.onAppHide) my.onAppHide(onHide)
    const watchdog = setTimeout(() => {
      if (settled || pickerOpened) return
      // 只提示不中断：个别真机选择器不切后台，不能误 reject 打断真实选文件流程
      my.showToast({ content: '模拟器暂不支持选择PDF，请在真机预览中上传', type: 'none', duration: 3500 })
    }, 3000)
    const settle = (callback) => (arg) => {
      if (settled) return
      settled = true
      clearTimeout(watchdog)
      if (my.offAppHide) my.offAppHide(onHide)
      callback(arg)
    }
    my.chooseFileFromDisk({
      success: settle((result) => resolve([{
        path: result.apFilePath,
        name: result.fileName || '报告.pdf',
        kind: 'pdf',
      }])),
      fail: settle(() => reject(new Error('未选择 PDF'))),
    })
  })
}

async function chooseReport(index) {
  await confirmConsent()
  if (index === 0) return chooseImages('camera')
  if (index === 1) return chooseImages('album')
  return choosePdf()
}

module.exports = { chooseReport }
