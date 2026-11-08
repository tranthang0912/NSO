"""Read-only parity checks against SRC NSOACE FIX. Does not regenerate or edit the source."""
import hashlib
import json
import re
from pathlib import Path
from extract_data import ROOT_DIR, SRC_DIR, DATA_SRC, parse_binary_map, parse_sql_inserts, STATIC_TABLES


def numbers(text, name, opening, closing):
    match = re.search(r"\b" + name + r"\s*=\s*" + re.escape(opening) + r"(.*?)" + re.escape(closing), text, re.S)
    assert match, f"Missing table: {name}"
    return [int(value) for value in re.findall(r"-?\d+", match.group(1))]


def main():
    game_data = (SRC_DIR / "src/main/java/com/nsoz/server/GameData.java").read_text(encoding="utf-8")
    server = (ROOT_DIR / "src/main/java/com/nsoweb/world/EndgameOps.java").read_text(encoding="utf-8")
    client = (ROOT_DIR / "web/js/ui_panels.js").read_text(encoding="utf-8")
    tables = ["UP_CRYSTAL", "UP_CLOTHE", "UP_ADORN", "UP_WEAPON", "COIN_UP_CRYSTAL",
              "COIN_UP_CLOTHE", "COIN_UP_ADORN", "COIN_UP_WEAPON", "GOLD_UP", "MAX_PERCENT"]
    for name in tables:
        expected = numbers(game_data, name, "{", "}")
        assert expected == numbers(server, name, "{", "}"), f"Server upgrade table differs: {name}"
        assert expected == numbers(client, name, "[", "]"), f"Client upgrade table differs: {name}"
    for map_id in range(177):
        generated = json.loads((ROOT_DIR / f"data/assets/maps/{map_id}.json").read_text(encoding="utf-8"))
        width, height, tiles = parse_binary_map(map_id)
        assert (generated["tmw"], generated["tmh"], generated["tiles"]) == (width, height, tiles), map_id
        assert len(generated["types"]) == width * height, map_id
    copied = 0
    for source, output in [("Mob/2", "mob"), ("Effect/2", "effect"), ("EffectAuto/2", "effect_auto"),
                           ("Map/2/tile", "map/tile"), ("Map/2/bg", "map/bg"), ("Object/2", "object")]:
        for original in (DATA_SRC / "Img" / source).iterdir():
            if not original.is_file():
                continue
            generated = ROOT_DIR / "data/assets/img" / output / original.name
            assert hashlib.sha256(original.read_bytes()).digest() == hashlib.sha256(generated.read_bytes()).digest(), str(generated)
            copied += 1
    raw = parse_sql_inserts((SRC_DIR / "nsoz.sql").read_text(encoding="utf-8"), STATIC_TABLES)
    others = {r[1]: r[2] for r in raw["others"]}
    for name in ["head_normal", "body_normal", "leg", "head_jump", "body_jump"]:
        assert name in others, f"Missing original sprite data: {name}"
    print(f"PASS: {len(tables)} upgrade tables match GameData in server and client; 177 map tile arrays match Data/Map;")
    print(f"PASS: {copied} mob/effect/map/object assets are byte-identical to zoom 2 source; original sprite tables present.")


if __name__ == "__main__":
    main()
