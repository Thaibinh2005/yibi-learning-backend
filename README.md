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
- At startup the backend also loads the bundled AI example sentences (`hsk_ai_examples.json`) and level
  meanings (`hsk_level_meanings.json`); nothing to run, and no Gemini key is needed for them.

## Running on a server

- Start with `SPRING_PROFILES_ACTIVE=prod`. The backend then refuses to start unless `JWT_SECRET` (at least 32
  characters), `DB_PASSWORD`, `REDIS_PASSWORD` and `MINIO_ROOT_PASSWORD` are set and differ from the development
  defaults in `application.yml` (that file is public, so a default JWT secret would let anyone sign tokens).
- The `prod` profile also logs at INFO, stops logging SQL and the values bound to it, and never sends exception
  details to the browser.
- Limits (Redis, per user, per address for guests; admins are exempt): 5 wrong passwords per account or 30 per
  address in 15 minutes; speech scoring 20 per minute and 400 per day; adding listening material 20 per hour;
  saving lessons 40 per minute; public reads 240 per minute; JSON bodies up to 4 MB. See `RateLimitFilter`.

## Dictation & shadowing (Nghe & Nói)

- Start the speech service (`yibi-speech-service` on port 8001, or set `SPEECH_SERVICE_URL`; run
  `pip install -r requirements.txt` first):
  it reads YouTube subtitles, transcribes uploaded files with Whisper and scores shadowing. Without it,
  learners can still add typed text.
- Vietnamese translations come from Gemini: put `GEMINI_API_KEY` (and optional `GEMINI_API_KEY_FALLBACK_1..2`)
  in `yibi-backend/.env` or the `.env` one folder up; the backend reads only the `GEMINI_*` entries from it.
  Without a key, lessons still get pinyin.
- Flyway V30 turns the old `video_transcripts` table into `listening_lessons` + `media_sources` and cannot be
  undone: back up your database before the first start on this version. It also creates 7 starter categories.
- Uploaded files are stored in `./media-storage` (git-ignored). Limits are set with `LISTENING_*` variables
  (5 videos and 300 translated sentences per learner per day, 100 MB / 20 minutes per file).
- Community lessons are not bundled: each database has its own; admins publish them in the "Thư viện video" tab.

Sources: meanings from CVDICT (CC BY-SA 4.0), grammar from krmanik/HSK-3.0, examples from Tatoeba
(CC BY 2.0 FR), passages written with Gemini and not reviewed. Audio is made with edge-tts.
