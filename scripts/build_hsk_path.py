"""Build src/main/resources/data/hsk_path_2025.json: themed learning units for each HSK level.

Step 1 (--step units) sorts every syllabus word of a level into themes with Gemini. The code, not the
model, guarantees each word lands in exactly one unit: anything unclassified goes to a "common words" unit.
Step 2 (--step enrich --levels 1 2 3) writes a dialogue and grammar explanations for each unit, and rejects
dialogues that use characters above the level.

Reads the lessons from the database and never writes to it. Re-running resumes: finished parts are kept.

  python scripts/build_hsk_path.py --step units
  python scripts/build_hsk_path.py --step enrich --levels 1 2 3
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from pathlib import Path

import psycopg
from google.genai import types
from pydantic import BaseModel

ROOT = Path(__file__).resolve().parents[3] / "Project"
sys.path.insert(0, str(ROOT))
from gemini_client_pool import GeminiClientPool  # noqa: E402

OUTPUT = Path(__file__).resolve().parents[1] / "src/main/resources/data/hsk_path_2025.json"
MODELS = ("gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.8-flash")
LEVELS = {"1": 1, "2": 2, "3": 3, "4": 4, "5": 5, "6": 6, "7-9": 7}
WORDS_PER_UNIT = 20
MIN_UNIT_WORDS = 6
CLASSIFY_BATCH = 250
HAN = re.compile(r"[一-鿿]")
LATIN_WORD = re.compile(r"[A-Za-zÀ-ỿ]{2,}")
OPTION_LETTER = re.compile(r"^[A-D][.、．]\s*")
QUOTED_OR_BRACKETED = re.compile(r"\([^)]*\)|（[^）]*）|'[^']*'|\"[^\"]*\"|“[^”]*”|‘[^’]*’")
MAX_OFF_LEVEL_SHARE = 0.05
LIMITED_MODEL_REST_SECONDS = 600


class Theme(BaseModel):
    title_vi: str
    title_zh: str
    icon: str


class ThemeList(BaseModel):
    themes: list[Theme]


class Placement(BaseModel):
    word: str
    theme: int


class Placements(BaseModel):
    placements: list[Placement]


class Line(BaseModel):
    speaker: str
    zh: str
    pinyin: str
    vi: str


class Question(BaseModel):
    question: str
    options: list[str]
    answer: str
    explanation: str


class Dialogue(BaseModel):
    title: str
    lines: list[Line]
    questions: list[Question]


class GrammarNote(BaseModel):
    content: str
    explanation_vi: str
    example_zh: str
    example_pinyin: str
    example_vi: str


class Enrichment(BaseModel):
    dialogue: Dialogue
    grammar: list[GrammarNote]
    grammar_questions: list[Question]


def load_env() -> None:
    for raw in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        if "=" in raw and not raw.lstrip().startswith("#"):
            key, value = raw.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip().strip('"'))


def dsn() -> str:
    return os.getenv("HSK_DATABASE_URL") or (
        f"postgresql://{os.environ['POSTGRES_USER']}:{os.environ['POSTGRES_PASSWORD']}"
        f"@localhost:5432/{os.getenv('POSTGRES_DB', 'yibi_db')}"
    )


def load_level(conn, level: int) -> dict:
    """The words, grammar points and characters of one level, exactly as its lessons hold them."""
    rows = conn.execute(
        """
        SELECT l.content_type, l.lesson_payload->'sections'
        FROM lessons l JOIN courses c ON c.id = l.course_id
        WHERE c.hsk_level = %s AND l.content_type IN ('VOCABULARY', 'GRAMMAR', 'HANZI')
        ORDER BY l.lesson_order
        """,
        (level,),
    ).fetchall()
    words, grammar, hanzi = [], [], []
    for content_type, sections in rows:
        if content_type == "VOCABULARY":
            words += sections.get("vocabulary", [])
        elif content_type == "GRAMMAR":
            # Sentence-type points are named only by their subcategory.
            grammar += [{**g, "content": g.get("content") or g.get("subcategory") or g.get("category_name")}
                        for g in sections.get("grammar", [])]
        else:
            hanzi += [item["hanzi"] for item in sections.get("characters", [])]
    return {"words": words, "grammar": grammar, "hanzi": hanzi}


class Gemini:
    def __init__(self) -> None:
        self.pool = GeminiClientPool()
        self.limited_until: dict[str, float] = {}

    def ask(self, prompt: str, schema):
        last = None
        for model in MODELS:
            if time.time() < self.limited_until.get(model, 0):
                continue
            for attempt in range(6):
                try:
                    response = self.pool.client.models.generate_content(
                        model=model,
                        contents=prompt,
                        config=types.GenerateContentConfig(
                            response_mime_type="application/json", response_schema=schema, temperature=0.4,
                            http_options=types.HttpOptions(timeout=150_000),
                        ),
                    )
                    return schema.model_validate_json(response.text)
                except Exception as exc:  # the SDK raises several types; every one is retried, then reported
                    last = exc
                    if "429" in str(exc):
                        # Out of quota on this key: use the next one, and wait only after trying them all.
                        if self.pool.rotate():
                            continue
                        self.pool._index, self.pool._client = 0, None
                        # Waiting on every call costs more than the fallback does, so look again only after a while.
                        self.limited_until[model] = time.time() + LIMITED_MODEL_REST_SECONDS
                        print(f"  all keys limited on {model}, resting it", flush=True)
                        break
                    print(f"  retry {model}: {str(exc)[:100]}", flush=True)
                    # A dropped connection stays broken in the client's pool, so the next try needs a fresh one.
                    self.pool._client = None
                    time.sleep(6 * (attempt + 1) if "503" in str(exc) else 2)
        raise RuntimeError(f"Gemini failed: {last}")


def describe(word: dict) -> str:
    return f"{word['simplified']} ({str(word.get('meaning_vi') or word.get('meaning_en') or '')[:40]})"


def build_units(gemini: Gemini, level_code: str, data: dict) -> list[dict]:
    distinct = list(dict.fromkeys(w["simplified"] for w in data["words"]))
    by_word = {w["simplified"]: w for w in data["words"]}
    count = max(4, round(len(distinct) / 14))
    step = max(1, len(distinct) // 300)
    sample = "; ".join(describe(by_word[w]) for w in distinct[::step])
    themes = gemini.ask(
        f"""Bạn thiết kế giáo trình tiếng Trung cho người Việt, cấp HSK {level_code} (chuẩn HSK 3.0).
