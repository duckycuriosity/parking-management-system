# Authentication Flow

## 1. Tong quan

Du an nay dang su dung Keycloak lam Identity Provider va Spring Boot lam Resource Server.
Backend khong co endpoint login rieng. Client se dang nhap qua Keycloak, lay `access token` (JWT), sau do gui token nay trong header:

```http
Authorization: Bearer <access_token>
```

Backend se:
1. Xac thuc JWT bang `issuer-uri` cua Keycloak.
2. Resolve token thanh `Authentication`.
3. Load user noi bo tu DB thong qua `keycloakUserId`.
4. Gan roles, permissions vao `Authentication`.
5. Dung `@PreAuthorize` de kiem tra quyen o level method.

## 2. Cau hinh auth hien tai

### 2.1 Spring Security
File: `src/main/java/vio/barie/core/config/auth/SecurityConfig.java`

- Bat `@EnableWebSecurity` va `@EnableMethodSecurity`.
- Cau hinh OAuth2 Resource Server voi JWT.
- Dung `JwtAuthConverter` de chuyen `Jwt` thanh `Authentication`.
- Co custom handler cho:
  - `401 Unauthorized`
  - `403 Forbidden`

### 2.2 Keycloak
File: `src/main/resources/application-dev.yml`

- `spring.security.oauth2.resourceserver.jwt.issuer-uri` tro den realm Keycloak.
- `keycloak.*` duoc dung cho `KeycloakAdminConfig` de tao admin client, phuc vu cac tac vu nhu tao user, xoa user, set enabled.

## 3. Luong xu ly request co token

### Buoc 1: Client gui request
Client gui request kem JWT trong header `Authorization: Bearer ...`.

### Buoc 2: Spring Security validate token
`SecurityConfig` cau hinh resource server voi JWT.
Neu token sai, het han, hoac khong hop le:
- tra `401`
- message co the la `Invalid token`, `Token expired`, hoac `Missing authentication token`

### Buoc 3: JwtAuthConverter tao principal
File: `src/main/java/vio/barie/core/config/auth/JwtAuthConverter.java`

- Lay `keycloakUserId = jwt.getSubject()`.
- Goi `CurrentUserService.getCurrentUser(keycloakUserId)` de doc user noi bo tu DB.
- Doc roles tu `realm_access.roles` trong JWT.
- Gop them:
  - roles tu token
  - roles tu DB
  - permissions tu DB
- Tao `UsernamePasswordAuthenticationToken` voi principal la `UserDTO`.

### Buoc 4: CurrentUserService lay user noi bo
File: `src/main/java/vio/barie/core/service/CurrentUserService.java`

- Tim `SysUserEntity` bang `keycloakUserId`.
- Neu user khong ton tai -> `AccessDeniedException("User not found")`.
- Neu status khong phai `ACTIVE` -> `AccessDeniedException("User is not active")`.
- Build `UserDTO` gom:
  - `userId`
  - `keycloakUserId`
  - `organizeId`
  - `branchId`
  - `username`
  - `email`
  - `roles`
  - `permissions`

### Buoc 5: UserContextFilter gan context theo request
File: `src/main/java/vio/barie/core/config/filter/UserContextFilter.java`

- Lay `Authentication` tu `SecurityContextHolder`.
- Neu `principal` la `UserDTO` thi set vao `UserContext` (ThreadLocal).
- Cuoi request se clear context.

### Buoc 6: Method security kiem tra quyen
Nhieu controller dung `@PreAuthorize`.
Vi du:
- `isAuthenticated()` cho endpoint can dang nhap.
- `hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())`
- `hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_VIEW())`

`AuthorizationService` duoc expose voi bean name `authz`, co the dung trong SpEL de kiem tra permission/role/branch/organize.

## 4. Cac diem can chu y trong project nay

### 4.1 `permitAll` dang bao phu rat rong
Trong `SecurityConfig`, co dong:

