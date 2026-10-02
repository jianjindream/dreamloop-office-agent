import { expect, test, type Page } from '@playwright/test'

const mockCommonApi = async (page: Page) => {
  await page.route('**/api/status', (route) => route.fulfill({ json: { rag_loaded: false, rag_mode: 'mock', tools_count: 0, llm_model: 'mock', embedding_model: 'mock', is_mock: true, infrastructure: {} } }))
  await page.route('**/api/tools', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/documents', (route) => route.fulfill({ json: [] }))
}

test.beforeEach(async ({ page }) => {
  await mockCommonApi(page)
})

test('navigation, theme and keyboard search work at each viewport', async ({ page }) => {
  await page.goto('/chat')
  await expect(page.getByRole('heading', { name: 'AI 对话' })).toBeVisible()
  await page.getByRole('button', { name: '切换主题' }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await expect(page.getByRole('heading', { name: 'AI 对话' })).toBeVisible()

  await page.keyboard.press('Control+k')
  await expect(page.getByRole('dialog', { name: '全局搜索' })).toBeVisible()
  await page.getByRole('textbox', { name: '搜索页面、对话和文档' }).fill('文档')
  await page.getByRole('button', { name: /AI 文档/ }).last().click()
  await expect(page).toHaveURL(/\/documents$/)
  await expect(page.getByRole('heading', { name: 'AI 文档' })).toBeVisible()

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1)
  expect(overflow).toBe(false)
  await page.keyboard.press('Tab')
  await expect(page.getByRole('link', { name: '跳转到主要内容' })).toBeAttached()
})

test('chat answer becomes an editable document draft', async ({ page }) => {
  await page.addInitScript(() => {
    const now = Date.now()
    localStorage.setItem('dreamloop-chat-sessions-v2', JSON.stringify([{ id: 'session-1', title: '产品方案', createdAt: now, updatedAt: now, messages: [
      { id: 'user-1', role: 'user', content: '写一份产品方案', createdAt: now, status: 'complete', execution: [], sources: [] },
      { id: 'assistant-1', role: 'assistant', content: '# 新产品方案\n\n项目目标', createdAt: now, status: 'complete', execution: [], sources: [] },
    ] }]))
    localStorage.setItem('dreamloop-current-session-v2', 'session-1')
  })
  await page.goto('/chat')
  await page.getByRole('button', { name: '将回答转为文档' }).click()
  await expect(page.getByRole('complementary', { name: '文档编辑画布' })).toBeVisible()
  await expect(page.getByRole('textbox', { name: '文档标题' })).toHaveValue('新产品方案')
  await expect(page.getByRole('textbox', { name: 'Markdown 文档正文' })).toHaveValue(/项目目标/)
  await expect(page.getByRole('button', { name: /已引用/ })).toBeVisible()
})

test('all workspace pages fit the viewport', async ({ page }) => {
  for (const path of ['/chat', '/knowledge', '/documents', '/tools', '/status', '/settings']) {
    await page.goto(path)
    await expect(page.locator('main')).toBeVisible()
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1)
    expect(overflow, `${path} should not overflow horizontally`).toBe(false)
  }
})

test('skip link and search dialog are keyboard accessible', async ({ page }) => {
  await page.goto('/chat')
  await expect(page.getByRole('heading', { name: 'AI 对话' })).toBeVisible()
  await page.keyboard.press('Tab')
  const skipLink = page.getByRole('link', { name: '跳转到主要内容' })
  await expect(skipLink).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.locator('#main-content')).toBeFocused()

  const searchButton = page.locator('button[aria-label="打开全局搜索"]:visible').first()
  await searchButton.click()
  await expect(page.getByRole('textbox', { name: '搜索页面、对话和文档' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog', { name: '全局搜索' })).toBeHidden()
  await expect(searchButton).toBeFocused()
})