Đề xuất khoảng {count} chủ đề học hẹp và cụ thể, đủ bao quát các từ dưới đây, mỗi chủ đề chứa các từ thật sự liên quan nhau.
Ví dụ tách riêng "Số đếm" với "Ngày tháng", "Gia đình" với "Bạn bè"; không gộp hai mảng bằng dấu &.
Hư từ cần chủ đề riêng theo công dụng, ví dụ "Đại từ nhân xưng", "Từ để hỏi", "Phó từ thường dùng", "Trợ từ và liên từ".
Xếp từ chủ đề dễ và thiết yếu nhất đến khó hơn, ví dụ HSK 1 mở đầu bằng chào hỏi, đại từ, số đếm, gia đình.
title_vi ngắn gọn bằng tiếng Việt, title_zh tiếng Trung, icon là đúng một emoji.
Các từ: {sample}""",
        ThemeList,
    ).themes
    theme_lines = "\n".join(f"{i}: {t.title_vi}" for i, t in enumerate(themes))

    buckets: dict[int, list[str]] = {i: [] for i in range(len(themes))}
    placed: set[str] = set()
    for start in range(0, len(distinct), CLASSIFY_BATCH):
        batch = distinct[start : start + CLASSIFY_BATCH]
        result = gemini.ask(
            f"""Xếp MỖI từ tiếng Trung sau vào đúng một chủ đề phù hợp nghĩa nhất. Trả về số thứ tự chủ đề.
