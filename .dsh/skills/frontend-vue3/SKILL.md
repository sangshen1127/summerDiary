---
name: frontend-vue3
description: Standard Vue 3 frontend architecture using Composition API, TypeScript, and API-first design.
---

-------------------------------------------------------------------------------------------------------

# frontend-vue3

## Purpose

Use this skill when implementing a **modern frontend application** with **Vue 3**.

This skill defines a **scalable, maintainable Vue 3 architecture** emphasizing:

* Composition API
* Type safety
* API-first frontend-backend separation
* Clear responsibility boundaries

It is suitable for **admin systems**, **B2B products**, and **long-lived web applications**.

---

## Tech Stack

* **Framework**: Vue 3.4+
* **Language**: TypeScript
* **Build Tool**: Vite
* **State Management**: Pinia
* **Routing**: Vue Router
* **HTTP Client**: Axios
* **Component Library**: Element Plus (recommended) / Headless UI
* **Styling**: Tailwind CSS (recommended) / CSS Modules
* **Validation**: Zod / Element Plus validators
* **Testing**: Vitest + Testing Library

---

## Design Philosophy

* Explicit data flow over implicit magic
* UI as a projection of state
* Business logic isolated from view components
* API contract is the source of truth

---

## Standard Architecture

```
frontend/
├── public/
├── src/
│   ├── main.ts                 # App entry
│   ├── App.vue
│   ├── api/                    # API client layer
│   │   ├── http.ts             # Axios instance, interceptors
│   │   ├── index.ts            # API module exports
│   │   └── {entity}.api.ts     # API calls per domain
│   ├── assets/                 # Static assets
│   ├── components/             # Global reusable components
│   │   ├── base/               # Buttons, Modals, Inputs
│   │   └── layout/             # Layout-related components
│   ├── composables/            # Reusable Composition API logic
│   │   └── use{Feature}.ts
│   ├── views/                  # Page-level components (route targets)
│   │   └── {Entity}View.vue
│   ├── router/                 # Router configuration
│   │   └── index.ts
│   ├── stores/                 # Pinia stores
│   │   └── {entity}.store.ts
│   ├── types/                  # Shared TypeScript types
│   │   └── {entity}.ts
│   ├── utils/                  # Utility helpers
│   └── styles/                 # Global styles
├── tests/
│   ├── components/
│   └── views/
├── index.html
├── vite.config.ts
├── tsconfig.json
└── package.json
```

---

## Layer Responsibilities

### View (views/)

* Page-level UI composition
* Bind state to UI
* Handle user interactions
* **NO direct API calls**

### Component (components/)

* Pure UI components
* Stateless or minimally stateful
* Reusable across views

### Store (stores/)

* Centralized application state
* Coordinate API calls
* Handle loading / error states

### API Layer (api/)

* All HTTP communication
* Request/response typing
* Error normalization

### Composables (composables/)

* Reusable business/UI logic
* Encapsulate complex state logic

---

## State Management Rules

* Pinia is the single source of truth
* Views consume state via stores
* Stores may call API modules
* No API calls inside components directly

---

## API-First Frontend Design

* API contracts defined before UI implementation
* Request/response types mirrored from backend schemas
* Prefer generated types (OpenAPI) when available

---

## Dependency Rules

* Views depend on Stores & Components
* Stores depend on API modules
* API modules depend on http client
* No circular dependencies

---

## Inputs

This skill expects:

* **API Base URL** (e.g., `https://api.example.com`)
* **Entity Definitions** (name, fields, types)
* **Authentication** (JWT / Cookie / None)
* **Routing Requirements** (route paths)
* **UI Complexity** (simple / admin / complex)

---

## Outputs

* **TypeScript Types** – Typed API contracts
* **API Client Module** – Axios wrapper with typed requests/responses
* **Pinia Store** – Centralized state management
* **CRUD Views** – List, form, and detail pages
* **Reusable Components** – Table, form, filters
* **Routing Config** – Route definitions

---

## Templates

Located in `assets/templates/crud/`:

