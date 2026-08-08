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
  return new Promise((resolve, reject) => {
    my.chooseFileFromDisk({
      success: (result) => resolve([{
        path: result.apFilePath,
        name: result.fileName || '报告.pdf',
        kind: 'pdf',
      }]),
      fail: () => reject(new Error('未选择 PDF')),
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