Hư từ, đại từ, phó từ: xếp vào chủ đề về công dụng của chúng nếu có, không xếp vào chủ đề đời sống chỉ vì hay gặp ở đó.
Chủ đề:
{theme_lines}
Từ:
{chr(10).join(describe(by_word[w]) for w in batch)}""",
            Placements,
        )
        allowed = set(batch)
        for item in result.placements:
            if item.word in allowed and item.word not in placed and item.theme in buckets:
                buckets[item.theme].append(item.word)
                placed.add(item.word)
        print(f"  HSK {level_code}: {len(placed)}/{len(distinct)} words placed", flush=True)

    units: list[dict] = []
    leftovers = [w for w in distinct if w not in placed]
    for index, theme in enumerate(themes):
        words = buckets[index]
        if len(words) < MIN_UNIT_WORDS:
            leftovers += words
            continue
        parts = -(-len(words) // WORDS_PER_UNIT)
        size = -(-len(words) // parts)
        for part in range(parts):
            chunk = words[part * size : (part + 1) * size]
            if chunk:
                suffix = f" {part + 1}" if parts > 1 else ""
                units.append({"title_vi": theme.title_vi + suffix, "title_zh": theme.title_zh, "icon": theme.icon, "words": chunk})
    # A few stray words join the last unit rather than form a unit of their own.
    if units and len(leftovers) < MIN_UNIT_WORDS:
        units[-1]["words"] += leftovers
        leftovers = []
    for start in range(0, len(leftovers), WORDS_PER_UNIT):
        units.append({"title_vi": f"Từ thông dụng {start // WORDS_PER_UNIT + 1}", "title_zh": "常用词", "icon": "📌",
                      "words": leftovers[start : start + WORDS_PER_UNIT]})

    found = [w for u in units for w in u["words"]]
    if sorted(found) != sorted(distinct):
        raise RuntimeError(f"HSK {level_code}: units do not hold every word exactly once")
    return units


def mixed_option(option: str) -> bool:
    """Chinese and Latin in one option, other than a term quoted or glossed in brackets."""
    bare = QUOTED_OR_BRACKETED.sub("", option)
    return bool(HAN.search(bare) and LATIN_WORD.search(bare))


def valid_questions(questions: list[Question]) -> list[dict]:
    good = []
    for q in questions:
        # Options get shuffled, so a printed letter would stop matching its place.
        options = list(dict.fromkeys(OPTION_LETTER.sub("", o.strip()) for o in q.options))
        answer = OPTION_LETTER.sub("", q.answer.strip())
        if len(options) == 4 and answer in options and not any(mixed_option(o) for o in options):
            good.append({"question": q.question, "options": options, "answer": answer, "explanation": q.explanation})
    return good


def off_level_share(text: str, allowed: set[str]) -> float:
    chars = HAN.findall(text)
    return sum(c not in allowed for c in chars) / len(chars) if chars else 1.0


def enrich_unit(gemini: Gemini, level_code: str, unit: dict, grammar: list[dict], allowed: set[str], by_word: dict) -> None:
    words = "; ".join(describe(by_word[w]) for w in unit["words"])
    points = "\n".join(f"- {g['content']} ({g.get('subcategory') or g.get('category_name') or g.get('category') or ''})" for g in grammar)
    note = ""
    for _ in range(3):
        result = gemini.ask(
            f"""Bạn soạn bài học tiếng Trung cho người Việt, cấp HSK {level_code}, đơn vị "{unit['title_vi']}".
Từ của đơn vị: {words}

1. dialogue: hội thoại 4-6 lượt lời tự nhiên giữa hai người, dùng nhiều từ của đơn vị. Chỉ dùng từ ở cấp HSK {level_code} trở xuống.
   Kèm pinyin có dấu và bản dịch tiếng Việt cho từng lượt; title bằng tiếng Việt; speaker là A hoặc B.
   questions: 2 câu hỏi đọc hiểu bằng tiếng Việt dựa đúng vào hội thoại, mỗi câu đúng 4 lựa chọn, answer trùng nguyên văn một lựa chọn.
