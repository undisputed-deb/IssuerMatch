FROM node:24-alpine AS build
WORKDIR /web
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
# These three values are public by design (they ship to the browser). Nothing secret may be passed here.
ARG VITE_SUPABASE_URL
ARG VITE_SUPABASE_ANON_KEY
ARG VITE_API_URL=http://localhost:8080
ENV VITE_ENV_DIR=. VITE_SUPABASE_URL=$VITE_SUPABASE_URL VITE_SUPABASE_ANON_KEY=$VITE_SUPABASE_ANON_KEY VITE_API_URL=$VITE_API_URL
RUN npm run build

FROM nginx:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /web/dist /usr/share/nginx/html
EXPOSE 80
