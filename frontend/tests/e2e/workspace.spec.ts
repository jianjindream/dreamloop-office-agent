import { expect, test, type Page } from '@playwright/test'

const mockCommonApi = async (page: Page) => {
  await page.route('**/api/auth/me', (route) => route.fulfill({ json: { user_id: 'test-user', workspace_id: 'default' } }))
  await page.route('**/api/sessions', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/sessions?*', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/snapshots', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/status', (route) => route.fulfill({ json: { rag_loaded: false, rag_mode: 'mock', tools_count: 0, llm_model: 'mock', embedding_model: 'mock', is_mock: true, infrastructure: {} } }))
  await page.route('**/api/tools', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/documents', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/team/me', (route) => route.fulfill({ json: { workspaceId: 'default', userId: 'test-user', displayName: 'Test', email: '', role: 'owner', status: 'active', invitedBy: 'test-user', createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), administrator: true, editor: true } }))
  await page.route('**/api/tasks', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/connections/providers', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/connections', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/team/members', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/pilot/dashboard', (route) => route.fulfill({ json: { generatedReports: 0, completedReports: 0, exportedReports: 0, averageCompletionSeconds: 0, averageEditCount: 0, factErrorRate: 0, reuseRate: 0, averageRating: 0, feedbackCount: 0, recentComments: [] } }))
  await page.route('**/api/weekly-report/templates', (route) => route.fulfill({ json: [{ id: 'builtin-weekly-report', name: '标准项目周报', description: '', builtIn: true, contentMd: '' }] }))
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
  await page.route('**/api/sessions', (route) => route.fulfill({ json: [{ id: 'session-1', title: '产品方案', created_at: new Date().toISOString(), updated_at: new Date().toISOString(), message_count: 2 }] }))
  await page.route('**/api/sessions/session-1/messages?*', (route) => route.fulfill({ json: [
    { id: 1, role: 'user', content: '写一份产品方案', created_at: new Date().toISOString() },
    { id: 2, role: 'assistant', content: '# 新产品方案\n\n项目目标', created_at: new Date().toISOString() },
  ] }))
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
  for (const path of ['/chat', '/knowledge', '/documents', '/weekly-report', '/operations', '/tools', '/status', '/settings']) {
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

test('uploaded attachment is bound to one question and renders a versioned citation', async ({ page }) => {
  let chatPayload: Record<string, unknown> | undefined
  const source = {
    chunk: { content: '本周完成登录回归，完成率 75%。' }, similarity: 0.94, context_id: 31,
    document_id: 'doc-weekly', version_id: 'ver-2', document_title: '项目周报', version: 2,
    locator: '原文片段 3', excerpt: '本周完成登录回归，完成率 75%。', retrieval_source: 'hybrid',
  }
  await page.route('**/api/upload/file', (route) => route.fulfill({ json: {
    document_saved: true, index_status: 'indexed', document_id: 'doc-weekly', version_id: 'ver-2', filename: 'weekly.md',
  } }))
  await page.route('**/api/chat/stream', async (route) => {
    chatPayload = route.request().postDataJSON() as Record<string, unknown>
    const body = [
      `event: intent\ndata: ${JSON.stringify({ intent: 'summarize', knowledge_scope: 'selected', document_ids: ['doc-weekly'] })}\n\n`,
      `event: mode\ndata: ${JSON.stringify({ mode: 'rag' })}\n\n`,
      `event: rag_result\ndata: ${JSON.stringify({ chunks: [source] })}\n\n`,
      `event: done\ndata: ${JSON.stringify({ answer: '完成率为 75% [S1]', mode: 'rag', intent: 'summarize', search_results: [source] })}\n\n`,
    ].join('')
    await route.fulfill({ status: 200, contentType: 'text/event-stream', body })
  })
  await page.goto('/chat')
  await page.locator('input[type="file"]').setInputFiles({ name: 'weekly.md', mimeType: 'text/markdown', buffer: Buffer.from('# 周报\n完成率 75%') })
  await expect(page.getByText('weekly.md')).toBeVisible()
  await page.getByRole('textbox', { name: '向 DreamLoop 提问' }).fill('总结附件')
  await page.getByRole('button', { name: '发送消息' }).click()
  await expect(page.getByText('完成率为 75% [S1]')).toBeVisible()
  await expect(page.getByText('项目周报v2')).toBeVisible()
  await expect(page.getByText(/原文片段 3/)).toBeVisible()
  expect(chatPayload).toMatchObject({ knowledge_scope: 'selected', document_ids: ['doc-weekly'], message: '总结附件' })
  await expect(page.getByText('weekly.md')).toBeHidden()
})
