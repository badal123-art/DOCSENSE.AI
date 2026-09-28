# Folio

Folio is a document question-answering application built with a Spring Boot backend and a React/Vite frontend.

## Project layout

- `backend/` - Spring Boot API, PDF parsing, embeddings, and retrieval-augmented chat
- `frontend/` - React client built with Vite

## Requirements

- Java 25 JDK
- Node.js 20 or newer
- An `OPENROUTER_API_KEY` environment variable for OpenRouter requests

## Run the backend

From `backend/`:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
./mvnw spring-boot:run

# Or, when using the bundled Maven distribution:
# ./apache-maven-3.9.8/bin/mvn.cmd spring-boot:run
```

The API runs on `http://localhost:8080`.

## Run the frontend

From `frontend/`:

```powershell
npm install
npm run dev
```

Open `http://localhost:5173`. Vite proxies `/api` requests to Spring Boot.

The backend exposes `GET /api/status`, `POST /api/upload`, and `POST /api/chat`. Uploaded documents and embeddings are held in memory and are lost when the backend restarts.

Copy `.env.example` to `.env` and set `OPENROUTER_API_KEY` locally. The backend uses `openai/gpt-4o-mini` for chat and `openai/text-embedding-3-small` for document search. Do not commit `.env` or publish the key.
