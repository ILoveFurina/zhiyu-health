/* ============================================
   智愈答辩展示页 - 交互脚本
   ============================================ */

// ============ 顶部导航高亮 ============
const navLinks = document.querySelectorAll('.topbar nav a');
const sections = document.querySelectorAll('section[id]');

window.addEventListener('scroll', () => {
  let current = '';
  sections.forEach(sec => {
    const top = sec.offsetTop - 100;
    if (window.scrollY >= top) current = sec.id;
  });
  navLinks.forEach(a => {
    a.classList.toggle('active', a.getAttribute('href') === '#' + current);
  });
});

// ============ C 端页面切换 ============
const cTabs = document.querySelectorAll('.c-tab');
const cPages = document.querySelectorAll('.c-page');
cTabs.forEach(tab => {
  tab.addEventListener('click', () => {
    const target = tab.dataset.ctab;
    cTabs.forEach(t => t.classList.toggle('active', t === tab));
    cPages.forEach(p => p.classList.toggle('hidden', p.id !== 'cpage-' + target));
    // 滚动到顶部
    document.querySelector('.phone-screen').scrollTop = 0;
  });
});

// ============ B 端页面切换（tabs + 侧边栏联动） ============
const bTabs = document.querySelectorAll('.b-tab');
const bPages = document.querySelectorAll('.b-page');
const bMenuItems = document.querySelectorAll('.menu-item');

function switchBPage(target) {
  bPages.forEach(p => p.classList.toggle('hidden', p.id !== 'bpage-' + target));
  bTabs.forEach(t => t.classList.toggle('active', t.dataset.btab === target));
  bMenuItems.forEach(m => m.classList.toggle('active', m.dataset.bmenu === target));
  // 更新地址栏
  document.querySelector('.b-chrome .url').textContent = 'localhost:8000/' + target;
}

bTabs.forEach(tab => {
  tab.addEventListener('click', () => switchBPage(tab.dataset.btab));
});
bMenuItems.forEach(item => {
  item.addEventListener('click', () => {
    if (item.dataset.bmenu) switchBPage(item.dataset.bmenu);
  });
});

// ============ B 端接诊抽屉 ============
function openDrawer() {
  document.getElementById('drawerMask').classList.remove('hidden');
  document.getElementById('bDrawer').classList.remove('hidden');
}
function closeDrawer() {
  document.getElementById('drawerMask').classList.add('hidden');
  document.getElementById('bDrawer').classList.add('hidden');
}

// ============ 打字机效果（AI 诊室首条 AI 消息） ============
// 进入页面时对首条 AI 气泡做一次打字机动画，体现 SSE 流式体感
window.addEventListener('load', () => {
  const aiBubble = document.querySelector('#cpage-chat .bubble.ai');
  if (!aiBubble) return;
  const fullText = aiBubble.textContent.replace('▍', '').trim();
  aiBubble.innerHTML = '<span class="typed"></span><span class="cursor">▍</span>';
  const typed = aiBubble.querySelector('.typed');
  let i = 0;
  const timer = setInterval(() => {
    if (i < fullText.length) {
      typed.textContent += fullText[i];
      i++;
    } else {
      clearInterval(timer);
    }
  }, 40);
});

// ============ 平滑滚动（导航点击） ============
navLinks.forEach(a => {
  a.addEventListener('click', e => {
    const href = a.getAttribute('href');
    if (href && href.startsWith('#')) {
      e.preventDefault();
      const el = document.querySelector(href);
      if (el) window.scrollTo({ top: el.offsetTop - 56, behavior: 'smooth' });
    }
  });
});
