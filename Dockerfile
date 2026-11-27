FROM node:24-slim
WORKDIR /app
# pickup times ("at six thirty") are read in the server's timezone; .env can override it
ENV NODE_ENV=production TZ=Asia/Kolkata

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY server.js ./
COPY src ./src
COPY public ./public
COPY profiles ./profiles

# runtime data (orders, shops, signing key) lives here and is mounted as a volume
RUN mkdir data && chown node:node data
USER node

EXPOSE 3000
CMD ["node", "server.js"]
