"""Makes an mp3 for every listening lesson that has no audio yet.

    pip install edge-tts
    python scripts/generate_passage_audio.py <output-folder> [--api http://localhost:8080]

Then upload the files and link them to their lessons:

    mvn spring-boot:run "-Dspring-boot.run.arguments=--import-passage-audio=<output-folder>"

edge-tts is an unofficial client of Microsoft's online voices: fine for a course project, not a
supported service for production use.
"""
import argparse
import asyncio
import json
import pathlib
import urllib.request

import edge_tts

VOICE = "zh-CN-XiaoxiaoNeural"
# A slower voice for the lowest levels, where learners still sound out every word.
RATE_BY_LEVEL = {1: "-25%", 2: "-20%", 3: "-10%"}


def get(api: str, path: str):
    with urllib.request.urlopen(api + path, timeout=30) as response:
        return json.load(response)["data"]


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=pathlib.Path)
    parser.add_argument("--api", default="http://localhost:8080")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    made = 0
    for course in get(args.api, "/api/courses"):
        for lesson in get(args.api, f"/api/lessons/course/{course['id']}"):
            if lesson.get("contentType") != "LISTENING":
                continue
            item = lesson["lessonPayload"]["sections"]["listenings"][0]
            target = args.output / f"{lesson['id']}.mp3"
            if item.get("audio_url") or target.exists():
                continue
            rate = RATE_BY_LEVEL.get(course["hskLevel"], "+0%")
            await edge_tts.Communicate(item["transcript"], VOICE, rate=rate).save(str(target))
            made += 1
            print(f"{target.name}  {lesson['title']}")
    print(f"{made} files made in {args.output}")


if __name__ == "__main__":
    asyncio.run(main())