### Core Templates (Shared)
| Template | Purpose |
|----------|---------|
| `types.ts.tmpl` | TypeScript interfaces and types |
| `api.ts.tmpl` | API client with Axios |
| `store.ts.tmpl` | Pinia store for state management |

### Element Plus UI Templates
| Template | Purpose |
|----------|---------|
| `ElementTable.vue.tmpl` | List/table with el-table |
| `ElementForm.vue.tmpl` | Form with el-form validation |
| `ElementView.vue.tmpl` | Page view with el-dialog modal |

### Tailwind CSS Templates
| Template | Purpose |
|----------|---------|
| `TailwindTable.vue.tmpl` | List/table with Tailwind styling |
| `TailwindForm.vue.tmpl` | Form with Tailwind styling |
| `TailwindView.vue.tmpl` | Page view with Tailwind styling |

### Configuration
| File | Purpose |
|------|---------|
| `deps.py` | NPM dependencies and placeholder mapping |

---

## Template Placeholders

| Placeholder | Description | Example |
|-------------|-------------|---------|
| `{{Entity}}` | PascalCase entity name | `User`, `Product` |
| `{{entity}}` | camelCase entity name | `user`, `product` |
| `{{entities}}` | Lowercase plural | `users`, `products` |
| `{{package}}` | Import base path | `@/api`, `@/types` |
| `{{fields}}` | Entity properties | `name: string; email: string;` |
| `{{table_columns}}` | Element Plus table columns | `<el-table-column prop="name" label="Name" />` |
| `{{table_rows}}` | Tailwind table row cells | `<td>{{ item.name }}</td>` |
| `{{form_fields}}` | Form input fields | Form field templates |

---

## Style Guide - Element Plus vs Tailwind

### Element Plus (Recommended for Enterprise)
**Pros:**
- Rich component library (100+ components)
- Built-in theming and customization
- Form validation out-of-the-box
- Better for complex admin UIs
- No build configuration needed

**Cons:**
- Larger bundle size (~300KB gzipped)
- Less customizable than Tailwind
- Opinionated design system

**Installation:**
```bash
npm install element-plus @element-plus/icons-vue
```

**Setup (main.ts):**
```typescript
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'

const app = createApp(App)
app.use(ElementPlus)
```

**Example:**
```vue
<el-table :data="items" stripe>
  <el-table-column prop="name" label="Name" />
  <el-table-column label="Actions">
    <template #default="{ row }">
      <el-button @click="edit(row.id)">Edit</el-button>
    </template>
  </el-table-column>
</el-table>

<el-dialog v-model="visible" title="Create User">
  <el-form :model="form">
    <el-form-item label="Name" prop="name">
      <el-input v-model="form.name" />
    </el-form-item>
  </el-form>
</el-dialog>
```

### Tailwind CSS (Recommended for Custom Design)
**Pros:**
- Utility-first approach for maximum control
- Smaller bundle size (~15KB gzipped)
- Highly customizable
- Great for modern, minimal designs
- Perfect for design systems

**Cons:**
- Steeper learning curve
- Requires build configuration
- Need to compose components yourself
- More CSS to write for complex UIs

**Installation:**
```bash
npm install -D tailwindcss postcss autoprefixer
npx tailwindcss init -p
```

**Configuration (tailwind.config.js):**
```javascript
export default {
  content: [
    './index.html',
    './src/**/*.{vue,js,ts,jsx,tsx}',
  ],
  theme: {
    extend: {},
  },
  plugins: [],
}
```

**Add to CSS (src/index.css):**
```css
@tailwind base;
@tailwind components;
@tailwind utilities;
```

**Example:**
```vue
<table class="min-w-full divide-y divide-gray-200">
  <thead class="bg-gray-50">
    <tr>
      <th class="px-6 py-3 text-left text-xs font-medium text-gray-500">
        Name
      </th>
    </tr>
  </thead>
  <tbody class="divide-y divide-gray-200">
    <tr v-for="item in items">
      <td class="px-6 py-4">{{ item.name }}</td>
    </tr>
  </tbody>
</table>

<div v-if="showModal" class="fixed inset-0 bg-gray-600 bg-opacity-50 flex items-center justify-center">
  <div class="bg-white rounded-lg shadow-lg p-6">
    <h2 class="text-lg font-bold mb-4">Create User</h2>
    <form>
      <input v-model="form.name" class="border rounded px-3 py-2 w-full" />
    </form>
  </div>
</div>
```

