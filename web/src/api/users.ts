// Users API — the ADMIN management surface plus the self password change.
// Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type UserPage =
  paths["/api/v1/users"]["get"]["responses"]["200"]["content"]["application/json"];
export type UserResponse =
  paths["/api/v1/users/{id}"]["get"]["responses"]["200"]["content"]["application/json"];
type UserCreateBody =
  paths["/api/v1/users"]["post"]["requestBody"]["content"]["application/json"];
type UserUpdateBody =
  paths["/api/v1/users/{id}"]["put"]["requestBody"]["content"]["application/json"];
type PasswordUpdateBody =
  paths["/api/v1/users/{id}/password"]["put"]["requestBody"]["content"]["application/json"];

type UserListQuery = {
  page: number;
  pageSize: number;
  sort?: string;
  name?: string;
  email?: string;
  role?: string;
};

export async function listUsers(q: UserListQuery): Promise<UserPage> {
  const params = buildQuery({
    page: q.page,
    pageSize: q.pageSize,
    sort: q.sort,
    name: q.name,
    email: q.email,
    role: q.role,
  });
  return jsonRequest<UserPage>(`/api/v1/users?${params}`);
}

export async function getUser(id: number): Promise<UserResponse> {
  return jsonRequest<UserResponse>(`/api/v1/users/${id}`);
}

export async function createUser(req: UserCreateBody): Promise<UserResponse> {
  return jsonRequest<UserResponse>("/api/v1/users", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export async function updateUser(id: number, body: UserUpdateBody): Promise<void> {
  await voidRequest(`/api/v1/users/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function deleteUser(id: number): Promise<void> {
  await voidRequest(`/api/v1/users/${id}`, { method: "DELETE" });
}

export async function changeUserPassword(id: number, body: PasswordUpdateBody): Promise<void> {
  await voidRequest(`/api/v1/users/${id}/password`, { method: "PUT", body: JSON.stringify(body) });
}
