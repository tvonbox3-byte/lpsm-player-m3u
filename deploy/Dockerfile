FROM node:22-alpine
WORKDIR /app
COPY backend/package.json ./
COPY backend/src ./src
COPY backend/public ./public
RUN mkdir -p /app/data && chown -R node:node /app
USER node
ENV PORT=8080 DATA_FILE=/app/data/lpsm.json
EXPOSE 8080
CMD ["node","src/server.js"]