---

## Choosing Your Style

| Feature | Element Plus | Tailwind |
|---------|--------------|----------|
| Learning Curve | Low | Medium |
| Bundle Size | Large | Small |
| Component Library | Rich (100+) | None |
| Customization | Moderate | High |
| Admin UI | Excellent | Good |
| Minimalist UI | Fair | Excellent |
| Form Validation | Built-in | Manual |
| Theming | Easy | Complex |

**Use Element Plus If:**
- Building admin panels
- Need rich components
- Team unfamiliar with CSS
- Quick prototyping required
- Complex forms needed

**Use Tailwind If:**
- Building custom designs
- Want minimal bundle size
- Team comfortable with utilities
- Design flexibility is priority
- Minimalist UI needed

---

## Quick Start Example

```bash
# Replacements:
{{Entity}} → User
{{entity}} → user
{{entities}} → users
{{package}} → @/

# types.ts
export interface User {
  id: number;
  name: string;
  email: string;
  createdAt: string;
  updatedAt: string;
}

# api.ts creates:
export const getUserList = async (offset: number, limit: number) => {...}
export const getUserById = async (id: number) => {...}
export const createUser = async (payload: CreateUserRequest) => {...}
export const updateUser = async (id: number, payload: UpdateUserRequest) => {...}
export const deleteUser = async (id: number) => {...}

# store.ts creates Pinia store with actions:
useUserStore().fetchList()
useUserStore().create(payload)
useUserStore().update(id, payload)
useUserStore().remove(id)
```

---

## Best Practices

1. **Always use `<script setup lang="ts">`** – Modern Vue 3 syntax
2. **Keep views thin** – Move logic to stores/composables
3. **Use typed API responses everywhere** – Never `any`
4. **Avoid shared mutable state outside Pinia** – Single source of truth
5. **Handle loading and error states explicitly** – Show feedback to user
6. **Use composables for cross-cutting logic** – useForm, usePagination, etc.
7. **Version routes alongside API versions** – `/user` matches `/api/v1/users`
8. **Prefer store actions over component methods** – Centralized logic
9. **Type all props and emits** – Full TypeScript support
10. **Lazy load routes** – Code split for large apps

---

## Common Patterns

### API Module Pattern

```typescript
// api/user.api.ts
import { http } from './http';
import type { User, CreateUserRequest } from '@/types/user';

export const getUserList = async (offset = 0, limit = 20) => {
  const { data } = await http.get('/api/v1/users', {
    params: { offset, limit },
  });
  return data;
};

export const createUser = async (payload: CreateUserRequest) => {
  const { data } = await http.post('/api/v1/users', payload);
  return data;
};
```

### Pinia Store Pattern

```typescript
// stores/user.store.ts
import { defineStore } from 'pinia';
import { ref } from 'vue';
import * as userApi from '@/api/user.api';

export const useUserStore = defineStore('user', () => {
  const items = ref([]);
  const loading = ref(false);
  const error = ref(null);

  const fetchList = async () => {
    loading.value = true;
    try {
      const response = await userApi.getUserList();
      items.value = response.data;
    } catch (err) {
      error.value = err.message;
    } finally {
      loading.value = false;
    }
  };

  return { items, loading, error, fetchList };
});
```

### View Component Pattern

```vue
<script setup lang="ts">
import { useUserStore } from '@/stores/user.store';
import { onMounted } from 'vue';

const store = useUserStore();

onMounted(() => {
  store.fetchList();
});
</script>

<template>
  <div v-if="store.loading">Loading...</div>
  <div v-if="store.error" class="error">{{ store.error }}</div>
  <table v-else>
    <!-- render store.items -->
  </table>
</template>
```

### Form Component Pattern

