# yibi-learning-backend

## Run locally with the same data as everyone else

```bash
cp .env.example .env            # then fill in the values
docker compose up -d            # PostgreSQL 16, Redis 7, MinIO
mvn spring-boot:run "-Dspring-boot.run.arguments=--import-syllabus --server.port=8089"
mvn spring-boot:run "-Dspring-boot.run.arguments=--import-passage-audio=data/passage-audio --server.port=8089"
mvn spring-boot:run
```

- Flyway builds the schema when the backend starts.
- `--import-syllabus` loads the HSK 3.0 syllabus, grammar, Vietnamese meanings, Tatoeba example sentences,
  the 7 courses with their lessons, and the 110 reading and listening passages from `src/main/resources/data`.
  Running it again is safe.
- `--import-passage-audio` uploads the mp3 files in `data/passage-audio` (named by passage, e.g. `L3-P2.mp3`)
  to MinIO and links them to the listening lessons. Run it after the first import.
- Run the frontend on port 5173; the backend allows CORS from that origin only.

Sources: meanings from CVDICT (CC BY-SA 4.0), grammar from krmanik/HSK-3.0, examples from Tatoeba
(CC BY 2.0 FR), passages written with Gemini and not reviewed. Audio is made with edge-tts.
