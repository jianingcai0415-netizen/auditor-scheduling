import { api } from './client'

export type UserRole = 'ADMIN' | 'CAPOSALA' | 'DISPATCHER' | 'APPROVER' | 'VIEWER'

export interface AppUser {
  id: number
  username: string
  role: UserRole
  active: boolean
  displayName?: string
  email?: string
  createdAt?: string
  lastLoginAt?: string
}

export interface CreateUserPayload {
  username: string
  rawPassword: string
  role: UserRole
  displayName?: string
  email?: string
}

export interface UpdateUserPayload {
  username?: string
  role?: UserRole
  active?: boolean
  displayName?: string
  email?: string
  rawPassword?: string
}

export const usersApi = {
  list: () => api.get<AppUser[]>('/users'),
  create: (payload: CreateUserPayload) => api.post<AppUser>('/users', payload),
  update: (id: number, payload: UpdateUserPayload) => api.put<AppUser>(`/users/${id}`, payload),
  deactivate: (id: number) => api.delete<{ deactivated: boolean }>(`/users/${id}`),
}