```vue
<script setup lang="ts">
import { ref, watch } from 'vue';
import type { User, UpdateUserRequest } from '@/types/user';

interface Props {
  item?: User;
}

const props = defineProps<Props>();
const emit = defineEmits<{
  submit: [payload: UpdateUserRequest];
}>();

const form = ref({ name: '', email: '' });

watch(() => props.item, (item) => {
  if (item) {
    form.value = { name: item.name, email: item.email };
  }
});

const handleSubmit = () => {
  emit('submit', form.value);
};
</script>
```

---

## NPM Dependencies

### Core Dependencies
```bash
npm install vue@^3.4.0 pinia@^2.1.0 vue-router@^4.2.0 axios@^1.6.0 zod@^3.22.0
```

### Element Plus Setup
```bash
# Install Element Plus
npm install element-plus @element-plus/icons-vue

# Add to main.ts
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
app.use(ElementPlus)
```

### Tailwind CSS Setup
```bash
# Install Tailwind
npm install -D tailwindcss postcss autoprefixer
npx tailwindcss init -p

# Update tailwind.config.js
export default {
  content: ['./index.html', './src/**/*.{vue,js,ts}'],
  theme: { extend: {} },
  plugins: [],
}

# Add to src/index.css
@tailwind base;
@tailwind components;
@tailwind utilities;

# Import in main.ts
import './index.css'
```

### Development Dependencies
```bash
npm install -D typescript@^5.0.0 vite@^5.0.0 @vitejs/plugin-vue@^5.0.0
npm install -D vitest@^0.34.0 @testing-library/vue@^8.0.0 @vue/test-utils@^2.4.0
```

---

## Testing Strategy

```typescript
// tests/stores/user.store.test.ts
import { setActivePinia, createPinia } from 'pinia';
import { useUserStore } from '@/stores/user.store';

beforeEach(() => {
  setActivePinia(createPinia());
});

it('should fetch users', async () => {
  const store = useUserStore();
  await store.fetchList();
  expect(store.items).toHaveLength(10);
});

// tests/api/user.api.test.ts
import { describe, it, expect, vi } from 'vitest';
import * as userApi from '@/api/user.api';

it('should call /api/v1/users', async () => {
  vi.mock('@/api/http');
  const result = await userApi.getUserList();
  expect(result).toBeDefined();
});

// tests/components/UserForm.test.ts
import { mount } from '@vue/test-utils';
import UserForm from '@/components/UserForm.vue';

it('should emit submit event', async () => {
  const wrapper = mount(UserForm);
  await wrapper.vm.handleSubmit();
  expect(wrapper.emitted().submit).toBeTruthy();
});
```

---

## Axios Instance Setup

```typescript
// api/http.ts
import axios, { type AxiosInstance } from 'axios';
import router from '@/router';

export const http: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:3000',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Response interceptor
http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      router.push('/login');
    }
    return Promise.reject(error);
  }
);
```

---

## Anti-Patterns to Avoid

1. ❌ API calls inside components – move to stores
2. ❌ Business logic inside views – extract to composables
3. ❌ Untyped API responses – always use interfaces
4. ❌ Global event bus – use Pinia for communication
5. ❌ Overusing watchers – consider composables instead
6. ❌ Mutable state outside Pinia – single source of truth
7. ❌ Component prop drilling – use store for shared state
8. ❌ Computed in templates – move to computed()

---

## Project Structure

```
src/
├── api/
│   ├── http.ts              # Axios instance
│   ├── user.api.ts
│   ├── product.api.ts
│   └── index.ts             # Exports
├── stores/
│   ├── user.store.ts
│   ├── product.store.ts
│   └── index.ts
├── types/
│   ├── user.ts
│   ├── product.ts
│   └── common.ts            # Shared types
├── views/
│   ├── user/
│   │   ├── UserView.vue
│   │   └── components/
│   │       ├── UserTable.vue
│   │       └── UserForm.vue
│   └── product/
│       ├── ProductView.vue
│       └── components/
├── components/
│   ├── base/                # Global UI
│   │   ├── Button.vue
│   │   └── Modal.vue
│   └── layout/
│       ├── Header.vue
│       └── Sidebar.vue
├── composables/
│   ├── usePagination.ts
│   ├── useForm.ts
│   └── useAuth.ts
├── router/
│   └── index.ts
├── App.vue
└── main.ts
```