import { expect, test, type Page } from '@playwright/test'

const mockOperations = async (page: Page) => {
  const now = new Date().toISOString()
  const me = { workspaceId: 'default', userId: 'owner', displayName: 'Owner', email: '', role: 'owner', status: 'active', invitedBy: 'owner', createdAt: now, updatedAt: now, administrator: true, editor: true }
  let tasks = [{ id: 'task-1', workspaceId: 'default', userId: 'owner', taskType: 'weekly_report', title: '星桥项目周报', status: 'succeeded', progress: 100, message: '任务完成', result: {}, error: '', cancelRequested: false, createdAt: now, updatedAt: now }]
  let connections: Record<string, unknown>[] = []
  let members = [me]
  await page.route('**/api/auth/me', (route) => route.fulfill({ json: { user_id: 'owner', workspace_id: 'default' } }))
  await page.route('**/api/sessions', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/status', (route) => route.fulfill({ json: { rag_loaded: false, rag_mode: 'mock', tools_count: 0, llm_model: 'mock', embedding_model: 'mock', is_mock: true, infrastructure: {} } }))
  await page.route('**/api/team/me', (route) => route.fulfill({ json: me }))
  await page.route('**/api/team/members', async (route) => {
    if (route.request().method() === 'POST') {
      const body = route.request().postDataJSON() as Record<string, string>
      members.push({ ...me, userId: body.user_id, displayName: body.display_name, role: body.role, status: 'invited', administrator: false, editor: body.role === 'editor' })
    }
    return route.fulfill({ json: route.request().method() === 'POST' ? members.at(-1) : members })
  })
  await page.route('**/api/tasks', (route) => route.fulfill({ json: tasks }))
  await page.route('**/api/connections/providers', (route) => route.fulfill({ json: [{ id: 'manual_upload', name: '手动上传', capabilities: ['files.upload'] }] }))
  await page.route('**/api/connections', async (route) => {
    if (route.request().method() === 'POST') {
      const body = route.request().postDataJSON() as Record<string, string>
      connections = [{ id: 'conn-1', workspaceId: 'default', provider: 'manual_upload', providerName: '手动上传', displayName: body.display_name || '手动上传', status: 'connected', credentialRef: '', config: {}, capabilities: ['files.upload'], updatedAt: now }]
      return route.fulfill({ json: connections[0] })
    }
    return route.fulfill({ json: connections })
  })
  await page.route('**/api/pilot/dashboard', (route) => route.fulfill({ json: { generatedReports: 3, completedReports: 2, exportedReports: 1, averageCompletionSeconds: 95, averageEditCount: 6, factErrorRate: 0, reuseRate: 0.5, averageRating: 4.5, feedbackCount: 2, recentComments: ['引用清楚'] } }))
  await page.route('**/api/pilot/events', (route) => route.fulfill({ json: { ok: true } }))
}

test('manage tasks, connections, members and pilot feedback', async ({ page }) => {
  await mockOperations(page)
  await page.goto('/operations')
  await expect(page.getByText('星桥项目周报')).toBeVisible()
  await page.getByRole('button', { name: '平台连接' }).click()
  await page.getByLabel('连接名称').fill('项目资料')
  await page.getByRole('button', { name: '添加连接' }).click()
  await expect(page.getByText('项目资料')).toBeVisible()
  await page.getByRole('button', { name: '团队权限' }).click()
  await page.getByLabel('成员用户 ID').fill('editor-1')
  await page.getByLabel('成员姓名').fill('编辑成员')
  await page.getByRole('button', { name: '邀请' }).click()
  await expect(page.getByText('编辑成员')).toBeVisible()
  await page.getByRole('button', { name: '试点数据' }).click()
  await expect(page.getByText('95 秒')).toBeVisible()
  await page.getByPlaceholder('哪里节省了时间，哪里仍需人工修正？').fill('引用清楚，减少了核对时间。')
  await page.getByRole('button', { name: '提交反馈' }).click()
  await expect(page.getByText('试点反馈已记录。')).toBeVisible()
})