2. grammar: với MỖI điểm ngữ pháp dưới đây, giữ nguyên content, giải thích ngắn (1-2 câu) bằng tiếng Việt cách dùng, kèm một câu ví dụ đúng cấp.
{points or '(đơn vị này không có điểm ngữ pháp, trả về danh sách rỗng)'}
3. grammar_questions: 2 câu trắc nghiệm kiểm tra các điểm ngữ pháp trên (rỗng nếu không có), mỗi câu đúng 4 lựa chọn, chỉ một đáp án đúng.
{note}""",
            Enrichment,
        )
        text = "".join(line.zh for line in result.dialogue.lines)
        share = off_level_share(text, allowed)
        # The model sometimes leaks English or Vietnamese into a Chinese line, and that line would be read aloud.
        mixed = [zh for zh in [line.zh for line in result.dialogue.lines] + [g.example_zh for g in result.grammar] if LATIN_WORD.search(zh)]
        if mixed:
            note = "LƯU Ý: lần trước có dòng tiếng Trung lẫn chữ Latin. Trường zh chỉ được chứa chữ Hán và dấu câu."
            continue
        if share <= MAX_OFF_LEVEL_SHARE and len(result.dialogue.lines) >= 4:
            questions = valid_questions(result.dialogue.questions)
            if questions:
                unit["dialogue"] = {"title": result.dialogue.title, "lines": [line.model_dump() for line in result.dialogue.lines],
                                    "questions": questions, "off_level_share": round(share, 3)}
                break
        extra = sorted({c for c in HAN.findall(text) if c not in allowed})
        note = f"LƯU Ý: lần trước dùng chữ vượt cấp {''.join(extra[:30])}. Hãy thay bằng từ dễ hơn."
    # The model may reformat a point's name, so notes pair with points by position; the syllabus text wins.
    notes = [{**point, **note.model_dump(), "content": point["content"]} for point, note in zip(grammar, result.grammar)]
    unit["grammar"] = notes if len(notes) == len(grammar) else []
    unit["grammar_questions"] = valid_questions(result.grammar_questions)


def save(document: dict) -> None:
    OUTPUT.write_text(json.dumps(document, ensure_ascii=False, indent=1), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--step", choices=["units", "enrich"], required=True)
    parser.add_argument("--levels", nargs="*", default=list(LEVELS))
    args = parser.parse_args()
    load_env()
    document = json.loads(OUTPUT.read_text(encoding="utf-8")) if OUTPUT.exists() else {}
    gemini = Gemini()
    with psycopg.connect(dsn()) as conn:
        allowed: set[str] = set()
        for code, level in LEVELS.items():
            data = load_level(conn, level)
            allowed |= set(data["hanzi"]) | {c for w in data["words"] for c in HAN.findall(w["simplified"])}
            if code not in args.levels:
                continue
            if args.step == "units":
                if code not in document:
                    print(f"HSK {code}: {len(data['words'])} words", flush=True)
                    document[code] = {"units": build_units(gemini, code, data)}
                    save(document)
                print(f"HSK {code}: {len(document[code]['units'])} units", flush=True)
                continue
            units = document[code]["units"]
            by_word = {w["simplified"]: w for w in data["words"]}
            for index, unit in enumerate(units):
                share = data["grammar"][index * len(data["grammar"]) // len(units) : (index + 1) * len(data["grammar"]) // len(units)]
                if "dialogue" in unit and len(unit.get("grammar", [])) == len(share):
                    continue
                enrich_unit(gemini, code, unit, share, allowed, by_word)
                save(document)
                print(f"HSK {code} unit {index + 1}/{len(units)} {unit['title_vi']}: "
                      f"dialogue={'dialogue' in unit} grammar={len(unit['grammar'])}/{len(share)} gq={len(unit['grammar_questions'])}", flush=True)


if __name__ == "__main__":
    main()