```java
.requestMatchers("/**").permitAll()
```

Nghia la ve mat URL authorization, gan nhu tat ca endpoint deu duoc permit.
Voi cai dat hien tai, viec chan truy cap chu yeu nam o:
- `@PreAuthorize`
- logic trong service
- custom validation

Neu mot endpoint khong co `@PreAuthorize`, no co the truy cap duoc ma khong can token.

### 4.2 Khong co login endpoint trong backend
Backend nay khong tu dang nhap.
Muon test auth thi can lay token tu Keycloak.

### 4.3 Keycloak user va local user la hai lop khac nhau
- Keycloak quan ly danh tinh va JWT.
- DB local luu user noi bo trong `SysUserEntity`.
- `keycloakUserId` la key noi 2 he thong.

## 5. Cach test auth luong hien tai

### Cach 1: Test nhanh bang endpoint `/api/user/me`
Day la endpoint tot nhat de kiem tra auth.
No can token hop le va user phai ton tai trong DB local.

#### 5.1 Lay access token tu Keycloak
Ban can co JWT tu realm `barie`.
Co the lay token bang Postman hoac tu frontend dang nhap.
Neu client cua ban ho tro password grant, co the test bang token endpoint cua Keycloak.

#### 5.2 Goi API
```bash
curl -X GET "http://localhost:8080/api/user/me" \
  -H "Authorization: Bearer <access_token>"
```

#### 5.3 Ket qua mong doi
- `200 OK`: neu token hop le va user ton tai + `ACTIVE`
- `401 Unauthorized`: neu token sai/het han/thieu token
- `403 Forbidden`: neu token hop le nhung user khong co quyen hoac bi chặn boi method security

### Cach 2: Test endpoint can quyen edit
Vi du:
- `POST /api/user/create`
- `PUT /api/user/{userId}`
- `DELETE /api/user`

Nhung endpoint nay co `@PreAuthorize("hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")`.

#### Test
```bash
curl -X POST "http://localhost:8080/api/user/create" \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "test.user",
    "email": "test.user@example.com",
    "password": "12345678",
    "roleCodes": ["ROLE_ORG_VIEW"]
  }'
```

Neu user khong co role edit, se bi `403 Forbidden`.

### Cach 3: Test endpoint co `@Authenticated()` nhung khong co permission
Vi du:
- `GET /api/user/me`
- `PUT /api/user/updateProfile`

Neu token hop le, request se vao duoc method.

## 6. Vai tro cua cac role hien tai
File: `src/main/java/vio/barie/core/common/RoleEnum.java`

- `ROLE_ORG_ROOT`
- `ROLE_ORG_VIEW`
- `ROLE_ORG_EDIT`
- `ROLE_BRANCH_VIEW`
- `ROLE_BRANCH_EDIT`

Helper:
- `CAN_VIEW()`
- `CAN_EDIT()`
- `ONLY_ORG_VIEW()`
- `ONLY_ORG_EDIT()`
- `ONLY_ROOT()`
- `REQUIRED_BRANCH()`

## 7. Lua y khi debug

Neu ban lay token dung nhung van bi loi:
1. Kiem tra `iss` trong token phai trung voi `issuer-uri`.
2. Kiem tra `sub` trong token co ton tai trong bang `SysUserEntity` voi `keycloakUserId` hay khong.
3. Kiem tra status user co phai `ACTIVE` khong.
4. Kiem tra user co role/permission can thiet khong.
5. Kiem tra endpoint co `@PreAuthorize` hay khong.
6. Kiem tra security config co cho phep URL nay bi `permitAll` khong.

## 8. Ket luan

Auth flow hien tai cua project la:

**Keycloak JWT -> Spring Resource Server validate token -> JwtAuthConverter map sang user noi bo -> UserContext/AuthorizationService/@PreAuthorize kiem tra quyen**

Neu muon test nhanh, hay bat dau tu `GET /api/user/me` voi mot access token hop le.
