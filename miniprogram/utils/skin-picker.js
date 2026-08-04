const CONSENT_KEY = 'skin_ai_consent_v1'

function confirmConsent() {
  const accepted = my.getStorageSync({ key: CONSENT_KEY }).data
  if (accepted) return Promise.resolve()
  return new Promise((resolve, reject) => {
    my.confirm({
      title: '皮肤拍照分析说明',
      content: '请确认你有权上传该照片。内容将发送至火山方舟多模态模型处理，照片原图会留存于你的历史会话中供回看。仅供参考，不替代医生诊断，异常请就医。',
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
              name: `皮肤照片 ${index + 1}`,
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

async function chooseSkinPhoto(index) {
  await confirmConsent()
  // 皮肤场景只接受图片（无 PDF），0=拍摄，1=相册
  return chooseImages(index === 0 ? 'camera' : 'album')
}

module.exports = { chooseSkinPhoto }
